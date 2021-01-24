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
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.isolated.DefaultIsolatedEventBus;
import com.facebook.buck.external.log.ExternalLogHandler;
import com.facebook.buck.external.model.ExternalAction;
import com.facebook.buck.external.model.ParsedArgs;
import com.facebook.buck.external.parser.ExternalArgsParser;
import com.facebook.buck.external.parser.ParsedEnvVars;
import com.facebook.buck.external.utils.BuildStepsRetriever;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.IsolatedStepsRunner;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.OutputStream;

/**
 * Main entry point for executing {@link ExternalAction} instances.
 *
 * <p>Expected usage: {@code this_binary <external_action_class_name> <buildable_command_path>}. See
 * {@link ExternalArgsParser}.
 *
 * <p>This binary also expects some environment variables to be written before execution. See {@link
 * ParsedEnvVars}.
 */
public class ExternalActionsExecutable {

  private static final NamedPipeFactory NAMED_PIPE_FACTORY = NamedPipeFactory.getFactory();
  private static final DownwardProtocolType DOWNWARD_PROTOCOL_TYPE = DownwardProtocolType.BINARY;
  private static final DownwardProtocol DOWNWARD_PROTOCOL =
      DOWNWARD_PROTOCOL_TYPE.getDownwardProtocol();

  /** Main entrypoint of actions that can be built in a separate process from buck. */
  public static void main(String[] args) {
    // Note that creating if expected environment variables are not present, this will throw a
    // runtime exception
    ParsedEnvVars parsedEnvVars = ParsedEnvVars.parse(EnvVariablesProvider.getSystemEnv());
    Console console = createConsole(parsedEnvVars);
    try (NamedPipeWriter namedPipe =
            NAMED_PIPE_FACTORY.connectAsWriter(parsedEnvVars.getEventPipe());
        OutputStream outputStream = namedPipe.getOutputStream()) {
      DOWNWARD_PROTOCOL_TYPE.writeDelimitedTo(outputStream);
      Logger.get("").addHandler(new ExternalLogHandler(outputStream));
      executeSteps(args, parsedEnvVars, console, outputStream);
    } catch (Exception e) {
      handleExceptionAndTerminate(Thread.currentThread(), console, e);
    }
    System.exit(0);
  }

  private static Console createConsole(ParsedEnvVars parsedEnvVars) {
    return new Console(
        parsedEnvVars.getVerbosity(),
        System.out,
        System.err,
        new Ansi(parsedEnvVars.isAnsiTerminal()));
  }

  private static void handleExceptionAndTerminate(
      Thread thread, Console console, Throwable throwable) {
    String errorMessage = ErrorLogger.getUserFriendlyMessage(throwable);
    console.printErrorText(
        "Failed to execute external action. Thread: "
            + thread
            + System.lineSeparator()
            + errorMessage);
    System.exit(1);
  }

  private static void executeSteps(
      String[] args, ParsedEnvVars parsedEnvVars, Console console, OutputStream outputStream)
      throws Exception {
    ParsedArgs parsedArgs = new ExternalArgsParser().parse(args);
    ImmutableList<IsolatedStep> stepsToExecute =
        BuildStepsRetriever.getStepsForBuildable(parsedArgs);

    long startExecutionEpochMillis = System.currentTimeMillis();
    try (IsolatedEventBus eventBus =
        new DefaultIsolatedEventBus(
            parsedEnvVars.getBuildUuid(),
            outputStream,
            startExecutionEpochMillis,
            DOWNWARD_PROTOCOL)) {
      IsolatedExecutionContext executionContext =
          IsolatedExecutionContext.of(
              eventBus,
              console,
              Platform.detect(),
              new DefaultProcessExecutor(console),
              parsedEnvVars.getRuleCellRoot(),
              parsedEnvVars.getActionId());
      IsolatedStepsRunner.execute(stepsToExecute, executionContext);
    }
  }
}
