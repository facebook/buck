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
import com.facebook.buck.core.util.log.Logger;
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
 * {@link ExternalAction} for testing purposes. Expects two args from {@link BuildableCommand}:
 *
 * <ol>
 *   <li>String to be shown in the console;
 *   <li>String to be written as log.
 * </ol>
 */
public class FakeBuckEventWritingAction implements ExternalAction {

  @Override
  public ImmutableList<IsolatedStep> getSteps(BuildableCommand buildableCommand) {
    ConsoleEventStep consoleEventStep = new ConsoleEventStep(buildableCommand.getArgs(0));
    LogEventStep logEventStep = new LogEventStep(buildableCommand.getArgs(1));
    return ImmutableList.of(consoleEventStep, logEventStep);
  }

  static class ConsoleEventStep extends IsolatedStep {

    private static final Logger LOG = Logger.get(ConsoleEventStep.class.getName());

    private final String message;

    private ConsoleEventStep(String message) {
      this.message = message;
    }

    @Override
    public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context) {
      IsolatedEventBus isolatedEventBus = context.getIsolatedEventBus();
      LOG.info("Starting ConsoleEventStep execution for message %s!", message);
      try (SimplePerfEvent.Scope ignore =
          SimplePerfEvent.scopeWithActionId(
              isolatedEventBus, context.getActionId(), "test_perf_event_title")) {
        isolatedEventBus.post(ConsoleEvent.info(message));
      }
      LOG.info("Finished ConsoleEventStep execution for message %s!", message);
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

  static class LogEventStep extends IsolatedStep {

    private static final Logger LOG = Logger.get(LogEventStep.class.getName());

    private final String message;

    private LogEventStep(String message) {
      this.message = message;
    }

    @Override
    public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context) {
      LOG.error(message);
      return StepExecutionResults.SUCCESS;
    }

    @Override
    public String getIsolatedStepDescription(IsolatedExecutionContext context) {
      return String.format("log: %s", message);
    }

    @Override
    public String getShortName() {
      return "log_event_step";
    }
  }
}
