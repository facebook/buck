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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Objects;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

public class GenerateBuildConfigStep implements Step {

  private final ProjectFilesystem filesystem;
  private final UnflavoredBuildTarget source;
  private final String javaPackage;
  private final boolean useConstantExpressions;
  private final Supplier<BuildConfigFields> fields;
  private final Path outBuildConfigPath;

  public GenerateBuildConfigStep(
      ProjectFilesystem filesystem,
      UnflavoredBuildTarget source,
      String javaPackage,
      boolean useConstantExpressions,
      Supplier<BuildConfigFields> fields,
      Path outBuildConfigPath) {
    this.filesystem = filesystem;
    this.source = source;
    this.javaPackage = javaPackage;
    this.useConstantExpressions = useConstantExpressions;
    this.fields = fields;
    this.outBuildConfigPath = outBuildConfigPath;
    if (outBuildConfigPath.getNameCount() == 0) {
      throw new HumanReadableException("Output BuildConfig.java filepath is missing");
    }
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    String java =
        BuildConfigs.generateBuildConfigDotJava(
            source, javaPackage, useConstantExpressions, fields.get());
    filesystem.writeContentsToPath(java, outBuildConfigPath);

    return StepExecutionResults.SUCCESS;
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
    return Objects.equal(this.javaPackage, that.javaPackage)
        && this.useConstantExpressions == that.useConstantExpressions
        && Objects.equal(this.fields, that.fields)
        && Objects.equal(this.outBuildConfigPath, that.outBuildConfigPath);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(javaPackage, useConstantExpressions, fields, outBuildConfigPath);
  }
}
