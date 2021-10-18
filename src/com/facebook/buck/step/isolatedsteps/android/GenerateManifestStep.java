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

import com.android.common.utils.ILogger;
import com.facebook.buck.android.BuckEventAndroidLogger;
import com.facebook.buck.android.manifest.GenerateManifest;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;

public class GenerateManifestStep extends IsolatedStep {

  private final RelPath skeletonManifestPath;
  private final ImmutableSet<RelPath> libraryManifestPaths;
  private final RelPath outManifestPath;
  private final RelPath mergeReportPath;
  private final String moduleName;
  private final ImmutableMap<String, String> placeholders;

  public GenerateManifestStep(
      RelPath skeletonManifestPath,
      String moduleName,
      ImmutableSet<RelPath> libraryManifestPaths,
      RelPath outManifestPath,
      RelPath mergeReportPath,
      ImmutableMap<String, String> placeholders) {
    this.skeletonManifestPath = skeletonManifestPath;
    this.moduleName = moduleName;
    this.libraryManifestPaths = ImmutableSet.copyOf(libraryManifestPaths);
    this.outManifestPath = outManifestPath;
    this.mergeReportPath = mergeReportPath;
    this.placeholders = placeholders;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    AbsPath root = context.getRuleCellRoot();
    String xmlText =
        GenerateManifest.generateXml(
            ProjectFilesystemUtils.getPathForRelativePath(root, skeletonManifestPath),
            moduleName,
            libraryManifestPaths.stream()
                .map(relPath -> ProjectFilesystemUtils.getPathForRelativePath(root, relPath))
                .collect(ImmutableSet.toImmutableSet()),
            placeholders,
            ProjectFilesystemUtils.getPathForRelativePath(root, outManifestPath),
            ProjectFilesystemUtils.getPathForRelativePath(root, mergeReportPath),
            new ManifestMergerLogger(context.getIsolatedEventBus()));

    ProjectFilesystemUtils.writeContentsToPath(
        context.getRuleCellRoot(), xmlText, outManifestPath.getPath());

    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return String.format("generate-manifest %s", skeletonManifestPath);
  }

  @Override
  public String getShortName() {
    return "generate_manifest";
  }

  private static class ManifestMergerLogger extends BuckEventAndroidLogger implements ILogger {
    public ManifestMergerLogger(IsolatedEventBus eventBus) {
      super(eventBus);
    }

    @Override
    public void info(String msgFormat, Object... args) {
      // suppress
    }

    @Override
    public void verbose(String msgFormat, Object... args) {
      // suppress
    }
  }
}
