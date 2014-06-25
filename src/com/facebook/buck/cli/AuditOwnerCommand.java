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

import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.ProjectConfigDescription;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.google.common.collect.Multimap;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Outputs targets that own a specified list of files.
 */
public class AuditOwnerCommand extends AbstractCommandRunner<AuditOwnerOptions> {

  private static final String FILE_INDENT = "    ";

  public AuditOwnerCommand(CommandRunnerParams params) {
    super(params);
  }

  @VisibleForTesting
  static final class OwnersReport {
    final ImmutableSetMultimap<BuildRule, Path> owners;
    final ImmutableSet<Path> inputsWithNoOwners;
    final ImmutableSet<String> nonExistentInputs;
    final ImmutableSet<String> nonFileInputs;

    public OwnersReport(SetMultimap<BuildRule, Path> owners,
                        Set<Path> inputsWithNoOwners,
                        Set<String> nonExistentInputs,
                        Set<String> nonFileInputs) {
      this.owners = ImmutableSetMultimap.copyOf(owners);
      this.inputsWithNoOwners = ImmutableSet.copyOf(inputsWithNoOwners);
      this.nonExistentInputs = ImmutableSet.copyOf(nonExistentInputs);
      this.nonFileInputs = ImmutableSet.copyOf(nonFileInputs);
    }
  }

  @Override
  AuditOwnerOptions createOptions(BuckConfig buckConfig) {
    return new AuditOwnerOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(AuditOwnerOptions options)
      throws IOException, InterruptedException {

    // Build full graph.
    PartialGraph graph;
    try {
      graph = PartialGraph.createFullGraph(
          getProjectFilesystem(),
          options.getDefaultIncludes(),
          getParser(),
          getBuckEventBus());
    } catch (BuildTargetException | BuildFileParseException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    OwnersReport report = generateOwnersReport(graph.getActionGraph(), options);
    printReport(options, report);
    return 0;
  }

  @VisibleForTesting
  OwnersReport generateOwnersReport(ActionGraph graph, AuditOwnerOptions options) {

    // Process arguments assuming they are all relative file paths.
    Set<Path> inputs = Sets.newHashSet();
    Set<String> nonExistentInputs = Sets.newHashSet();
    Set<String> nonFileInputs = Sets.newHashSet();

    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    for (String filePath : options.getArguments()) {
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
    SetMultimap<BuildRule, Path> owners = TreeMultimap.create();
    for (BuildRule rule : graph.getNodes()) {
      for (Path ruleInput : rule.getInputs()) {
        if (inputs.contains(ruleInput)) {
          inputsWithNoOwners.remove(ruleInput);
          owners.put(rule, ruleInput);
        }
      }
    }

    // Try to guess owners for nonexistent files.
    if (options.isGuessForDeletedEnabled()) {
      guessOwnersForNonExistentFiles(graph, owners, nonExistentInputs);
    }

    return new OwnersReport(owners, inputsWithNoOwners, nonExistentInputs, nonFileInputs);
  }

  /**
   * Guess target owners for deleted/missing files by finding first
   * BUCK file and assuming that all targets in this file used
   * missing file as input.
   */
  private void guessOwnersForNonExistentFiles(ActionGraph graph,
      SetMultimap<BuildRule, Path> owners, Set<String> nonExistentFiles) {

    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    for (String nonExistentFile : nonExistentFiles) {
      File file = projectFilesystem.getFileForRelativePath(nonExistentFile);
      File buck = findBuckFileFor(file);
      for (BuildRule rule : graph.getNodes()) {
        if (rule.getType() == ProjectConfigDescription.TYPE) {
          continue;
        }
        try {
          File ruleBuck = rule.getBuildTarget().getBuildFile(projectFilesystem);
          if (buck.getCanonicalFile().equals(ruleBuck.getCanonicalFile())) {
            owners.put(rule, Paths.get(nonExistentFile));
          }
        } catch (IOException | BuildTargetException e) {
          throw Throwables.propagate(e);
        }
      }
    }
  }

  private File findBuckFileFor(File file) {
    File dir = file;
    if (!dir.isDirectory()) {
      dir = dir.getParentFile();
    }

    File projectRoot = getProjectFilesystem().getProjectRoot();
    while (dir != null && !dir.equals(projectRoot)) {
      File buck = new File(dir, BuckConstant.BUILD_RULES_FILE_NAME);
      if (buck.exists()) {
        return buck;
      }
      dir = dir.getParentFile();
    }
    throw new RuntimeException("Failed to find BUCK file for " + file.getPath());
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
    Set<BuildRule> sortedRules = report.owners.keySet();
    for (BuildRule rule : sortedRules) {
      console.getStdOut().println(rule.getFullyQualifiedName());
    }
  }

  /**
  * Print only targets which were identified as owners in JSON.
  */
  @VisibleForTesting
  void printOwnersOnlyJsonReport(OwnersReport report) throws IOException {
    final Multimap<String, String> output = TreeMultimap.create();

    Set<BuildRule> sortedRules = report.owners.keySet();
    for (BuildRule rule : sortedRules) {
      Set<Path> files = report.owners.get(rule);
      for (Path input : files) {
        output.put(input.toString(), rule.getFullyQualifiedName());
      }
    }

    ObjectMapper mapper = new ObjectMapper();
    mapper.writeValue(console.getStdOut(), output.asMap());
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
      for (BuildRule rule : report.owners.keySet()) {
        out.println(rule.getFullyQualifiedName());
        Set<Path> files = report.owners.get(rule);
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
