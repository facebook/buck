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

package com.facebook.buck.step.fs;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import java.io.IOException;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** Delegates to {@link IsolatedStep} step. */
abstract class DelegateStep<T extends IsolatedStep> implements Step {

  @Nullable private T delegate;

  protected abstract T createDelegate(StepExecutionContext context);

  protected abstract String getShortNameSuffix();

  @Override
  public final String getShortName() {
    return "delegated_" + getShortNameSuffix();
  }

  @Override
  public final StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    return getDelegate(context).executeIsolatedStep(context);
  }

  @Override
  public final String getDescription(StepExecutionContext context) {
    return getDelegate(context).getIsolatedStepDescription(context);
  }

  @Value.Lazy
  private T getDelegate(StepExecutionContext context) {
    if (delegate == null) {
      delegate = createDelegate(context);
    }
    return delegate;
  }
}
