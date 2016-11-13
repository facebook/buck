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

package com.facebook.buck.go;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

public class CGoGenerateImportStep extends ShellStep {

  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> cgoCommandPrefix;
  private final GoPlatform platform;
  private final Path packageName;
  private final Path bin;
  private final Path outputFile;

  public CGoGenerateImportStep(
      Path workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> cgoCommandPrefix,
      GoPlatform platform,
      Path packageName,
      Path bin,
      Path outputFile) {
    super(workingDirectory);
    this.environment = environment;
    this.cgoCommandPrefix = cgoCommandPrefix;
    this.packageName = packageName;
    this.bin = bin;
    this.outputFile = outputFile;
    this.platform = platform;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.<String>builder()
        .addAll(cgoCommandPrefix)
        .add("-dynpackage", packageName.toString())
        .add("-dynimport", bin.toAbsolutePath().toString())
        .add("-dynout", outputFile.toAbsolutePath().toString());
    return commandBuilder.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
   return ImmutableMap.<String, String>builder()
        .putAll(environment)
        .put("GOOS", platform.getGoOs())
        .put("GOARCH", platform.getGoArch())
       .build();
  }

  @Override
  public String getShortName() {
    return "cgo import generator";
  }
}
