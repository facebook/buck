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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import java.io.IOException;
import java.nio.file.Path;

public class ExtractFromAndroidManifestStep extends AbstractExecutionStep {

  private final Path manifest;
  private final ProjectFilesystem filesystem;
  private final Path pathToRDotJavaPackageFile;

  public ExtractFromAndroidManifestStep(
      Path manifest, ProjectFilesystem filesystem, Path pathToRDotJavaPackageFile) {
    super("extract_from_android_manifest");
    this.manifest = manifest;
    this.filesystem = filesystem;
    this.pathToRDotJavaPackageFile = pathToRDotJavaPackageFile;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    AndroidManifestReader androidManifestReader =
        DefaultAndroidManifestReader.forPath(filesystem.resolve(manifest));

    String rDotJavaPackageFromAndroidManifest = androidManifestReader.getPackage();
    filesystem.writeContentsToPath(rDotJavaPackageFromAndroidManifest, pathToRDotJavaPackageFile);
    return StepExecutionResults.SUCCESS;
  }
}
