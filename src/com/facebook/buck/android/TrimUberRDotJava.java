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

package com.facebook.buck.android;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;

/**
 * Rule for trimming unnecessary ids from R.java files.
 */
class TrimUberRDotJava extends AbstractBuildRule {
  private final AaptPackageResources aaptPackageResources;

  TrimUberRDotJava(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      AaptPackageResources aaptPackageResources) {
    super(buildRuleParams, resolver);
    this.aaptPackageResources = aaptPackageResources;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(getPathToOutput());
    return ImmutableList.of(
        new MakeCleanDirectoryStep(getProjectFilesystem(), getPathToOutput().getParent()),
        new PerformTrimStep(),
        new ZipScrubberStep(getProjectFilesystem(), getPathToOutput())
    );
  }

  @Override
  public Path getPathToOutput() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(),
        getBuildTarget(),
        "/%s/_trimmed_r_dot_java.src.zip");
  }

  private class PerformTrimStep implements Step {
    @Override
    public StepExecutionResult execute(ExecutionContext context)
        throws IOException, InterruptedException {
      final ProjectFilesystem projectFilesystem = getProjectFilesystem();
      final Path sourceDir = aaptPackageResources.getPathToGeneratedRDotJavaSrcFiles();
      try (final CustomZipOutputStream output =
               ZipOutputStreams.newOutputStream(projectFilesystem.resolve(getPathToOutput()))) {
        if (!projectFilesystem.exists(sourceDir)) {
          // dx fails if its input contains no classes.  Rather than add empty input handling
          // to DxStep, the dex merger, and every other step of this chain, just generate a
          // stub class.  This will be stripped by ProGuard in release builds and have a minimal
          // effect on debug builds.
          output.putNextEntry(new ZipEntry("com/facebook/buck/AppWithoutResourcesStub.java"));
          output.write((
              "package com.facebook.buck_generated;\n" +
              "final class AppWithoutResourcesStub {}"
              ).getBytes());
        } else {
          projectFilesystem.walkRelativeFileTree(
              sourceDir,
              new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                  if (attrs.isDirectory()) {
                    return FileVisitResult.CONTINUE;
                  }
                  if (!attrs.isRegularFile()) {
                    throw new RuntimeException(String.format(
                        "Found unknown file type while looking for R.java: %s (%s)",
                        file,
                        attrs));
                  }
                  if (!file.getFileName().toString().endsWith(".java")) {
                    throw new RuntimeException(String.format(
                        "Found unknown file while looking for R.java: %s",
                        file));
                  }

                  output.putNextEntry(new ZipEntry(
                      MorePaths.pathWithUnixSeparators(sourceDir.relativize(file))));
                  projectFilesystem.copyToOutputStream(file, output);
                  return FileVisitResult.CONTINUE;
                }
              });
        }
      }
      return StepExecutionResult.SUCCESS;
    }

    @Override
    public String getShortName() {
      return "trim_uber_r_dot_java";
    }

    @Override
    public String getDescription(ExecutionContext context) {
      return String.format(
          "trim_uber_r_dot_java %s > %s",
          aaptPackageResources.getPathToGeneratedRDotJavaSrcFiles(),
          getPathToOutput());
    }
  }
}
