/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.step.fs;

import com.facebook.buck.core.util.immutables.BuckStyleStep;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.Escaper;
import java.io.IOException;
import java.nio.file.Files;
import org.immutables.value.Value;

/** Command that runs equivalent command of {@code mkdir -p} on the specified directory. */
@Value.Immutable
@BuckStyleStep
abstract class AbstractMkdirStep implements Step {

  @Value.Parameter
  protected abstract BuildCellRelativePath getPath();

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    Files.createDirectories(
        context.getBuildCellRootPath().resolve(getPath().getPathRelativeToBuildCellRoot()));
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "mkdir";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format(
        "mkdir -p %s",
        Escaper.escapeAsShellString(getPath().getPathRelativeToBuildCellRoot().toString()));
  }
}
