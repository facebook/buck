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

package com.facebook.buck.features.go;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.shell.ShellStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.function.Supplier;

public class CGoGenerateImportStep extends ShellStep {
  @AddToRuleKey private final ImmutableList<String> cgoCommandPrefix;
  @AddToRuleKey private final GoPlatform platform;

  private final Supplier<String> packageName;
  private final Path bin;
  private final Path outputFile;

  public CGoGenerateImportStep(
      Path workingDirectory,
      ImmutableList<String> cgoCommandPrefix,
      GoPlatform platform,
      Supplier<String> packageName,
      Path bin,
      Path outputFile) {
    super(workingDirectory);
    this.cgoCommandPrefix = cgoCommandPrefix;
    this.packageName = packageName;
    this.bin = bin;
    this.outputFile = outputFile;
    this.platform = platform;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .addAll(cgoCommandPrefix)
        .add("-dynpackage", packageName.get())
        .add("-dynimport", bin.toString())
        .add("-dynout", outputFile.toString())
        .build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return ImmutableMap.<String, String>builder()
        .putAll(context.getEnvironment())
        .put("GOOS", platform.getGoOs().getEnvVarValue())
        .put("GOARCH", platform.getGoArch().getEnvVarValue())
        .put("GOARM", platform.getGoArch().getEnvVarValueForArm())
        .build();
  }

  @Override
  public String getShortName() {
    return "cgo import generator";
  }
}
