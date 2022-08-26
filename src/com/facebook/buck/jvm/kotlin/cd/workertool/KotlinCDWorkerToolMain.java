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

package com.facebook.buck.jvm.kotlin.cd.workertool;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.facebook.buck.cd.model.kotlin.BuildKotlinCommand;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.build.execution.context.actionid.ActionId;
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
import com.facebook.buck.jvm.cd.workertool.MainUtils;
import com.facebook.buck.jvm.cd.workertool.StepExecutionUtils;
import com.facebook.buck.jvm.cd.workertool.WorkerToolParsedEnvs;
import com.facebook.buck.jvm.kotlin.cd.KotlinStepsBuilder;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.monitoring.HangMonitor;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.types.Unit;
import com.facebook.buck.workertool.model.CommandTypeMessage;
import com.facebook.buck.workertool.model.ExecuteCommand;
import com.facebook.buck.workertool.model.ShutdownCommand;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Main class for KotlinCD Worker Tool (the one that listens for protocol buffer messages). */
public class KotlinCDWorkerToolMain {
  private static final Logger LOG = Logger.get(KotlinCDWorkerToolMain.class);

  private static final NamedPipeFactory NAMED_PIPE_FACTORY = NamedPipeFactory.getFactory();
  private static final DownwardProtocolType DOWNWARD_PROTOCOL_TYPE = DownwardProtocolType.BINARY;
  private static final DownwardProtocol DOWNWARD_PROTOCOL =
      DOWNWARD_PROTOCOL_TYPE.getDownwardProtocol();

  private static final ThreadPoolExecutor THREAD_POOL =
      new ThreadPoolExecutor(
          0,
          Integer.MAX_VALUE,
          1,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          new MostExecutors.NamedThreadFactory("KotlinCD"));

  private static final ListeningExecutorService LISTENING_EXECUTOR_SERVICE =
      MoreExecutors.listeningDecorator(THREAD_POOL);

  private static final ScheduledExecutorService MONITORING_THREAD_POOL =
      Executors.newSingleThreadScheduledExecutor();

  private static final Duration HANG_DETECTOR_TIMEOUT = Duration.ofMinutes(5);
  private static final HangMonitor.AutoStartInstance HANG_MONITOR =
      new HangMonitor.AutoStartInstance(
          (threadDump) ->
              LOG.info(
                  "No recent kotlincd activity, dumping kotlincd thread stacks (`tr , '\\n'` to decode):%n %s",
                  threadDump),
          HANG_DETECTOR_TIMEOUT);

  /**
   * This is just a simple debugging tool. When started with this env var, kotlincd will write the
   * command protos to buck-out/protos in json-encoded format.
   */
  private static final boolean ENABLE_DEBUG_PROTO_DUMPS =
      EnvVariablesProvider.getSystemEnv().get("KOTLINCD_DUMP_PROTOS") != null;

  /** Main entrypoint of JavaCD worker tool. */
  public static void main(String[] args) {
    WorkerToolParsedEnvs workerToolParsedEnvs =
        WorkerToolParsedEnvs.parse(EnvVariablesProvider.getSystemEnv());
    Console console = createConsole(workerToolParsedEnvs);
    MONITORING_THREAD_POOL.scheduleAtFixedRate(
        KotlinCDWorkerToolMain::logCurrentKotlincdState, 1, 10, TimeUnit.SECONDS);

    try (NamedPipeWriter eventNamedPipe =
            NAMED_PIPE_FACTORY.connectAsWriter(workerToolParsedEnvs.getEventPipe());
        OutputStream eventsOutputStream = eventNamedPipe.getOutputStream();
        ClassLoaderCache classLoaderCache = new ClassLoaderCache()) {
      // establish downward protocol type
      DOWNWARD_PROTOCOL_TYPE.writeDelimitedTo(eventsOutputStream);
      Thread.setDefaultUncaughtExceptionHandler(
          (t, e) -> MainUtils.handleExceptionAndTerminate(t, console, e));

      Logger logger = Logger.get("");
      logger.cleanHandlers();
      logger.addHandler(new ExternalLogHandler(eventsOutputStream, DOWNWARD_PROTOCOL));

      handleCommands(workerToolParsedEnvs, console, eventsOutputStream, classLoaderCache);

    } catch (Exception e) {
      MainUtils.handleExceptionAndTerminate(Thread.currentThread(), console, e);
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

  private static void handleCommands(
      WorkerToolParsedEnvs workerToolParsedEnvs,
      Console console,
      OutputStream eventsOutputStream,
      ClassLoaderCache classLoaderCache)
      throws Exception {

    BuildId buildUuid = workerToolParsedEnvs.getBuildUuid();
    ProcessExecutor processExecutor = new DefaultProcessExecutor(console);
    Platform platform = Platform.detect();
    // no need to measure thread CPU time as this is an external process and we do not pass thread
    // time back to buck with Downward API
    Clock clock = new DefaultClock(false);

    try (IsolatedEventBus eventBus =
            new DefaultIsolatedEventBus(
                buildUuid,
                eventsOutputStream,
                clock,
                DOWNWARD_PROTOCOL,
                workerToolParsedEnvs.getActionId());
        NamedPipeReader commandsNamedPipe =
            NAMED_PIPE_FACTORY.connectAsReader(workerToolParsedEnvs.getCommandPipe());
        InputStream commandsInputStream = commandsNamedPipe.getInputStream()) {
      while (true) {
        CommandTypeMessage commandTypeMessage =
            CommandTypeMessage.parseDelimitedFrom(commandsInputStream);
        CommandTypeMessage.CommandType commandType = commandTypeMessage.getCommandType();

        switch (commandType) {
          case EXECUTE_COMMAND:
            ExecuteCommand executeCommand = ExecuteCommand.parseDelimitedFrom(commandsInputStream);
            ActionId executeCommandActionId = ActionId.of(executeCommand.getActionId());
            BuildKotlinCommand buildJavaCommand =
                BuildKotlinCommand.parseDelimitedFrom(commandsInputStream);
            LOG.debug("Start executing command with action id: %s", executeCommandActionId);
            maybeWriteProtoForDebugging(executeCommandActionId, buildJavaCommand);

            ListenableFuture<Unit> executeCommandFuture =
                LISTENING_EXECUTOR_SERVICE.submit(
                    () -> {
                      StepExecutionUtils.sendResultEvent(
                          executeBuildKotlinCommand(
                              classLoaderCache,
                              executeCommandActionId,
                              buildJavaCommand,
                              eventBus,
                              platform,
                              processExecutor,
                              console,
                              clock),
                          executeCommandActionId,
                          DOWNWARD_PROTOCOL,
                          eventsOutputStream);

                      return Unit.UNIT;
                    });

            addWorkAdvanceCallback(executeCommandFuture);
            break;

          case SHUTDOWN_COMMAND:
            ShutdownCommand shutdownCommand =
                ShutdownCommand.parseDelimitedFrom(commandsInputStream);
            LOG.debug(
                "Shutdown command received: %s. Stopping javacd worker tool...", shutdownCommand);
            return;

          case START_PIPELINE_COMMAND:
          case START_NEXT_PIPELINING_COMMAND:
            // Pipelining is not supported for KotlinCD
          case UNKNOWN:
          case UNRECOGNIZED:
          default:
            throw new IllegalStateException(commandType + " is not supported!");
        }
      }
    }
  }

  private static void maybeWriteProtoForDebugging(ActionId actionId, Message message) {
    if (!ENABLE_DEBUG_PROTO_DUMPS) {
      return;
    }

    try {
      String asJson = JsonFormat.printer().print(message);
      Path protoOut =
          Paths.get("buck-out/protos")
              .resolve("./" + actionId.getValue().replace(':', '/').split(" ")[0])
              .resolve("command_proto.json");

      Files.createDirectories(protoOut.getParent());
      Files.writeString(protoOut, asJson);
    } catch (Exception e) {
      System.err.println(e);
      throw new RuntimeException(e);
    }
  }

  private static StepExecutionResult executeBuildKotlinCommand(
      ClassLoaderCache classLoaderCache,
      ActionId actionId,
      BuildKotlinCommand buildKotlinCommand,
      IsolatedEventBus eventBus,
      Platform platform,
      ProcessExecutor processExecutor,
      Console console,
      Clock clock)
      throws IOException {
    KotlinStepsBuilder kotlinStepsBuilder = new KotlinStepsBuilder(buildKotlinCommand);
    AbsPath ruleCellRoot = kotlinStepsBuilder.getRuleCellRoot();
    ImmutableList<IsolatedStep> steps = kotlinStepsBuilder.getSteps();

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

  private static void addWorkAdvanceCallback(ListenableFuture<Unit> future) {
    Futures.addCallback(
        future,
        new FutureCallback<>() {
          @Override
          public void onSuccess(Unit explosion) {
            HANG_MONITOR.getHangMonitor().workAdvance();
          }

          @Override
          public void onFailure(Throwable thrown) {
            // no-op
          }
        },
        directExecutor());
  }

  private static void logCurrentKotlincdState() {
    int activeCount = THREAD_POOL.getActiveCount();
    long completedTaskCount = THREAD_POOL.getCompletedTaskCount();
    int largestPoolSize = THREAD_POOL.getLargestPoolSize();
    long taskCount = THREAD_POOL.getTaskCount();

    MainUtils.logCurrentCDState();
    LOG.info(
        "Kotlincd task state: executing tasks: %s, completed tasks: %s, largest pool size: %s, task count: %s.",
        activeCount, completedTaskCount, largestPoolSize, taskCount);
  }
}
