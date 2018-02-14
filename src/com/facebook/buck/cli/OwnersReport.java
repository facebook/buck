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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Used to determine owners of specific files */
final class OwnersReport {
  final ImmutableSetMultimap<TargetNode<?, ?>, Path> owners;
  final ImmutableSet<Path> inputsWithNoOwners;
  final ImmutableSet<String> nonExistentInputs;
  final ImmutableSet<String> nonFileInputs;

  private OwnersReport(
      SetMultimap<TargetNode<?, ?>, Path> owners,
      Set<Path> inputsWithNoOwners,
      Set<String> nonExistentInputs,
      Set<String> nonFileInputs) {
    this.owners = ImmutableSetMultimap.copyOf(owners);
    this.inputsWithNoOwners = ImmutableSet.copyOf(inputsWithNoOwners);
    this.nonExistentInputs = ImmutableSet.copyOf(nonExistentInputs);
    this.nonFileInputs = ImmutableSet.copyOf(nonFileInputs);
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

  @VisibleForTesting
  static OwnersReport emptyReport() {
    return new OwnersReport(
        ImmutableSetMultimap.of(), new HashSet<>(), new HashSet<>(), new HashSet<>());
  }

  private boolean isEmpty() {
    return owners.size() == 0
        && inputsWithNoOwners.size() == 0
        && nonExistentInputs.size() == 0
        && nonFileInputs.size() == 0;
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

    SetMultimap<TargetNode<?, ?>, Path> updatedOwners = TreeMultimap.create(owners);
    updatedOwners.putAll(other.owners);

    return new OwnersReport(
        updatedOwners,
        Sets.intersection(inputsWithNoOwners, other.inputsWithNoOwners),
        Sets.union(nonExistentInputs, other.nonExistentInputs),
        Sets.union(nonFileInputs, other.nonFileInputs));
  }

  @VisibleForTesting
  static OwnersReport generateOwnersReport(
      Cell rootCell, TargetNode<?, ?> targetNode, String filePath) {
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

  static Builder builder(Cell rootCell, Parser parser, BuckEventBus eventBus) {
    return new Builder(rootCell, parser, eventBus);
  }

  static final class Builder {
    private final Cell rootCell;
    private final Parser parser;
    private final BuckEventBus eventBus;

    private Builder(Cell rootCell, Parser parser, BuckEventBus eventBus) {
      this.rootCell = rootCell;
      this.parser = parser;
      this.eventBus = eventBus;
    }

    private OwnersReport getReportForBasePath(
        Map<Path, ImmutableSet<TargetNode<?, ?>>> map,
        ListeningExecutorService executor,
        Cell cell,
        Path basePath,
        Path cellRelativePath) {
      Path buckFile = cell.getFilesystem().resolve(basePath).resolve(cell.getBuildFileName());
      ImmutableSet<TargetNode<?, ?>> targetNodes =
          map.computeIfAbsent(
              buckFile,
              basePath1 -> {
                try {
                  return parser.getAllTargetNodes(
                      eventBus, cell, /* enable profiling */ false, executor, basePath1);
                } catch (BuildFileParseException e) {
                  throw new HumanReadableException(e);
                }
              });
      return targetNodes
          .stream()
          .map(targetNode -> generateOwnersReport(cell, targetNode, cellRelativePath.toString()))
          .reduce(OwnersReport.emptyReport(), OwnersReport::updatedWith);
    }

    private ImmutableSet<Path> getAllBasePathsForPath(
        BuildFileTree buildFileTree, Path cellRelativePath) {
      Collection<Path> pathTree =
          rootCell.isEnforcingBuckPackageBoundaries(cellRelativePath)
              ? ImmutableSet.of(cellRelativePath)
              : IntStream.range(1, cellRelativePath.getNameCount())
                  .mapToObj(end -> cellRelativePath.subpath(0, end))
                  .collect(Collectors.toList());
      return RichStream.from(pathTree)
          .map(buildFileTree::getBasePathOfAncestorTarget)
          .flatMap(Optionals::toStream)
          .toImmutableSet();
    }

    OwnersReport build(
        ImmutableMap<Cell, BuildFileTree> buildFileTrees,
        ListeningExecutorService executor,
        Iterable<String> arguments) {
      ProjectFilesystem rootCellFilesystem = rootCell.getFilesystem();
      final Path rootPath = rootCellFilesystem.getRootPath();
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
                      path ->
                          cellsByRootLength
                              .stream()
                              .filter(cell -> path.startsWith(cell.getRoot()))
                              .findFirst()));
      Set<String> missingFiles =
          RichStream.from(arguments)
              .filter(f -> !Files.exists(rootCellFilesystem.getPathForRelativePath(f)))
              .collect(Collectors.toSet());

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
            Preconditions.checkNotNull(
                buildFileTrees.get(cell),
                "cell is be derived from buildFileTree keys, so should be present");

        // Path from buck file to target nodes. We keep our own cache here since the manner that we
        // are calling the parser does not make use of its internal caches.
        Map<Path, ImmutableSet<TargetNode<?, ?>>> map = new HashMap<>();
        for (Path absolutePath : entry.getValue()) {
          Path cellRelativePath = cell.getFilesystem().relativize(absolutePath);
          ImmutableSet<Path> basePaths = getAllBasePathsForPath(buildFileTree, cellRelativePath);
          if (basePaths.isEmpty()) {
            inputWithNoOwners.add(absolutePath);
            continue;
          }
          report =
              basePaths
                  .stream()
                  .map(
                      basePath ->
                          getReportForBasePath(map, executor, cell, basePath, cellRelativePath))
                  .reduce(report, OwnersReport::updatedWith);
        }
      }

      return report.updatedWith(
          new OwnersReport(
              ImmutableSetMultimap.of(),
              /* inputWithNoOwners */ inputWithNoOwners.build(),
              /* nonExistentInputs */ missingFiles,
              new HashSet<>()));
    }
  }
}
