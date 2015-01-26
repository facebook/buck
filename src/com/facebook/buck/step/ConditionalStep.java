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

import com.google.common.base.Supplier;

import java.io.IOException;

/** {@link Step} that is run conditionally based on {@code Supplier&lt;Boolean> shouldRunStep}. */
public class ConditionalStep implements Step {

  private final Supplier<Boolean> shouldRunStep;
  private final Step step;

  public ConditionalStep(Supplier<Boolean> shouldRunStep, Step step) {
    this.shouldRunStep = shouldRunStep;
    this.step = step;
  }

  @Override
  public int execute(ExecutionContext context) throws IOException, InterruptedException {
    if (shouldRunStep.get()) {
      return step.execute(context);
    } else {
      return 0;
    }
  }

  @Override
  public String getShortName() {
    // Use the short name of the underlying Step because StepEvent.getCategory() uses the short
    // name to group similar step types together so they can be audited for time spent.
    return step.getShortName();
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "conditionally: " + step.getDescription(context);
  }
}
