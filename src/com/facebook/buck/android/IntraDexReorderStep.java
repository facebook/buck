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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.zip.UnzipStep;
import com.facebook.buck.zip.ZipStep;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * Runs a user supplied reordering tool on all dexes.
 * Deals with both jar-ed and non-jar-ed dexes. Jar-ed
 * dexes get unzipped to a temp directory first and re-zipped
 * to the output location after the reorder tool is run.
 */
public class IntraDexReorderStep implements Step {

  private final Path workingDirectory;
  private final Path reorderTool;
  private final Path reorderDataFile;
  private final Path inputPrimaryDexPath;
  private final Path outputPrimaryDexPath;
  private final Optional<Supplier<Multimap<Path, Path>>> secondaryDexMap;
  private final BuildTarget buildTarget;
  private final String inputSubDir;
  private final String outputSubDir;

  IntraDexReorderStep(
      Path workingDirectory,
      Optional<SourcePath> reorderTool,
      Optional<SourcePath> reorderDataFile,
      SourcePathResolver sourcePathResolver,
      BuildTarget buildTarget,
      Path inputPrimaryDexPath,
      Path outputPrimaryDexPath,
      final Optional<Supplier<Multimap<Path, Path>>> secondaryDexMap,
      String inputSubDir,
      String outputSubDir) {
    this.workingDirectory = workingDirectory;
    this.reorderTool = reorderTool.transform(sourcePathResolver.getPathFunction()).get();
    this.reorderDataFile = reorderDataFile.transform(sourcePathResolver.getPathFunction()).get();
    this.inputPrimaryDexPath = inputPrimaryDexPath;
    this.outputPrimaryDexPath = outputPrimaryDexPath;
    this.secondaryDexMap = secondaryDexMap;
    this.buildTarget = buildTarget;
    this.inputSubDir = inputSubDir;
    this.outputSubDir = outputSubDir;
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    try {
      DefaultStepRunner stepRunner = new DefaultStepRunner(context);
      List<Step> dxSteps = generateReorderCommands();
      for (Step step : dxSteps) {
        stepRunner.runStepForBuildTarget(step, Optional.of(buildTarget));
      }
    } catch (StepFailedException | IOException | InterruptedException e) {
      context.logError(e, "There was an error in intra dex reorder step.");
      return 1;
    }
    return 0;
  }

  private ImmutableList<Step> generateReorderCommands()
    throws StepFailedException, IOException, InterruptedException {
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

  private int reorderEntry(Path inputPath, boolean isPrimaryDex, ImmutableList.Builder<Step> steps)
    throws IOException, InterruptedException {

    if (!isPrimaryDex) {
      String tmpname = "dex-tmp-" + inputPath.getFileName().toString() + "-%s";
      Path temp = BuildTargets.getScratchPath(buildTarget, tmpname);
      // Create tmp directory if necessary
      steps.add(new MakeCleanDirectoryStep(temp));
      // un-zip
      steps.add(new UnzipStep(inputPath, temp));
      // run reorder tool
      steps.add(
          new DefaultShellStep(
              workingDirectory,
              ImmutableList.of(
                  reorderTool.toString(),
                  reorderDataFile.toString(),
                  temp.resolve("classes.dex").toString())));
      Path outputPath = Paths.get(inputPath.toString().replace(inputSubDir, outputSubDir));
      // re-zip
      steps.add(new ZipStep(
            outputPath,
            /* paths */ ImmutableSet.<Path>of(),
            /* junkPaths */ false,
            ZipStep.MAX_COMPRESSION_LEVEL,
            temp
            )
          );
    } else {
      // copy dex
      // apply reorder directly on dex
      steps.add(CopyStep.forFile(inputPrimaryDexPath, outputPrimaryDexPath));
      steps.add(
          new DefaultShellStep(
              workingDirectory,
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
