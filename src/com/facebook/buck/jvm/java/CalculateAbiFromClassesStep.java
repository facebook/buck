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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.abi.StubJar;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import java.io.IOException;
import java.nio.file.Path;

public class CalculateAbiFromClassesStep implements Step {

  private final BuildableContext buildableContext;
  private final ProjectFilesystem filesystem;
  private final Path binaryJar;
  private final Path abiJar;
  private final boolean sourceAbiCompatible;

  public CalculateAbiFromClassesStep(
      BuildableContext buildableContext,
      ProjectFilesystem filesystem,
      Path binaryJar,
      Path abiJar,
      boolean sourceAbiCompatible) {
    this.buildableContext = buildableContext;
    this.filesystem = filesystem;
    this.binaryJar = binaryJar;
    this.abiJar = abiJar;
    this.sourceAbiCompatible = sourceAbiCompatible;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) {
    try {
      Path binJar = filesystem.resolve(binaryJar);
      new StubJar(binJar).setSourceAbiCompatible(sourceAbiCompatible).writeTo(filesystem, abiJar);
      buildableContext.recordArtifact(abiJar);
    } catch (IOException | IllegalArgumentException e) {
      context.logError(e, "Failed to calculate ABI for %s.", binaryJar);
      return StepExecutionResult.ERROR;
    }

    return StepExecutionResult.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "calculate_abi_from_classes";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("%s %s", getShortName(), binaryJar);
  }
}
