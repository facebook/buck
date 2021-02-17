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

package com.facebook.buck.step.isolatedsteps.android;

import com.facebook.buck.android.BuildConfigs;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.base.Objects;
import java.io.IOException;
import java.util.Optional;

/** Step to generate an Android Build Config. */
public class GenerateBuildConfigStep extends IsolatedStep {

  private final String source;
  private final String javaPackage;
  private final boolean useConstantExpressions;
  private final Optional<RelPath> valuesFile;
  private final BuildConfigFields defaultValues;
  private final RelPath outBuildConfigPath;

  public GenerateBuildConfigStep(
      String source,
      String javaPackage,
      boolean useConstantExpressions,
      Optional<RelPath> valuesFile,
      BuildConfigFields defaultValues,
      RelPath outBuildConfigPath) {
    this.source = source;
    this.javaPackage = javaPackage;
    this.useConstantExpressions = useConstantExpressions;
    this.valuesFile = valuesFile;
    this.defaultValues = defaultValues;
    this.outBuildConfigPath = outBuildConfigPath;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {

    BuildConfigFields fields;
    if (!valuesFile.isPresent()) {
      fields = defaultValues;
    } else {
      fields =
          defaultValues.putAll(
              BuildConfigFields.fromFieldDeclarations(
                  ProjectFilesystemUtils.readLines(
                      context.getRuleCellRoot(), valuesFile.get().getPath())));
    }

    String java =
        BuildConfigs.generateBuildConfigDotJava(
            source, javaPackage, useConstantExpressions, fields);
    ProjectFilesystemUtils.writeContentsToPath(
        context.getRuleCellRoot(), java, outBuildConfigPath.getPath());

    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
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
        && Objects.equal(this.valuesFile, that.valuesFile)
        && Objects.equal(this.defaultValues, that.defaultValues)
        && Objects.equal(this.outBuildConfigPath, that.outBuildConfigPath);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        javaPackage, useConstantExpressions, valuesFile, defaultValues, outBuildConfigPath);
  }
}
