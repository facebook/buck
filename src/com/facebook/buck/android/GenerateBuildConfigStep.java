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

package com.facebook.buck.android;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Objects;
import java.io.IOException;
import java.util.function.Supplier;

public class GenerateBuildConfigStep implements Step {

  private final String source;
  private final String javaPackage;
  private final boolean useConstantExpressions;
  private final Supplier<BuildConfigFields> fields;
  private final RelPath outBuildConfigPath;

  public GenerateBuildConfigStep(
      String source,
      String javaPackage,
      boolean useConstantExpressions,
      Supplier<BuildConfigFields> fields,
      RelPath outBuildConfigPath) {
    this.source = source;
    this.javaPackage = javaPackage;
    this.useConstantExpressions = useConstantExpressions;
    this.fields = fields;
    this.outBuildConfigPath = outBuildConfigPath;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context) throws IOException {
    String java =
        BuildConfigs.generateBuildConfigDotJava(
            source, javaPackage, useConstantExpressions, fields.get());
    ProjectFilesystemUtils.writeContentsToPath(
        context.getRuleCellRoot(), java, outBuildConfigPath.getPath());

    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getDescription(StepExecutionContext context) {
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
