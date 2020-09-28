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

package com.facebook.buck.apple;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class AppleBundleComponentCopySpec {
  private final AbsPath sourcePath;
  private final RelPath destinationPathRelativeToBundleRoot;
  private final boolean ignoreIfMissing;

  public AppleBundleComponentCopySpec(
      AbsPath sourcePath, RelPath destinationPathRelativeToBundleRoot, boolean ignoreIfMissing) {
    this.sourcePath = sourcePath;
    this.destinationPathRelativeToBundleRoot = destinationPathRelativeToBundleRoot;
    this.ignoreIfMissing = ignoreIfMissing;
  }

  public AppleBundleComponentCopySpec(
      SourcePathWithAppleBundleDestination pathWithDestination,
      SourcePathResolverAdapter sourcePathResolver,
      AppleBundleDestinations destinations) {
    this.sourcePath = sourcePathResolver.getAbsolutePath(pathWithDestination.getSourcePath());
    this.destinationPathRelativeToBundleRoot =
        destinationPathRelativeToBundleRoot(
            sourcePathResolver,
            destinations,
            pathWithDestination.getSourcePath(),
            pathWithDestination.getDestination(),
            Optional.empty());
    this.ignoreIfMissing = false;
  }

  public AppleBundleComponentCopySpec(
      FileAppleBundlePart bundlePart,
      SourcePathResolverAdapter sourcePathResolver,
      AppleBundleDestinations destinations) {
    this.sourcePath = sourcePathResolver.getAbsolutePath(bundlePart.getSourcePath());
    this.destinationPathRelativeToBundleRoot =
        destinationPathRelativeToBundleRoot(
            sourcePathResolver,
            destinations,
            bundlePart.getSourcePath(),
            bundlePart.getDestination(),
            bundlePart.getNewName());
    this.ignoreIfMissing = bundlePart.getIgnoreIfMissing();
  }

  public AppleBundleComponentCopySpec(
      DirectoryAppleBundlePart bundlePart,
      SourcePathResolverAdapter sourcePathResolver,
      AppleBundleDestinations destinations) {
    this.sourcePath = sourcePathResolver.getAbsolutePath(bundlePart.getSourcePath());
    this.destinationPathRelativeToBundleRoot =
        destinationPathRelativeToBundleRoot(
            sourcePathResolver,
            destinations,
            bundlePart.getSourcePath(),
            bundlePart.getDestination(),
            Optional.empty());
    this.ignoreIfMissing = false;
  }

  public AbsPath getSourcePath() {
    return sourcePath;
  }

  public RelPath getDestinationPathRelativeToBundleRoot() {
    return destinationPathRelativeToBundleRoot;
  }

  private static RelPath destinationPathRelativeToBundleRoot(
      SourcePathResolverAdapter sourcePathResolver,
      AppleBundleDestinations destinations,
      SourcePath sourcePath,
      AppleBundleDestination destination,
      Optional<String> maybeNewName) {
    RelPath destinationDirectoryPath = RelPath.of(destination.getPath(destinations));
    AbsPath resolvedSourcePath = sourcePathResolver.getAbsolutePath(sourcePath);
    String destinationFileName = maybeNewName.orElse(resolvedSourcePath.getFileName().toString());
    return destinationDirectoryPath.resolveRel(destinationFileName);
  }

  public Step createCopyStep(ProjectFilesystem projectFilesystem, Path bundleRootPath) {
    return new AbstractExecutionStep("copy-apple-bundle-component") {
      @Override
      public StepExecutionResult execute(StepExecutionContext stepContext) throws IOException {
        performCopy(projectFilesystem, bundleRootPath);
        return StepExecutionResults.SUCCESS;
      }
    };
  }

  /** Perform actual copy according to the spec. */
  public void performCopy(ProjectFilesystem projectFilesystem, Path bundleRootPath)
      throws IOException {
    if (ignoreIfMissing && !projectFilesystem.exists(sourcePath.getPath())) {
      return;
    }
    boolean isDirectory = projectFilesystem.isDirectory(sourcePath);
    Path toPath = bundleRootPath.resolve(destinationPathRelativeToBundleRoot.getPath());
    projectFilesystem.copy(
        sourcePath.getPath(),
        isDirectory ? toPath.getParent() : toPath,
        isDirectory ? CopySourceMode.DIRECTORY_AND_CONTENTS : CopySourceMode.FILE);
  }
}
