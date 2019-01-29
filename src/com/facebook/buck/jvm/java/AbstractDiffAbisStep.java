/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.util.immutables.BuckStyleStep;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Joiner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleStep
abstract class AbstractDiffAbisStep implements Step {
  private static final Logger LOG = Logger.get(AbstractDiffAbisStep.class);

  @Value.Parameter
  protected abstract Path getClassAbiPath();

  @Value.Parameter
  protected abstract Path getSourceAbiPath();

  @Value.Parameter
  protected abstract JavaBuckConfig.SourceAbiVerificationMode getVerificationMode();

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    Path classAbiPath = getClassAbiPath();
    Path sourceAbiPath = getSourceAbiPath();

    List<String> diff = JarDiffer.diffJars(classAbiPath, sourceAbiPath);
    if (diff.isEmpty()) {
      return StepExecutionResults.SUCCESS;
    }

    String message = String.format("Files differ:\n%s", Joiner.on('\n').join(diff));
    JavaBuckConfig.SourceAbiVerificationMode verificationMode = getVerificationMode();
    switch (verificationMode) {
      case OFF:
        return StepExecutionResults.SUCCESS;
      case LOG:
        LOG.warn(message);
        return StepExecutionResults.SUCCESS;
      case FAIL:
        return StepExecutionResults.ERROR.withStderr(message);
      default:
        throw new AssertionError(String.format("Unknown verification mode: %s", verificationMode));
    }
  }

  @Override
  public String getShortName() {
    return "diff_abi_jars";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("diff %s %s", getClassAbiPath(), getSourceAbiPath());
  }
}
