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
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.IsolatedStepsRunner;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.workertool.model.CommandTypeMessage;
import com.facebook.buck.workertool.model.ExecuteCommand;
import com.facebook.buck.workertool.model.ShutdownCommand;
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

    try (NamedPipeWriter namedPipe =
            NAMED_PIPE_FACTORY.connectAsWriter(workerToolParsedEnvs.getEventPipe());
        OutputStream outputStream = namedPipe.getOutputStream()) {
      // establish downward protocol type as binary
      DOWNWARD_PROTOCOL_TYPE.writeDelimitedTo(outputStream);
      Logger.get("").addHandler(new ExternalLogHandler(outputStream));

      executeSteps(workerToolParsedEnvs, console, outputStream);

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
    String errorMessage = ErrorLogger.getUserFriendlyMessage(throwable);
    console.printErrorText(
        "Failed to execute java compilation action. Thread: "
            + thread
            + System.lineSeparator()
            + errorMessage);
    System.exit(1);
  }

  private static void executeSteps(
      WorkerToolParsedEnvs workerToolParsedEnvs, Console console, OutputStream outputStream)
      throws Exception {
    try (NamedPipeReader namedPipe =
            NAMED_PIPE_FACTORY.connectAsReader(workerToolParsedEnvs.getCommandPipe());
        InputStream inputStream = namedPipe.getInputStream()) {

      while (true) {
        CommandTypeMessage commandTypeMessage = CommandTypeMessage.parseDelimitedFrom(inputStream);
        CommandTypeMessage.CommandType commandType = commandTypeMessage.getCommandType();

        switch (commandType) {
          case EXECUTE_COMMAND:
            ExecuteCommand executeCommand = ExecuteCommand.parseDelimitedFrom(inputStream);
            LOG.info("Execute command received: %s", executeCommand);

            BuildJavaCommand buildJavaCommand = BuildJavaCommand.parseDelimitedFrom(inputStream);

            JavaCDWorkerToolStepsBuilder javaCDWorkerToolStepsBuilder =
                new JavaCDWorkerToolStepsBuilder(buildJavaCommand);
            AbsPath ruleCellRoot = javaCDWorkerToolStepsBuilder.getRuleCellRoot();
            ImmutableList<IsolatedStep> isolatedSteps = javaCDWorkerToolStepsBuilder.getSteps();

            String actionId = executeCommand.getActionId();
            executeJavaCompilationCommand(
                isolatedSteps,
                ruleCellRoot,
                workerToolParsedEnvs.getBuildUuid(),
                actionId,
                console,
                outputStream);
            break;

          case SHUTDOWN_COMMAND:
            ShutdownCommand shutdownCommand = ShutdownCommand.parseDelimitedFrom(inputStream);
            LOG.info("Shutdown command received: %s", shutdownCommand);
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

  private static void executeJavaCompilationCommand(
      ImmutableList<IsolatedStep> isolatedSteps,
      AbsPath ruleCellRoot,
      BuildId buildUuid,
      String actionId,
      Console console,
      OutputStream outputStream)
      throws IOException {
    long startExecutionMillis = System.currentTimeMillis();
    try (IsolatedEventBus eventBus =
        new DefaultIsolatedEventBus(
            buildUuid, outputStream, startExecutionMillis, DOWNWARD_PROTOCOL)) {
      IsolatedExecutionContext executionContext =
          IsolatedExecutionContext.of(
              eventBus,
              console,
              Platform.detect(),
              new DefaultProcessExecutor(console),
              ruleCellRoot,
              actionId);
      IsolatedStepsRunner.execute(isolatedSteps, executionContext);
    }
  }
}
