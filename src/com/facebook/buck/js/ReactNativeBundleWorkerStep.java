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

package com.facebook.buck.js;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.shell.WorkerJobParams;
import com.facebook.buck.shell.WorkerShellStep;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

public class ReactNativeBundleWorkerStep extends WorkerShellStep {

  public ReactNativeBundleWorkerStep(
      ProjectFilesystem filesystem,
      Path tmpDir,
      ImmutableList<String> jsPackagerCommand,
      Optional<String> additionalPackagerFlags,
      ReactNativePlatform platform,
      boolean isUnbundle,
      Path entryFile,
      boolean isDevMode,
      Path outputFile,
      Path resourcePath,
      Path sourceMapFile) {
    super(
        filesystem,
        Optional.of(
            WorkerJobParams.of(
                filesystem.resolve(tmpDir),
                jsPackagerCommand,
                String.format(
                    "--platform %s%s",
                    platform.toString(),
                    additionalPackagerFlags.isPresent() ? " " + additionalPackagerFlags.get() : ""),
                ImmutableMap.<String, String>of(),
                String.format(
                    "--command %s --entry-file %s --platform %s --dev %s --bundle-output %s " +
                        "--assets-dest %s --sourcemap-output %s",
                    isUnbundle ? "unbundle" : "bundle",
                    entryFile.toString(),
                    platform.toString(),
                    isDevMode ? "true" : "false",
                    outputFile.toString(),
                    resourcePath.toString(),
                    sourceMapFile.toString()))),
        Optional.<WorkerJobParams>absent(),
        Optional.<WorkerJobParams>absent());
  }

  @Override
  public String getShortName() {
    return "react-native-bundle-worker";
  }
}
