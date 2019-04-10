/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.cli;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Used to determine owners of specific files */
final class OwnersReport {
  final ImmutableSetMultimap<TargetNode<?>, Path> owners;
  final ImmutableSet<Path> inputsWithNoOwners;
  final ImmutableSet<String> nonExistentInputs;
  final ImmutableSet<String> nonFileInputs;

  private OwnersReport(
      ImmutableSetMultimap<TargetNode<?>, Path> owners,
      ImmutableSet<Path> inputsWithNoOwners,
      ImmutableSet<String> nonExistentInputs,
      ImmutableSet<String> nonFileInputs) {
    this.owners = owners;
    this.inputsWithNoOwners = inputsWithNoOwners;
    this.nonExistentInputs = nonExistentInputs;
    this.nonFileInputs = nonFileInputs;
  }

  /** Get the set of files that were requested that did not have an owning rule */
  public ImmutableSet<Path> getInputsWithNoOwners() {
    return inputsWithNoOwners;
  }

  /** Get the set of inputs specified in a build rule that do not exist on disk */
  public ImmutableSet<String> getNonExistentInputs() {
    return nonExistentInputs;
  }

  /** Get inputs to a build rule that do not appear to be regular files */
  public ImmutableSet<String> getNonFileInputs() {
    return nonFileInputs;
  }

  private static OwnersReport emptyReport() {
    return new OwnersReport(
        ImmutableSetMultimap.of(), ImmutableSet.of(), ImmutableSet.of(), ImmutableSet.of());
  }

  private boolean isEmpty() {
    return owners.isEmpty()
        && inputsWithNoOwners.isEmpty()
        && nonExistentInputs.isEmpty()
        && nonFileInputs.isEmpty();
  }

  @VisibleForTesting
  OwnersReport updatedWith(OwnersReport other) {
    // If either this or other are empty, the intersection below for missing files will get
    // screwed up. This mostly is just so that when we do a fold elsewhere in the class against
    // a default empty object, we don't obliterate inputsWithNoOwners
    if (this.isEmpty()) {
      return other;
    } else if (other.isEmpty()) {
      return this;
    }

    SetMultimap<TargetNode<?>, Path> updatedOwners = TreeMultimap.create(owners);
    updatedOwners.putAll(other.owners);

    return new OwnersReport(
        ImmutableSetMultimap.copyOf(updatedOwners),
        Sets.intersection(inputsWithNoOwners, other.inputsWithNoOwners).immutableCopy(),
        Sets.union(nonExistentInputs, other.nonExistentInputs).immutableCopy(),
        Sets.union(nonFileInputs, other.nonFileInputs).immutableCopy());
  }

  @VisibleForTesting
  static OwnersReport generateOwnersReport(
      Cell rootCell, TargetNode<?> targetNode, String filePath) {
    Path file = rootCell.getFilesystem().getPathForRelativePath(filePath);
    if (!Files.exists(file)) {
      return new OwnersReport(
          ImmutableSetMultimap.of(),
          ImmutableSet.of(),
          ImmutableSet.of(filePath),
          ImmutableSet.of());
    } else if (!Files.isRegularFile(file)) {
      return new OwnersReport(
          ImmutableSetMultimap.of(),
          ImmutableSet.of(),
          ImmutableSet.of(),
          ImmutableSet.of(filePath));
    } else {
      Path commandInput = rootCell.getFilesystem().getPath(filePath);
      Set<Path> ruleInputs = targetNode.getInputs();
      Predicate<Path> startsWith =
          input -> !commandInput.equals(input) && commandInput.startsWith(input);
      if (ruleInputs.contains(commandInput) || ruleInputs.stream().anyMatch(startsWith)) {
        return new OwnersReport(
            ImmutableSetMultimap.of(targetNode, commandInput),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of());
      } else {
        return new OwnersReport(
            ImmutableSetMultimap.of(),
            ImmutableSet.of(commandInput),
            ImmutableSet.of(),
            ImmutableSet.of());
      }
    }
  }

  static Builder builder(
      Cell rootCell,
      Parser parser,
      PerBuildState parserState,
      TargetConfiguration targetConfiguration) {
    return new Builder(rootCell, parser, parserState, targetConfiguration);
  }

  static final class Builder {
    private final Cell rootCell;
    private final Parser parser;
    private final PerBuildState parserState;
    private final TargetConfiguration targetConfiguration;

    private Builder(
        Cell rootCell,
        Parser parser,
        PerBuildState parserState,
        TargetConfiguration targetConfiguration) {
      this.rootCell = rootCell;
      this.parser = parser;
      this.parserState = parserState;
      this.targetConfiguration = targetConfiguration;
    }

    private OwnersReport getReportForBasePath(
        Map<Path, ImmutableList<TargetNode<?>>> map,
        Cell cell,
        Path basePath,
        Path cellRelativePath) {
      Path buckFile =
          cell.getFilesystem()
              .resolve(basePath)
              .resolve(cell.getBuckConfigView(ParserConfig.class).getBuildFileName());
      ImmutableList<TargetNode<?>> targetNodes =
          map.computeIfAbsent(
              buckFile,
              basePath1 -> {
                try {
                  return parser.getAllTargetNodesWithTargetCompatibilityFiltering(
                      parserState, cell, basePath1, targetConfiguration);
                } catch (BuildFileParseException e) {
                  throw new HumanReadableException(e);
                }
              });
      return targetNodes.stream()
          .map(targetNode -> generateOwnersReport(cell, targetNode, cellRelativePath.toString()))
          .reduce(OwnersReport.emptyReport(), OwnersReport::updatedWith);
    }

    private ImmutableSet<Path> getAllBasePathsForPath(
        BuildFileTree buildFileTree, Path cellRelativePath) {
      if (rootCell
          .getBuckConfigView(ParserConfig.class)
          .isEnforcingBuckPackageBoundaries(cellRelativePath)) {
        return buildFileTree
            .getBasePathOfAncestorTarget(cellRelativePath)
            .map(ImmutableSet::of)
            .orElse(ImmutableSet.of());
      }
      ImmutableSet.Builder<Path> resultBuilder =
          ImmutableSet.builderWithExpectedSize(cellRelativePath.getNameCount());
      for (int i = 1; i < cellRelativePath.getNameCount(); i++) {
        buildFileTree
            .getBasePathOfAncestorTarget(cellRelativePath.subpath(0, i))
            .ifPresent(resultBuilder::add);
      }
      return resultBuilder.build();
    }

    OwnersReport build(
        ImmutableMap<Cell, BuildFileTree> buildFileTrees, Iterable<String> arguments) {
      ProjectFilesystem rootCellFilesystem = rootCell.getFilesystem();
      Path rootPath = rootCellFilesystem.getRootPath();
      Preconditions.checkState(rootPath.isAbsolute());

      // Order cells by cell path length so that nested cells will resolve to the most specific
      // cell.
      List<Cell> cellsByRootLength =
          RichStream.from(buildFileTrees.keySet())
              .sorted(
                  Comparator.comparing((Cell cell) -> cell.getRoot().toString().length())
                      .reversed())
              .toImmutableList();

      Map<Optional<Cell>, List<Path>> argumentsByCell =
          RichStream.from(arguments)
              // Assume paths given are relative to root cell.
              .map(rootCellFilesystem::getPathForRelativePath)
              // Filter out any non-existent paths.
              .filter(Files::exists)
              // Resolve them all to absolute paths.
              .map(
                  pathString -> {
                    try {
                      return pathString.toRealPath();
                    } catch (IOException e) {
                      throw new RuntimeException(e);
                    }
                  })
              // Group them into cells that they belong to.
              .collect(
                  Collectors.groupingBy(
                      path -> {
                        for (Cell c : cellsByRootLength) {
                          if (path.startsWith(c.getRoot())) {
                            return Optional.of(c);
                          }
                        }
                        return Optional.empty();
                      }));
      ImmutableSet<String> missingFiles =
          RichStream.from(arguments)
              .filter(f -> !Files.exists(rootCellFilesystem.getPathForRelativePath(f)))
              .map(MorePaths::pathWithPlatformSeparators)
              .toImmutableSet();

      ImmutableSet.Builder<Path> inputWithNoOwners = ImmutableSet.builder();
      OwnersReport report = OwnersReport.emptyReport();
      // Process every cell's files independently.
      for (Map.Entry<Optional<Cell>, List<Path>> entry : argumentsByCell.entrySet()) {
        if (!entry.getKey().isPresent()) {
          inputWithNoOwners.addAll(entry.getValue());
          continue;
        }

        Cell cell = entry.getKey().get();
        BuildFileTree buildFileTree =
            Objects.requireNonNull(
                buildFileTrees.get(cell),
                "cell is be derived from buildFileTree keys, so should be present");

        // Path from buck file to target nodes. We keep our own cache here since the manner that we
        // are calling the parser does not make use of its internal caches.
        Map<Path, ImmutableList<TargetNode<?>>> map = new HashMap<>();
        for (Path absolutePath : entry.getValue()) {
          Path cellRelativePath = cell.getFilesystem().relativize(absolutePath);
          ImmutableSet<Path> basePaths = getAllBasePathsForPath(buildFileTree, cellRelativePath);
          if (basePaths.isEmpty()) {
            inputWithNoOwners.add(absolutePath);
            continue;
          }
          report =
              basePaths.stream()
                  .map(basePath -> getReportForBasePath(map, cell, basePath, cellRelativePath))
                  .reduce(report, OwnersReport::updatedWith);
        }
      }

      return report.updatedWith(
          new OwnersReport(
              ImmutableSetMultimap.of(),
              /* inputWithNoOwners */ inputWithNoOwners.build(),
              /* nonExistentInputs */ missingFiles,
              ImmutableSet.of()));
    }
  }
}
