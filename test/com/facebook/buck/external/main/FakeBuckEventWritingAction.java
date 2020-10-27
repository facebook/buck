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

package com.facebook.buck.external.main;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.external.model.ExternalAction;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.collect.ImmutableList;

/**
 * {@link ExternalAction} for testing purposes. Expects one arg from the {@link BuildableCommand}: a
 * message to be included in a {@link ConsoleEvent}.
 */
public class FakeBuckEventWritingAction implements ExternalAction {

  @Override
  public ImmutableList<IsolatedStep> getSteps(BuildableCommand buildableCommand) {
    ConsoleEventStep consoleEventStep = new ConsoleEventStep(buildableCommand.getArgs(0));
    return ImmutableList.of(consoleEventStep);
  }

  static class ConsoleEventStep extends IsolatedStep {

    private final String message;

    private ConsoleEventStep(String message) {
      this.message = message;
    }

    @Override
    public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context) {
      IsolatedEventBus isolatedEventBus = context.getIsolatedEventBus();
      try (SimplePerfEvent.Scope scope =
          SimplePerfEvent.scope(
              isolatedEventBus, SimplePerfEvent.PerfEventTitle.of("test_perf_event_title"))) {
        isolatedEventBus.post(ConsoleEvent.info(message));
      }
      return StepExecutionResults.SUCCESS;
    }

    @Override
    public String getIsolatedStepDescription(IsolatedExecutionContext context) {
      return String.format("console event: %s", message);
    }

    @Override
    public String getShortName() {
      return "console_event_step";
    }
  }
}
