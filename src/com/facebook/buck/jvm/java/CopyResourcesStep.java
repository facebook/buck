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
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.SymlinkIsolatedStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/** Copies (by creating symlinks) resources from existing paths to desired paths. */
public class CopyResourcesStep {

  private CopyResourcesStep() {}

  /** Copies (by creating symlinks) resources from existing paths to desired paths. */
  public static ImmutableList<IsolatedStep> of(
      ProjectFilesystem filesystem,
      BuildContext buildContext,
      BuildTarget target,
      ResourcesParameters parameters,
      Path outputDirectory) {
    return toIsolatedSteps(
        getResourcesMap(buildContext, filesystem, outputDirectory, parameters, target));
  }

  private static ImmutableList<IsolatedStep> toIsolatedSteps(
      ImmutableMap<RelPath, RelPath> resources) {
    ImmutableList.Builder<IsolatedStep> steps =
        ImmutableList.builderWithExpectedSize(resources.size() * 2);
    for (Map.Entry<RelPath, RelPath> entry : resources.entrySet()) {
      RelPath existingPath = entry.getKey();
      RelPath linkPath = entry.getValue();

      steps.add(MkdirIsolatedStep.of(linkPath.getParent()));
      steps.add(SymlinkIsolatedStep.of(existingPath, linkPath));
    }
    return steps.build();
  }

  /**
   * Returns discovered resources as a map, where the key is the an actual path to the resource and
   * a value is a desired path to it.
   */
  private static ImmutableMap<RelPath, RelPath> getResourcesMap(
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
    ImmutableMap.Builder<RelPath, RelPath> resourcesMapBuilder = ImmutableMap.builder();
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

      resourcesMapBuilder.put(
          filesystem.relativize(resourceAbsPath),
          filesystem.relativize(filesystem.resolve(target)));
    }
    return resourcesMapBuilder.build();
  }
}
