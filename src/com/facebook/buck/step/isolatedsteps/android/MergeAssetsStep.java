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

package com.facebook.buck.step.isolatedsteps.android;

import com.facebook.buck.android.resources.MergeAssetsUtils;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/** Step to construct build outputs for exo-for-resources. */
public class MergeAssetsStep extends IsolatedStep {
  private final RelPath relativePathToMergedAssets;
  private final Optional<RelPath> relativePathToBaseApk;
  private final ImmutableSet<RelPath> assetsDirectories;

  public MergeAssetsStep(
      RelPath relativePathToMergedAssets,
      Optional<RelPath> relativePathToBaseApk,
      ImmutableSet<RelPath> assetsDirectories) {
    this.relativePathToMergedAssets = relativePathToMergedAssets;
    this.relativePathToBaseApk = relativePathToBaseApk;
    this.assetsDirectories = assetsDirectories;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    MergeAssetsUtils.mergeAssets(
        ProjectFilesystemUtils.getPathForRelativePath(
            context.getRuleCellRoot(), relativePathToMergedAssets),
        relativePathToBaseApk.map(
            baseApk ->
                ProjectFilesystemUtils.getPathForRelativePath(context.getRuleCellRoot(), baseApk)),
        getAllAssets(context));
    return StepExecutionResults.SUCCESS;
  }

  private ImmutableMap<Path, Path> getAllAssets(IsolatedExecutionContext context)
      throws IOException {
    ImmutableMap.Builder<Path, Path> assets = ImmutableMap.builder();

    for (RelPath assetDirectory : assetsDirectories) {
      AbsPath absolutePath =
          ProjectFilesystemUtils.getAbsPathForRelativePath(
              context.getRuleCellRoot(), assetDirectory);

      ProjectFilesystemUtils.walkFileTree(
          context.getRuleCellRoot(),
          assetDirectory.getPath(),
          ImmutableSet.of(),
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Preconditions.checkState(
                  !Files.getFileExtension(file.toString()).equals("gz"),
                  "BUCK doesn't support adding .gz files to assets (%s).",
                  file);
              Path normalized = file.normalize();
              assets.put(absolutePath.getPath().relativize(normalized), normalized);
              return super.visitFile(file, attrs);
            }
          },
          ProjectFilesystemUtils.getEmptyIgnoreFilter());
    }

    return assets.build();
  }

  @Override
  public String getShortName() {
    return "merge_assets";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return String.format(
        "merge_assets %s %s",
        relativePathToMergedAssets,
        relativePathToBaseApk.map(RelPath::toString).orElse("no_base_apk"));
  }
}
