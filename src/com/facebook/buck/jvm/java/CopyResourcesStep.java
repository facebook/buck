/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class CopyResourcesStep implements Step {

  private final ProjectFilesystem filesystem;
  private final BuildContext buildContext;
  private final BuildTarget target;
  private final ResourcesParameters parameters;
  private final Path outputDirectory;

  public CopyResourcesStep(
      ProjectFilesystem filesystem,
      BuildContext buildContext,
      BuildTarget target,
      ResourcesParameters parameters,
      Path outputDirectory) {
    this.filesystem = filesystem;
    this.buildContext = buildContext;
    this.target = target;
    this.parameters = parameters;
    this.outputDirectory = outputDirectory;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
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
    ImmutableMap<AbsPath, Path> resourcesMap =
        getResourcesMap(buildContext, filesystem, outputDirectory, parameters, target);
    ImmutableList.Builder<Step> steps =
        ImmutableList.builderWithExpectedSize(resourcesMap.size() * 2);
    resourcesMap.forEach(
        (resourceAbsPath, target) -> {
          steps.add(
              MkdirStep.of(
                  BuildCellRelativePath.fromCellRelativePath(
                      buildContext.getBuildCellRootPath(), filesystem, target.getParent())));
          steps.add(SymlinkFileStep.of(filesystem, resourceAbsPath.getPath(), target));
        });

    return steps.build();
  }

  /**
   * Returns discovered resources as a map, where the key is the an actual absolute path to the resource
   * and a value is a desired path to it.
   */
  public static ImmutableMap<AbsPath, Path> getResourcesMap(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      Path outputDirectory,
      ResourcesParameters parameters,
      BuildTarget buildTarget) {

    if (parameters.getResources().isEmpty()) {
      return ImmutableMap.of();
    }

    JavaPackageFinder javaPackageFinder =
        parameters
            .getResourcesRoot()
            .map(Paths::get)
            .map(
                root ->
                    (JavaPackageFinder)
                        new ResourcesRootPackageFinder(root, buildContext.getJavaPackageFinder()))
            .orElse(buildContext.getJavaPackageFinder());

    String targetPackageDir = javaPackageFinder.findJavaPackage(buildTarget);
    ImmutableMap.Builder<AbsPath, Path> resourcesMapBuilder = ImmutableMap.builder();
    for (Map.Entry<String, SourcePath> entry : parameters.getResources().entrySet()) {
      String resource = entry.getKey();
      SourcePath rawResource = entry.getValue();
      AbsPath resourceAbsPath = buildContext.getSourcePathResolver().getAbsolutePath(rawResource);
      RelPath relativePathToResource = filesystem.relativize(resourceAbsPath);

      Path javaPackageAsPath =
          javaPackageFinder.findJavaPackageFolder(
              outputDirectory.getFileSystem().getPath(resource));

      Path relativeSymlinkPath;
      if ("".equals(javaPackageAsPath.toString())) {
        // In this case, the project root is acting as the default package, so the resource path
        // works fine.
        relativeSymlinkPath = relativePathToResource.getPath().getFileName();
      } else {
        int lastIndex =
            resource.lastIndexOf(
                PathFormatter.pathWithUnixSeparatorsAndTrailingSlash(javaPackageAsPath));
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
                              .getCellUnsafeRelPath(rawResource)
                              .getFileName()));
        } else {
          relativeSymlinkPath =
              outputDirectory.getFileSystem().getPath(resource.substring(lastIndex));
        }
      }
      Path target = outputDirectory.resolve(relativeSymlinkPath);

      resourcesMapBuilder.put(resourceAbsPath, target);
    }
    return resourcesMapBuilder.build();
  }

  @Override
  public String getShortName() {
    return "copy_resources";
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return String.format("%s of %s", getShortName(), target);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("target", target)
        .add("parameters", parameters)
        .add("outputDirectory", outputDirectory)
        .toString();
  }
}
