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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.javacd.model.AbiGenerationMode;
import com.facebook.buck.jvm.java.abi.StubJar;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Path;

/** Calculates class abi from the library.jar */
public class CalculateClassAbiStep extends IsolatedStep {

  private final RelPath binaryJar;
  private final RelPath abiJar;
  private final AbiGenerationMode compatibilityMode;

  public CalculateClassAbiStep(
      RelPath binaryJar, RelPath abiJar, AbiGenerationMode compatibilityMode) {
    this.binaryJar = binaryJar;
    this.abiJar = abiJar;
    this.compatibilityMode = compatibilityMode;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException {
    AbsPath ruleCellRoot = context.getRuleCellRoot();
    AbsPath output = toAbsOutputPath(ruleCellRoot, abiJar);

    try {
      new StubJar(ruleCellRoot.resolve(binaryJar))
          .setCompatibilityMode(compatibilityMode)
          .writeTo(output);
    } catch (IllegalArgumentException e) {
      context.logError(e, "Failed to calculate ABI for %s.", binaryJar);
      return StepExecutionResults.ERROR;
    }

    return StepExecutionResults.SUCCESS;
  }

  private AbsPath toAbsOutputPath(AbsPath root, RelPath relativeOutputPath) throws IOException {
    Path outputPath = ProjectFilesystemUtils.getPathForRelativePath(root, relativeOutputPath);
    Preconditions.checkState(
        !ProjectFilesystemUtils.exists(root, outputPath),
        "Output file already exists: %s",
        relativeOutputPath);

    if (outputPath.getParent() != null
        && !ProjectFilesystemUtils.exists(root, outputPath.getParent())) {
      ProjectFilesystemUtils.createParentDirs(root, outputPath);
    }
    return root.resolve(relativeOutputPath);
  }

  @Override
  public String getShortName() {
    return "class_abi";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return String.format("%s %s", getShortName(), binaryJar);
  }
}
