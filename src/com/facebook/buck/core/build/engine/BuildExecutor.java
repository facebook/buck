/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.build.engine;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import java.io.IOException;

/**
 * Interface used by a BuildExecutorRunner to build a rule. Should be approximately equivalent to:
 *
 * <pre>{@code
 * for (Step step : rule.getBuildSteps(buildRuleBuildContext, buildableContext)) {
 *   stepRunner.runStepForBuildTarget(executionContext, step, Optional.of(rule.getBuildTarget()));
 * }
 * }</pre>
 */
public interface BuildExecutor {
  void executeCommands(
      ExecutionContext executionContext,
      BuildContext buildRuleBuildContext,
      BuildableContext buildableContext,
      StepRunner stepRunner)
      throws StepFailedException, InterruptedException, IOException;
}
