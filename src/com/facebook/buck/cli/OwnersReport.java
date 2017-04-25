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
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.Console;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class OwnersReport {
  final ImmutableSetMultimap<TargetNode<?, ?>, Path> owners;
  final ImmutableSet<Path> inputsWithNoOwners;
  final ImmutableSet<String> nonExistentInputs;
  final ImmutableSet<String> nonFileInputs;

  OwnersReport(
      SetMultimap<TargetNode<?, ?>, Path> owners,
      Set<Path> inputsWithNoOwners,
      Set<String> nonExistentInputs,
      Set<String> nonFileInputs) {
    this.owners = ImmutableSetMultimap.copyOf(owners);
    this.inputsWithNoOwners = ImmutableSet.copyOf(inputsWithNoOwners);
    this.nonExistentInputs = ImmutableSet.copyOf(nonExistentInputs);
    this.nonFileInputs = ImmutableSet.copyOf(nonFileInputs);
  }

  static OwnersReport emptyReport() {
    return new OwnersReport(
        ImmutableSetMultimap.of(), Sets.newHashSet(), Sets.newHashSet(), Sets.newHashSet());
  }

  OwnersReport updatedWith(OwnersReport other) {
    SetMultimap<TargetNode<?, ?>, Path> updatedOwners = TreeMultimap.create(owners);
    updatedOwners.putAll(other.owners);

    return new OwnersReport(
        updatedOwners,
        Sets.intersection(inputsWithNoOwners, other.inputsWithNoOwners),
        Sets.union(nonExistentInputs, other.nonExistentInputs),
        Sets.union(nonFileInputs, other.nonFileInputs));
  }

  /** @return relative paths under the project root */
  static Iterable<Path> getArgumentsAsPaths(Path projectRoot, Iterable<String> args)
      throws IOException {
    return PathArguments.getCanonicalFilesUnderProjectRoot(projectRoot, args)
        .relativePathsUnderProjectRoot;
  }

  @VisibleForTesting
  static OwnersReport generateOwnersReport(
      Cell rootCell, TargetNode<?, ?> targetNode, Iterable<String> filePaths) {

    // Process arguments assuming they are all relative file paths.
    Set<Path> inputs = Sets.newHashSet();
    Set<String> nonExistentInputs = Sets.newHashSet();
    Set<String> nonFileInputs = Sets.newHashSet();

    for (String filePath : filePaths) {
      Path file = rootCell.getFilesystem().getPathForRelativePath(filePath);
      if (!Files.exists(file)) {
        nonExistentInputs.add(filePath);
      } else if (!Files.isRegularFile(file)) {
        nonFileInputs.add(filePath);
      } else {
        inputs.add(rootCell.getFilesystem().getPath(filePath));
      }
    }

    // Try to find owners for each valid and existing file.
    Set<Path> inputsWithNoOwners = Sets.newHashSet(inputs);
    SetMultimap<TargetNode<?, ?>, Path> owners = TreeMultimap.create();
    for (final Path commandInput : inputs) {
      Predicate<Path> startsWith =
          input -> !commandInput.equals(input) && commandInput.startsWith(input);

      Set<Path> ruleInputs = targetNode.getInputs();
      if (ruleInputs.contains(commandInput)
          || FluentIterable.from(ruleInputs).anyMatch(startsWith)) {
        inputsWithNoOwners.remove(commandInput);
        owners.put(targetNode, commandInput);
      }
    }

    return new OwnersReport(owners, inputsWithNoOwners, nonExistentInputs, nonFileInputs);
  }

  static Builder builder(Cell rootCell, Parser parser, BuckEventBus eventBus, Console console) {
    return new Builder(rootCell, parser, eventBus, console);
  }

  static final class Builder {
    private final Cell rootCell;
    private final Parser parser;
    private final BuckEventBus eventBus;
    private final Console console;

    private Builder(Cell rootCell, Parser parser, BuckEventBus eventBus, Console console) {

      this.rootCell = rootCell;
      this.parser = parser;
      this.eventBus = eventBus;
      this.console = console;
    }

    OwnersReport build(
        BuildFileTree buildFileTree, ListeningExecutorService executor, Iterable<String> arguments)
        throws IOException, BuildFileParseException {
      ProjectFilesystem cellFilesystem = rootCell.getFilesystem();
      final Path rootPath = cellFilesystem.getRootPath();
      Preconditions.checkState(rootPath.isAbsolute());
      Map<Path, ImmutableSet<TargetNode<?, ?>>> targetNodes = new HashMap<>();
      OwnersReport report = emptyReport();

      for (Path filePath : getArgumentsAsPaths(rootPath, arguments)) {
        Optional<Path> basePath = buildFileTree.getBasePathOfAncestorTarget(filePath);
        if (!basePath.isPresent()) {
          report =
              report.updatedWith(
                  new OwnersReport(
                      ImmutableSetMultimap.of(),
                      /* inputWithNoOwners */ ImmutableSet.of(filePath),
                      Sets.newHashSet(),
                      Sets.newHashSet()));
          continue;
        }

        Path buckFile = cellFilesystem.resolve(basePath.get()).resolve(rootCell.getBuildFileName());
        Preconditions.checkState(cellFilesystem.exists(buckFile));

        // Parse buck files and load target nodes.
        if (!targetNodes.containsKey(buckFile)) {
          try {
            targetNodes.put(
                buckFile,
                parser.getAllTargetNodes(
                    eventBus, rootCell, /* enable profiling */ false, executor, buckFile));
          } catch (BuildFileParseException e) {
            Path targetBasePath = MorePaths.relativize(rootPath, rootPath.resolve(basePath.get()));
            String targetBaseName = "//" + MorePaths.pathWithUnixSeparators(targetBasePath);

            console
                .getStdErr()
                .format(
                    "Could not parse build targets for %s: %s%n",
                    targetBaseName, e.getHumanReadableErrorMessage());
            throw e;
          }
        }

        for (TargetNode<?, ?> targetNode : targetNodes.get(buckFile)) {
          report =
              report.updatedWith(
                  generateOwnersReport(
                      rootCell, targetNode, ImmutableList.of(filePath.toString())));
        }
      }
      return report;
    }
  }
}
