/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.android.bundle;

import com.android.bundle.Files.NativeLibraries;
import com.android.bundle.Files.TargetedNativeDirectory;
import com.android.bundle.Targeting;
import com.android.bundle.Targeting.NativeDirectoryTargeting;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/** This step generates a Native.pb with java class files compiled from files.proto */
public class GenerateNativeStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path output;
  private final ImmutableSet<Path> module;

  public GenerateNativeStep(ProjectFilesystem filesystem, Path output, ImmutableSet<Path> module) {
    this.filesystem = filesystem;
    this.output = output;
    this.module = module;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    Path path = filesystem.resolve(output);
    try (OutputStream outputFile = filesystem.newFileOutputStream(path)) {
      createNativeLibraries().writeTo(outputFile);
    }
    return StepExecutionResults.SUCCESS;
  }

  private NativeLibraries createNativeLibraries() {
    NativeLibraries.Builder builder = NativeLibraries.newBuilder();
    for (Path nativeLib : module) {
      File[] filesInLib = filesystem.getPathForRelativePath(nativeLib).toFile().listFiles();
      if (filesInLib == null) {
        continue;
      }
      for (File fileInLib : filesInLib) {
        File[] files = fileInLib.listFiles();
        if (files == null || files.length < 1) {
          continue;
        }
        Path filePath = filesystem.getPathForRelativePath(nativeLib).relativize(fileInLib.toPath());
        builder.addDirectory(
            TargetedNativeDirectory.newBuilder()
                .setPath(Paths.get("lib").resolve(filePath).toString())
                .setTargeting(
                    NativeDirectoryTargeting.newBuilder()
                        .setAbi(
                            Targeting.Abi.newBuilder().setAliasValue(getAbi(fileInLib.toPath())))));
      }
    }
    return builder.build();
  }

  private static int getAbi(Path path) {
    if (path.getNameCount() < 2) {
      return -1;
    }
    String abiName = path.getName(path.getNameCount() - 1).toString();

    return GetAbiByName.getAbi(abiName);
  }

  @Override
  public String getShortName() {
    return "generate native.pb";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "";
  }
}
