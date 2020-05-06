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

package com.facebook.buck.step;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import java.io.IOException;

/**
 * Isolated step - build rule's step that is isolated from the buck internal data structures as an
 * action graph. For example doesn't contain any references to a {@link
 * com.facebook.buck.core.sourcepath.SourcePath}, {@link com.facebook.buck.rules.modern.OutputPath}
 * and cell related objects.
 */
public abstract class IsolatedStep implements Step {

  @Override
  public final StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    return executeIsolatedStep(context);
  }

  @Override
  public final String getDescription(ExecutionContext context) {
    return getIsolatedStepDescription(context);
  }

  public abstract StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException;

  public abstract String getIsolatedStepDescription(IsolatedExecutionContext context);
}
