/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.step;

import com.facebook.buck.core.build.execution.context.ExecutionContext;

public class FakeStep implements Step {
  private final String shortName;
  private final String description;
  private final int exitCode;

  public FakeStep(String shortName, String description, int exitCode) {
    this.shortName = shortName;
    this.description = description;
    this.exitCode = exitCode;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) {
    return StepExecutionResult.of(exitCode);
  }

  @Override
  public String getShortName() {
    return shortName;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return description;
  }
}
