/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.java.stepsbuilder.javacd.main;

import com.facebook.buck.cd.model.java.BuildJavaCommand;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.JavaStepsBuilder;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.google.common.collect.ImmutableList;
import java.io.IOException;

/** Executes java compilation command. */
class BuildJavaCommandExecutor {

  private BuildJavaCommandExecutor() {}

  static StepExecutionResult executeBuildJavaCommand(
      ClassLoaderCache classLoaderCache,
      ActionId actionId,
      BuildJavaCommand buildJavaCommand,
      IsolatedEventBus eventBus,
      Platform platform,
      ProcessExecutor processExecutor,
      Console console,
      Clock clock)
      throws IOException {

    JavaStepsBuilder javaStepsBuilder = new JavaStepsBuilder(buildJavaCommand);
    AbsPath ruleCellRoot = javaStepsBuilder.getRuleCellRoot();
    ImmutableList<IsolatedStep> steps = javaStepsBuilder.getSteps();

    try (IsolatedExecutionContext context =
        StepExecutionUtils.createExecutionContext(
            classLoaderCache,
            eventBus,
            platform,
            processExecutor,
            console,
            clock,
            actionId,
            ruleCellRoot)) {
      return StepExecutionUtils.executeSteps(steps, context);
    }
  }
}
