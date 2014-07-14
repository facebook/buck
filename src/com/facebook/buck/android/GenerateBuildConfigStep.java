/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class GenerateBuildConfigStep implements Step {

  private final String javaPackage;
  private final boolean useConstantExpressions;
  private final ImmutableMap<String, Object> constants;
  private final Path outBuildConfigPath;

  public GenerateBuildConfigStep(
      String javaPackage,
      boolean useConstantExpressions,
      Map<String, Object> constants,
      Path outBuildConfigPath) {
    this.javaPackage = Preconditions.checkNotNull(javaPackage);
    this.useConstantExpressions = useConstantExpressions;
    this.constants = ImmutableMap.copyOf(constants);
    this.outBuildConfigPath = Preconditions.checkNotNull(outBuildConfigPath);
    if (outBuildConfigPath.getNameCount() == 0) {
      throw new HumanReadableException("Output BuildConfig.java filepath is missing");
    }
  }

  @Override
  public int execute(ExecutionContext context) {
    String java = BuildConfigs.generateBuildConfigDotJava(
        javaPackage,
        useConstantExpressions,
        constants);
    ProjectFilesystem filesystem = context.getProjectFilesystem();
    try {
      filesystem.writeContentsToPath(java, outBuildConfigPath);
    } catch (IOException e) {
      context.logError(e, "Error writing BuildConfig.java: %s", outBuildConfigPath);
      return 1;
    }

    return 0;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("generate_build_config %s", javaPackage);
  }

  @Override
  public String getShortName() {
    return "generate_build_config";
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof GenerateBuildConfigStep)) {
      return false;
    }

    GenerateBuildConfigStep that = (GenerateBuildConfigStep) obj;
    return Objects.equal(this.javaPackage, that.javaPackage) &&
        this.useConstantExpressions == that.useConstantExpressions &&
        Objects.equal(this.constants, that.constants) &&
        Objects.equal(this.outBuildConfigPath, that.outBuildConfigPath);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        javaPackage,
        useConstantExpressions,
        constants,
        outBuildConfigPath);
  }
}
