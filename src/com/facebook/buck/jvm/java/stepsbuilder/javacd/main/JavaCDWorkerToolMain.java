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

package com.facebook.buck.jvm.java.stepsbuilder.javacd.main;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.isolated.DefaultIsolatedEventBus;
import com.facebook.buck.external.log.ExternalLogHandler;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.javacd.model.BuildJavaCommand;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.JavaCDWorkerToolStepsBuilder;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.IsolatedStepsRunner;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.workertool.model.CommandTypeMessage;
import com.facebook.buck.workertool.model.ExecuteCommand;
import com.facebook.buck.workertool.model.ShutdownCommand;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** JavaCD main class */
public class JavaCDWorkerToolMain {

  private static final Logger LOG = Logger.get(JavaCDWorkerToolMain.class);

  private static final NamedPipeFactory NAMED_PIPE_FACTORY = NamedPipeFactory.getFactory();
  private static final DownwardProtocolType DOWNWARD_PROTOCOL_TYPE = DownwardProtocolType.BINARY;
  private static final DownwardProtocol DOWNWARD_PROTOCOL =
      DOWNWARD_PROTOCOL_TYPE.getDownwardProtocol();

  /** Main entrypoint of JavaCD worker tool. */
  public static void main(String[] args) {
    WorkerToolParsedEnvs workerToolParsedEnvs =
        WorkerToolParsedEnvs.parse(EnvVariablesProvider.getSystemEnv());
    Console console = createConsole(workerToolParsedEnvs);

    try (NamedPipeWriter eventNamedPipe =
            NAMED_PIPE_FACTORY.connectAsWriter(workerToolParsedEnvs.getEventPipe());
        OutputStream eventsOutputStream = eventNamedPipe.getOutputStream()) {
      // establish downward protocol type
      DOWNWARD_PROTOCOL_TYPE.writeDelimitedTo(eventsOutputStream);
      Logger logger = Logger.get("");
      logger.cleanHandlers();
      logger.addHandler(new ExternalLogHandler(eventsOutputStream, DOWNWARD_PROTOCOL));

      handleCommands(workerToolParsedEnvs, console, eventsOutputStream);

    } catch (Exception e) {
      handleExceptionAndTerminate(Thread.currentThread(), console, e);
    }
    System.exit(0);
  }

  private static Console createConsole(WorkerToolParsedEnvs parsedEnvVars) {
    return new Console(
        parsedEnvVars.getVerbosity(),
        System.out,
        System.err,
        new Ansi(parsedEnvVars.isAnsiTerminal()));
  }

  private static void handleExceptionAndTerminate(
      Thread thread, Console console, Throwable throwable) {
    // Remove an existing `ExternalLogHandler` handler that depend on the closed event pipe stream.
    Logger logger = Logger.get("");
    logger.cleanHandlers();

    String errorMessage = ErrorLogger.getUserFriendlyMessage(throwable);
    // this method logs the message with log.warn that would be noop as all logger handlers have
    // been cleaned and prints the message into a std err.
    console.printErrorText(
        "Failed to execute java compilation action. Thread: "
            + thread
            + System.lineSeparator()
            + errorMessage);
    System.exit(1);
  }

  private static void handleCommands(
      WorkerToolParsedEnvs workerToolParsedEnvs, Console console, OutputStream eventsOutputStream)
      throws Exception {

    BuildId buildUuid = workerToolParsedEnvs.getBuildUuid();
    ProcessExecutor processExecutor = new DefaultProcessExecutor(console);
    Platform platform = Platform.detect();
    // no need to measure thread CPU time as this is an external process and we do not pass thread
    // time back to buck with Downward API
    Clock clock = new DefaultClock(false);

    try (IsolatedEventBus eventBus =
        new DefaultIsolatedEventBus(buildUuid, eventsOutputStream, clock, DOWNWARD_PROTOCOL)) {

      try (NamedPipeReader commandsNamedPipe =
              NAMED_PIPE_FACTORY.connectAsReader(workerToolParsedEnvs.getCommandPipe());
          InputStream commandsInputStream = commandsNamedPipe.getInputStream()) {

        while (true) {
          CommandTypeMessage commandTypeMessage =
              CommandTypeMessage.parseDelimitedFrom(commandsInputStream);
          CommandTypeMessage.CommandType commandType = commandTypeMessage.getCommandType();

          switch (commandType) {
            case EXECUTE_COMMAND:
              ExecuteCommand executeCommand =
                  ExecuteCommand.parseDelimitedFrom(commandsInputStream);
              String actionId = executeCommand.getActionId();
              BuildJavaCommand buildJavaCommand =
                  BuildJavaCommand.parseDelimitedFrom(commandsInputStream);
              LOG.info("Execute command with action id: %s received", actionId);

              handleBuildJavaCommand(
                  actionId,
                  buildJavaCommand,
                  eventsOutputStream,
                  eventBus,
                  platform,
                  processExecutor,
                  console,
                  clock);
              break;

            case SHUTDOWN_COMMAND:
              ShutdownCommand shutdownCommand =
                  ShutdownCommand.parseDelimitedFrom(commandsInputStream);
              LOG.info(
                  "Shutdown command received: %s. Stopping javacd worker tool...", shutdownCommand);
              return;

            case START_PIPELINE_COMMAND:
              // TODO: msemko : Implement pipelining support
              throw new UnsupportedOperationException("Pipelining support not yet implemented!");

            case UNKNOWN:
            case UNRECOGNIZED:
            default:
              throw new IllegalStateException(commandType + " is not supported!");
          }
        }
      }
    }
  }

  private static void handleBuildJavaCommand(
      String actionId,
      BuildJavaCommand buildJavaCommand,
      OutputStream eventsOutputStream,
      IsolatedEventBus eventBus,
      Platform platform,
      ProcessExecutor processExecutor,
      Console console,
      Clock clock)
      throws IOException {

    JavaCDWorkerToolStepsBuilder javaCDWorkerToolStepsBuilder =
        new JavaCDWorkerToolStepsBuilder(buildJavaCommand);
    AbsPath ruleCellRoot = javaCDWorkerToolStepsBuilder.getRuleCellRoot();
    ImmutableList<IsolatedStep> isolatedSteps = javaCDWorkerToolStepsBuilder.getSteps();
    StepExecutionResult stepExecutionResult;
    try (IsolatedExecutionContext executionContext =
        IsolatedExecutionContext.of(
            eventBus, console, platform, processExecutor, ruleCellRoot, actionId, clock)) {
      stepExecutionResult = IsolatedStepsRunner.execute(isolatedSteps, executionContext);
    }

    int exitCode = stepExecutionResult.getExitCode();
    ResultEvent.Builder resultEventBuilder =
        ResultEvent.newBuilder().setActionId(actionId).setExitCode(exitCode);
    if (!stepExecutionResult.isSuccess()) {
      StringBuilder errorMessage = new StringBuilder();
      stepExecutionResult
          .getStderr()
          .ifPresent(
              stdErr ->
                  errorMessage.append("Std err: ").append(stdErr).append(System.lineSeparator()));
      stepExecutionResult
          .getCause()
          .ifPresent(
              cause ->
                  errorMessage.append("Cause: ").append(Throwables.getStackTraceAsString(cause)));
      if (errorMessage.length() > 0) {
        resultEventBuilder.setMessage(errorMessage.toString());
      }
    }

    ResultEvent resultEvent = resultEventBuilder.build();
    DOWNWARD_PROTOCOL.write(
        EventTypeMessage.newBuilder().setEventType(EventTypeMessage.EventType.RESULT_EVENT).build(),
        resultEvent,
        eventsOutputStream);
  }
}
