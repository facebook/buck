/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.FilesystemBackedBuildFileTree;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.Ansi;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Outputs targets that own a specified list of files.
 */
public class AuditOwnerCommand extends AbstractCommandRunner<AuditOwnerOptions> {

  private static final String FILE_INDENT = "    ";
  private static final int BUILD_TARGET_ERROR = 13;

  public AuditOwnerCommand(CommandRunnerParams params) {
    super(params);
  }

  @VisibleForTesting
  static final class OwnersReport {
    final ImmutableSetMultimap<TargetNode<?>, Path> owners;
    final ImmutableSet<Path> inputsWithNoOwners;
    final ImmutableSet<String> nonExistentInputs;
    final ImmutableSet<String> nonFileInputs;

    public OwnersReport(SetMultimap<TargetNode<?>, Path> owners,
                        Set<Path> inputsWithNoOwners,
                        Set<String> nonExistentInputs,
                        Set<String> nonFileInputs) {
      this.owners = ImmutableSetMultimap.copyOf(owners);
      this.inputsWithNoOwners = ImmutableSet.copyOf(inputsWithNoOwners);
      this.nonExistentInputs = ImmutableSet.copyOf(nonExistentInputs);
      this.nonFileInputs = ImmutableSet.copyOf(nonFileInputs);
    }

    public static OwnersReport emptyReport() {
      return new OwnersReport(
          ImmutableSetMultimap.<TargetNode<?>, Path>of(),
          Sets.<Path>newHashSet(),
          Sets.<String>newHashSet(),
          Sets.<String>newHashSet());
    }

    public OwnersReport updatedWith(OwnersReport other) {
      SetMultimap<TargetNode<?>, Path> updatedOwners =
          TreeMultimap.create(owners);
      updatedOwners.putAll(other.owners);

      return new OwnersReport(
          updatedOwners,
          Sets.intersection(inputsWithNoOwners, other.inputsWithNoOwners),
          Sets.union(nonExistentInputs, other.nonExistentInputs),
          Sets.union(nonFileInputs, other.nonFileInputs));
    }
  }

  @Override
  AuditOwnerOptions createOptions(BuckConfig buckConfig) {
    return new AuditOwnerOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(AuditOwnerOptions options)
      throws IOException, InterruptedException {
    OwnersReport report = OwnersReport.emptyReport();
    Map<Path, List<TargetNode<?>>> targetNodes = Maps.newHashMap();
    ParserConfig parserConfig = new ParserConfig(options.getBuckConfig());
    BuildFileTree buildFileTree = new FilesystemBackedBuildFileTree(
        getProjectFilesystem(),
        parserConfig.getBuildFileName());

    for (Path filePath : options.getArgumentsAsPaths(getProjectFilesystem().getRootPath())) {
      Optional<Path> basePath = buildFileTree.getBasePathOfAncestorTarget(filePath);
      if (!basePath.isPresent()) {
        report = report.updatedWith(
          new OwnersReport(
              ImmutableSetMultimap.<TargetNode<?>, Path>of(),
              /* inputWithNoOwners */ ImmutableSet.of(filePath),
              Sets.<String>newHashSet(),
              Sets.<String>newHashSet()));
        continue;
      }

      Path buckFile = basePath.get().resolve(parserConfig.getBuildFileName());
      Preconditions.checkState(getProjectFilesystem().exists(buckFile));

      // Get the target base name.
      Path targetBasePath = MorePaths.relativize(
          getProjectFilesystem().getRootPath().toAbsolutePath(),
          buckFile.toAbsolutePath().getParent());
      String targetBaseName = "//" + targetBasePath.toString();

      // Parse buck files and load target nodes.
      if (!targetNodes.containsKey(buckFile)) {
        try {
          Parser parser = getParser();

          List<Map<String, Object>> buildFileTargets = parser.parseBuildFile(
              buckFile,
              parserConfig,
              environment,
              console,
              getBuckEventBus());

          for (Map<String, Object> buildFileTarget : buildFileTargets) {
            if (!buildFileTarget.containsKey("name")) {
              continue;
            }

            BuildTarget target = BuildTarget.builder(
                targetBaseName,
                (String) buildFileTarget.get("name")).build();

            if (!targetNodes.containsKey(buckFile)) {
              targetNodes.put(buckFile, Lists.<TargetNode<?>>newArrayList());
            }
            TargetNode<?> parsedTargetNode = parser.getTargetNode(target);
            if (parsedTargetNode != null) {
              targetNodes.get(buckFile).add(parsedTargetNode);
            }
          }
        } catch (BuildFileParseException | BuildTargetException e) {
          console.getStdErr().format("Could not parse build targets for %s", targetBaseName);
          return BUILD_TARGET_ERROR;
        }
      }

      for (TargetNode<?> targetNode : targetNodes.get(buckFile)) {
        report = report.updatedWith(
            generateOwnersReport(
                targetNode,
                ImmutableList.of(filePath.toString()),
                options.isGuessForDeletedEnabled()));
      }
    }

    printReport(options, report);
    return 0;
  }

  @VisibleForTesting
  OwnersReport generateOwnersReport(
      TargetNode<?> targetNode,
      Iterable<String> filePaths,
      boolean guessForDeletedEnabled) {

    // Process arguments assuming they are all relative file paths.
    Set<Path> inputs = Sets.newHashSet();
    Set<String> nonExistentInputs = Sets.newHashSet();
    Set<String> nonFileInputs = Sets.newHashSet();

    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    for (String filePath : filePaths) {
      File file = projectFilesystem.getFileForRelativePath(filePath);
      if (!file.exists()) {
        nonExistentInputs.add(filePath);
      } else if (!file.isFile()) {
        nonFileInputs.add(filePath);
      } else {
        inputs.add(Paths.get(filePath));
      }
    }

    // Try to find owners for each valid and existing file.
    Set<Path> inputsWithNoOwners = Sets.newHashSet(inputs);
    SetMultimap<TargetNode<?>, Path> owners = TreeMultimap.create();
    for (final Path commandInput : inputs) {
      Predicate<Path> startsWith = new Predicate<Path>() {
        @Override
        public boolean apply(Path input) {
          return !commandInput.equals(input) && commandInput.startsWith(input);
        }
      };

      Set<Path> ruleInputs = targetNode.getInputs();
      if (ruleInputs.contains(commandInput) ||
          FluentIterable.from(ruleInputs).anyMatch(startsWith)) {
        inputsWithNoOwners.remove(commandInput);
        owners.put(targetNode, commandInput);
      }
    }

    // Try to guess owners for nonexistent files.
    if (guessForDeletedEnabled) {
      for (String nonExsitentInput : nonExistentInputs) {
        owners.put(targetNode, new File(nonExsitentInput).toPath());
      }
    }

    return new OwnersReport(owners, inputsWithNoOwners, nonExistentInputs, nonFileInputs);
  }

  private void printReport(AuditOwnerOptions options, OwnersReport report) throws IOException {
    if (options.isFullReportEnabled()) {
      printFullReport(report);
    } else {
      if (options.shouldGenerateJsonOutput()) {
        printOwnersOnlyJsonReport(report);
      } else {
        printOwnersOnlyReport(report);
      }
    }
  }

  /**
   * Print only targets which were identified as owners.
   */
  private void printOwnersOnlyReport(OwnersReport report) {
    Set<TargetNode<?>> sortedTargetNodes = report.owners.keySet();
    for (TargetNode<?> targetNode : sortedTargetNodes) {
      console.getStdOut().println(targetNode.getBuildTarget().getFullyQualifiedName());
    }
  }

  /**
  * Print only targets which were identified as owners in JSON.
  */
  @VisibleForTesting
  void printOwnersOnlyJsonReport(OwnersReport report) throws IOException {
    final Multimap<String, String> output = TreeMultimap.create();

    Set<TargetNode<?>> sortedTargetNodes = report.owners.keySet();
    for (TargetNode<?> targetNode : sortedTargetNodes) {
      Set<Path> files = report.owners.get(targetNode);
      for (Path input : files) {
        output.put(input.toString(), targetNode.getBuildTarget().getFullyQualifiedName());
      }
    }

    getObjectMapper().writeValue(console.getStdOut(), output.asMap());
  }

  /**
   * Print detailed report on all owners.
   */
  private void printFullReport(OwnersReport report) {
    PrintStream out = console.getStdOut();
    Ansi ansi = console.getAnsi();
    if (report.owners.isEmpty()) {
      out.println(ansi.asErrorText("No owners found"));
    } else {
      out.println(ansi.asSuccessText("Owners:"));
      for (TargetNode<?> targetNode : report.owners.keySet()) {
        out.println(targetNode.getBuildTarget().getFullyQualifiedName());
        Set<Path> files = report.owners.get(targetNode);
        for (Path input : files) {
          out.println(FILE_INDENT + input);
        }
      }
    }

    if (!report.inputsWithNoOwners.isEmpty()) {
      out.println();
      out.println(ansi.asErrorText("Files without owners:"));
      for (Path input : report.inputsWithNoOwners) {
        out.println(FILE_INDENT + input);
      }
    }

    if (!report.nonExistentInputs.isEmpty()) {
      out.println();
      out.println(ansi.asErrorText("Non existent files:"));
      for (String input : report.nonExistentInputs) {
        out.println(FILE_INDENT + input);
      }
    }

    if (!report.nonFileInputs.isEmpty()) {
      out.println();
      out.println(ansi.asErrorText("Non-file inputs:"));
      for (String input : report.nonFileInputs) {
        out.println(FILE_INDENT + input);
      }
    }
  }

  @Override
  String getUsageIntro() {
    return "prints targets that own specified files";
  }

}
