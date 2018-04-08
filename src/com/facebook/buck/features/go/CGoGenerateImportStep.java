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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;

public class CGoGenerateImportStep extends ShellStep {
  @AddToRuleKey private final ImmutableList<String> cgoCommandPrefix;
  @AddToRuleKey private final GoPlatform platform;

  private final Path packageName;
  private final Path bin;
  private final Path outputFile;

  public CGoGenerateImportStep(
      BuildTarget buildTarget,
      Path workingDirectory,
      ImmutableList<String> cgoCommandPrefix,
      GoPlatform platform,
      Path packageName,
      Path bin,
      Path outputFile) {
    super(Optional.of(buildTarget), workingDirectory);
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
        .add("-dynpackage", packageName.getFileName().toString())
        .add("-dynimport", bin.toString())
        .add("-dynout", outputFile.toString())
        .build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return ImmutableMap.<String, String>builder()
        .putAll(context.getEnvironment())
        .put("GOOS", platform.getGoOs())
        .put("GOARCH", platform.getGoArch())
        .build();
  }

  @Override
  public String getShortName() {
    return "cgo import generator";
  }
}
