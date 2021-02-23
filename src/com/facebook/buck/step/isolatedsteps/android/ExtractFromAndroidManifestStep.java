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

import com.facebook.buck.android.AndroidManifestReader;
import com.facebook.buck.android.DefaultAndroidManifestReader;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import java.io.IOException;

public class ExtractFromAndroidManifestStep extends IsolatedStep {

  private final RelPath manifest;
  private final RelPath pathToRDotJavaPackageFile;

  public ExtractFromAndroidManifestStep(RelPath manifest, RelPath pathToRDotJavaPackageFile) {
    this.manifest = manifest;
    this.pathToRDotJavaPackageFile = pathToRDotJavaPackageFile;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException {
    AndroidManifestReader androidManifestReader =
        DefaultAndroidManifestReader.forPath(
            ProjectFilesystemUtils.getPathForRelativePath(context.getRuleCellRoot(), manifest));

    String rDotJavaPackageFromAndroidManifest = androidManifestReader.getPackage();
    ProjectFilesystemUtils.writeContentsToPath(
        context.getRuleCellRoot(),
        rDotJavaPackageFromAndroidManifest,
        pathToRDotJavaPackageFile.getPath());
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "extract_from_android_manifest";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return String.format(
        "extract_from_android_manifest %s %s", manifest, pathToRDotJavaPackageFile);
  }
}
