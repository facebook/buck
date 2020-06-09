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

package com.facebook.buck.step.isolatedsteps.common;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import java.io.IOException;

/**
 * Abstract implementation of {@link IsolatedStep} that takes the description as a constructor
 * parameter and requires only the implementation of {@link
 * #executeIsolatedStep(IsolatedExecutionContext)}. This facilitates the creation of an anonymous
 * implementation of {@link IsolatedStep}.
 */
public abstract class AbstractIsolatedExecutionStep extends IsolatedStep {

  private final String description;

  public AbstractIsolatedExecutionStep(String description) {
    this.description = description;
  }

  @Override
  public abstract StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException;

  @Override
  public String getShortName() {
    return description;
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return description;
  }
}
