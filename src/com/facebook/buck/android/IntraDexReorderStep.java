/*
 * Copyright 2015-present Facebook, Inc.
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
package com.facebook.buck.android;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.unarchive.UnzipStep;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.facebook.buck.zip.ZipStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Runs a user supplied reordering tool on all dexes. Deals with both jar-ed and non-jar-ed dexes.
 * Jar-ed dexes get unzipped to a temp directory first and re-zipped to the output location after
 * the reorder tool is run.
 */
public class IntraDexReorderStep implements Step {

  private final BuildTarget target;
  private final BuildContext context;
  private final ProjectFilesystem filesystem;
  private final Path reorderTool;
  private final Path reorderDataFile;
  private final Path inputPrimaryDexPath;
  private final Path outputPrimaryDexPath;
  private final Optional<Supplier<Multimap<Path, Path>>> secondaryDexMap;
  private final BuildTarget buildTarget;
  private final String inputSubDir;
  private final String outputSubDir;

  IntraDexReorderStep(
      BuildTarget target,
      BuildContext context,
      ProjectFilesystem filesystem,
      Path reorderTool,
      Path reorderDataFile,
      BuildTarget buildTarget,
      Path inputPrimaryDexPath,
      Path outputPrimaryDexPath,
      Optional<Supplier<Multimap<Path, Path>>> secondaryDexMap,
      String inputSubDir,
      String outputSubDir) {
    this.target = target;
    this.context = context;
    this.filesystem = filesystem;
    this.reorderTool = reorderTool;
    this.reorderDataFile = reorderDataFile;
    this.inputPrimaryDexPath = inputPrimaryDexPath;
    this.outputPrimaryDexPath = outputPrimaryDexPath;
    this.secondaryDexMap = secondaryDexMap;
    this.buildTarget = buildTarget;
    this.inputSubDir = inputSubDir;
    this.outputSubDir = outputSubDir;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    try {
      DefaultStepRunner stepRunner = new DefaultStepRunner();
      List<Step> dxSteps = generateReorderCommands();
      for (Step step : dxSteps) {
        stepRunner.runStepForBuildTarget(context, step, Optional.of(buildTarget));
      }
    } catch (StepFailedException e) {
      context.logError(e, "There was an error in intra dex reorder step.");
      return StepExecutionResults.ERROR;
    }
    return StepExecutionResults.SUCCESS;
  }

  private ImmutableList<Step> generateReorderCommands() {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    reorderEntry(inputPrimaryDexPath, true, steps);
    if (secondaryDexMap.isPresent()) {
      Set<Path> secondaryDexSet = secondaryDexMap.get().get().keySet();
      for (Path secondaryDexPath : secondaryDexSet) {
        reorderEntry(secondaryDexPath, false, steps);
      }
    }
    return steps.build();
  }

  private int reorderEntry(
      Path inputPath, boolean isPrimaryDex, ImmutableList.Builder<Step> steps) {

    if (!isPrimaryDex) {
      String tmpname = "dex-tmp-" + inputPath.getFileName() + "-%s";
      Path temp = BuildTargets.getScratchPath(filesystem, buildTarget, tmpname);
      // Create tmp directory if necessary
      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), filesystem, temp)));
      // un-zip
      steps.add(new UnzipStep(filesystem, inputPath, temp, Optional.empty()));
      // run reorder tool
      steps.add(
          new DefaultShellStep(
              target,
              filesystem.getRootPath(),
              ImmutableList.of(
                  reorderTool.toString(),
                  reorderDataFile.toString(),
                  temp.resolve("classes.dex").toString())));
      Path outputPath = Paths.get(inputPath.toString().replace(inputSubDir, outputSubDir));
      // re-zip
      steps.add(
          new ZipStep(
              filesystem,
              outputPath,
              /* paths */ ImmutableSet.of(),
              /* junkPaths */ false,
              ZipCompressionLevel.MAX,
              temp));
    } else {
      // copy dex
      // apply reorder directly on dex
      steps.add(CopyStep.forFile(filesystem, inputPrimaryDexPath, outputPrimaryDexPath));
      steps.add(
          new DefaultShellStep(
              target,
              filesystem.getRootPath(),
              ImmutableList.of(
                  reorderTool.toString(),
                  reorderDataFile.toString(),
                  outputPrimaryDexPath.toString())));
    }
    return 0;
  }

  @Override
  public String getShortName() {
    return "intradex reorder";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("%s --- intradex reorder using %s", buildTarget, reorderTool);
  }
}
