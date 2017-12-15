/*
 * Copyright 2014-present Facebook, Inc.
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
package com.facebook.buck.jvm.java;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasOutputName;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class CopyResourcesStep implements Step {

  private final ProjectFilesystem filesystem;
  private final BuildContext buildContext;
  private final SourcePathRuleFinder ruleFinder;
  private final BuildTarget target;
  private final ResourcesParameters parameters;
  private final Path outputDirectory;

  public CopyResourcesStep(
      ProjectFilesystem filesystem,
      BuildContext buildContext,
      SourcePathRuleFinder ruleFinder,
      BuildTarget target,
      ResourcesParameters parameters,
      Path outputDirectory) {
    this.filesystem = filesystem;
    this.buildContext = buildContext;
    this.ruleFinder = ruleFinder;
    this.target = target;
    this.parameters = parameters;
    this.outputDirectory = outputDirectory;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    for (Step step : buildSteps()) {
      StepExecutionResult result = step.execute(context);
      if (!result.isSuccess()) {
        return result;
      }
    }
    return StepExecutionResults.SUCCESS;
  }

  @VisibleForTesting
  ImmutableList<Step> buildSteps() {
    ImmutableList.Builder<Step> allSteps = ImmutableList.builder();

    if (parameters.getResources().isEmpty()) {
      return allSteps.build();
    }

    JavaPackageFinder javaPackageFinder =
        parameters
            .getResourcesRoot()
            .map(
                root ->
                    (JavaPackageFinder)
                        new ResourcesRootPackageFinder(root, buildContext.getJavaPackageFinder()))
            .orElse(buildContext.getJavaPackageFinder());

    String targetPackageDir = javaPackageFinder.findJavaPackage(target);

    for (SourcePath rawResource : parameters.getResources()) {
      // If the path to the file defining this rule were:
      // "first-party/orca/lib-http/tests/com/facebook/orca/BUCK"
      //
      // And the value of resource were:
      // "first-party/orca/lib-http/tests/com/facebook/orca/protocol/base/batch_exception1.txt"
      //
      // Assuming that `src_roots = tests` were in the [java] section of the .buckconfig file,
      // then javaPackageAsPath would be:
      // "com/facebook/orca/protocol/base/"
      //
      // And the path that we would want to copy to the classes directory would be:
      // "com/facebook/orca/protocol/base/batch_exception1.txt"
      //
      // Therefore, some path-wrangling is required to produce the correct string.

      Optional<BuildRule> underlyingRule = ruleFinder.getRule(rawResource);
      Path relativePathToResource =
          buildContext.getSourcePathResolver().getRelativePath(rawResource);

      String resource;

      if (underlyingRule.isPresent()) {
        BuildTarget underlyingTarget = underlyingRule.get().getBuildTarget();
        if (underlyingRule.get() instanceof HasOutputName) {
          resource =
              MorePaths.pathWithUnixSeparators(
                  underlyingTarget
                      .getBasePath()
                      .resolve(((HasOutputName) underlyingRule.get()).getOutputName()));
        } else {
          Path genOutputParent =
              BuildTargets.getGenPath(filesystem, underlyingTarget, "%s").getParent();
          Path scratchOutputParent =
              BuildTargets.getScratchPath(filesystem, underlyingTarget, "%s").getParent();
          Optional<Path> outputPath =
              MorePaths.stripPrefix(relativePathToResource, genOutputParent)
                  .map(Optional::of)
                  .orElse(MorePaths.stripPrefix(relativePathToResource, scratchOutputParent));
          Preconditions.checkState(
              outputPath.isPresent(),
              "%s is used as a resource but does not output to a default output directory",
              underlyingTarget.getFullyQualifiedName());
          resource =
              MorePaths.pathWithUnixSeparators(
                  underlyingTarget.getBasePath().resolve(outputPath.get()));
        }
      } else {
        resource = MorePaths.pathWithUnixSeparators(relativePathToResource);
      }

      Path javaPackageAsPath =
          javaPackageFinder.findJavaPackageFolder(
              outputDirectory.getFileSystem().getPath(resource));

      Path relativeSymlinkPath;
      if ("".equals(javaPackageAsPath.toString())) {
        // In this case, the project root is acting as the default package, so the resource path
        // works fine.
        relativeSymlinkPath = relativePathToResource.getFileName();
      } else {
        int lastIndex =
            resource.lastIndexOf(
                MorePaths.pathWithUnixSeparatorsAndTrailingSlash(javaPackageAsPath));
        if (lastIndex < 0) {
          Preconditions.checkState(
              rawResource instanceof BuildTargetSourcePath,
              "If resource path %s does not contain %s, then it must be a BuildTargetSourcePath.",
              relativePathToResource,
              javaPackageAsPath);
          // Handle the case where we depend on the output of another BuildRule. In that case, just
          // grab the output and put in the same package as this target would be in.
          relativeSymlinkPath =
              outputDirectory
                  .getFileSystem()
                  .getPath(
                      String.format(
                          "%s%s%s",
                          targetPackageDir,
                          targetPackageDir.isEmpty() ? "" : "/",
                          buildContext
                              .getSourcePathResolver()
                              .getRelativePath(rawResource)
                              .getFileName()));
        } else {
          relativeSymlinkPath =
              outputDirectory.getFileSystem().getPath(resource.substring(lastIndex));
        }
      }
      Path target = outputDirectory.resolve(relativeSymlinkPath);
      allSteps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  buildContext.getBuildCellRootPath(), filesystem, target.getParent())));
      allSteps.add(
          SymlinkFileStep.builder()
              .setFilesystem(filesystem)
              .setExistingFile(buildContext.getSourcePathResolver().getAbsolutePath(rawResource))
              .setDesiredLink(target)
              .build());
    }
    return allSteps.build();
  }

  @Override
  public String getShortName() {
    return "copy_resources";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("%s of %s", getShortName(), target);
  }
}
