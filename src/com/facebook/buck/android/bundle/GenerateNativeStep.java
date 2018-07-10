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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/** This step generates a Native.pb with java class files compiled from files.proto */
public class GenerateNativeStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path output;
  private final ImmutableSet<Path> module;
  private static final String LIB_DIRECTORY = "lib/";
  private static final ImmutableMap<String, Integer> ABI_BY_NAME =
      new ImmutableMap.Builder<String, Integer>()
          .put("UNSPECIFIED_CPU_ARCHITECTURE", 0)
          .put("ARMEABI", 1)
          .put("ARMEABI_V7A", 2)
          .put("ARM64_V8A", 3)
          .put("X86", 4)
          .put("X86_64", 5)
          .put("MIPS", 6)
          .put("MIPS64", 7)
          .build();

  public GenerateNativeStep(ProjectFilesystem filesystem, Path output, ImmutableSet<Path> module) {
    this.filesystem = filesystem;
    this.output = output;
    this.module = module;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    Path path = filesystem.resolve(output);
    try (OutputStream outputFile = filesystem.newFileOutputStream(path)) {
      createNativeLibraries().writeTo(outputFile);
    }
    return StepExecutionResults.SUCCESS;
  }

  private NativeLibraries createNativeLibraries() {
    NativeLibraries.Builder builder = NativeLibraries.newBuilder();
    for (Path nativeLib : module) {
      builder.addDirectory(
          TargetedNativeDirectory.newBuilder()
              .setPath(nativeLib.toString())
              .setTargeting(
                  NativeDirectoryTargeting.newBuilder()
                      .setAbi(
                          Targeting.Abi.newBuilder().setAliasValue(getAbi(nativeLib.toString())))));
    }
    return builder.build();
  }

  private int getAbi(String path) {
    if (path.length() <= LIB_DIRECTORY.length()) {
      return -1;
    }
    String abiName = path.substring(LIB_DIRECTORY.length());

    if (ABI_BY_NAME.containsKey(abiName.toUpperCase())) {
      return ABI_BY_NAME.get(abiName.toUpperCase());
    } else {
      return -1;
    }
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
