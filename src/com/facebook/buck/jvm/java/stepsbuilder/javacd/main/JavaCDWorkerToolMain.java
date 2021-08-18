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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.facebook.buck.core.build.execution.context.actionid.ActionId;
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
import com.facebook.buck.javacd.model.PipeliningCommand;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.monitoring.HangMonitor;
import com.facebook.buck.util.perf.PerfStatsTracking;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.types.Unit;
import com.facebook.buck.util.unit.SizeUnit;
import com.facebook.buck.workertool.model.CommandTypeMessage;
import com.facebook.buck.workertool.model.ExecuteCommand;
import com.facebook.buck.workertool.model.ShutdownCommand;
import com.facebook.buck.workertool.model.StartNextPipeliningCommand;
import com.facebook.buck.workertool.model.StartPipelineCommand;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** JavaCD main class */
public class JavaCDWorkerToolMain {

  private static final Logger LOG = Logger.get(JavaCDWorkerToolMain.class);

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
          new MostExecutors.NamedThreadFactory("JavaCD"));

  private static final ListeningExecutorService LISTENING_EXECUTOR_SERVICE =
      MoreExecutors.listeningDecorator(THREAD_POOL);

  private static final ScheduledExecutorService MONITORING_THREAD_POOL =
      Executors.newSingleThreadScheduledExecutor();
  private static final int AVAILABLE_PROCESSORS = Runtime.getRuntime().availableProcessors();

  private static final Duration HANG_DETECTOR_TIMEOUT = Duration.ofMinutes(5);
  private static final HangMonitor.AutoStartInstance HANG_MONITOR =
      new HangMonitor.AutoStartInstance(
          (threadDump) ->
              LOG.info(
                  "No recent javacd activity, dumping javacd thread stacks (`tr , '\\n'` to decode):%n %s",
                  threadDump),
          HANG_DETECTOR_TIMEOUT);

  /** Main entrypoint of JavaCD worker tool. */
  public static void main(String[] args) {
    WorkerToolParsedEnvs workerToolParsedEnvs =
        WorkerToolParsedEnvs.parse(EnvVariablesProvider.getSystemEnv());
    Console console = createConsole(workerToolParsedEnvs);
    MONITORING_THREAD_POOL.scheduleAtFixedRate(
        JavaCDWorkerToolMain::logCurrentJavacdState, 1, 10, TimeUnit.SECONDS);

    try (NamedPipeWriter eventNamedPipe =
            NAMED_PIPE_FACTORY.connectAsWriter(workerToolParsedEnvs.getEventPipe());
        OutputStream eventsOutputStream = eventNamedPipe.getOutputStream();
        ClassLoaderCache classLoaderCache = new ClassLoaderCache()) {
      // establish downward protocol type
      DOWNWARD_PROTOCOL_TYPE.writeDelimitedTo(eventsOutputStream);
      Logger logger = Logger.get("");
      logger.cleanHandlers();
      logger.addHandler(new ExternalLogHandler(eventsOutputStream, DOWNWARD_PROTOCOL));

      handleCommands(workerToolParsedEnvs, console, eventsOutputStream, classLoaderCache);

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
    Map<ActionId, SettableFuture<ActionId>> startNextCommandMap = new HashMap<>();

    try (IsolatedEventBus eventBus =
        new DefaultIsolatedEventBus(
            buildUuid,
            eventsOutputStream,
            clock,
            DOWNWARD_PROTOCOL,
            workerToolParsedEnvs.getActionId())) {

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
              ActionId executeCommandActionId = ActionId.of(executeCommand.getActionId());
              BuildJavaCommand buildJavaCommand =
                  BuildJavaCommand.parseDelimitedFrom(commandsInputStream);
              LOG.debug("Start executing command with action id: %s", executeCommandActionId);

              ListenableFuture<Unit> executeCommandFuture =
                  LISTENING_EXECUTOR_SERVICE.submit(
                      () -> {
                        BuildJavaCommandExecutor.executeBuildJavaCommand(
                            classLoaderCache,
                            executeCommandActionId,
                            buildJavaCommand,
                            eventsOutputStream,
                            DOWNWARD_PROTOCOL,
                            eventBus,
                            platform,
                            processExecutor,
                            console,
                            clock);

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
              StartPipelineCommand startPipelineCommand =
                  StartPipelineCommand.parseDelimitedFrom(commandsInputStream);
              ImmutableList<ActionId> actionIds =
                  startPipelineCommand.getActionIdList().stream()
                      .map(ActionId::of)
                      .collect(ImmutableList.toImmutableList());
              PipeliningCommand pipeliningCommand =
                  PipeliningCommand.parseDelimitedFrom(commandsInputStream);
              LOG.debug("Start executing pipelining command with action ids: %s", actionIds);

              Optional<SettableFuture<ActionId>> startNextCommandOptional = Optional.empty();
              if (actionIds.size() > 1) {
                ActionId libraryActionId = actionIds.get(1);
                checkState(!startNextCommandMap.containsKey(libraryActionId));
                SettableFuture<ActionId> startNextCommandFuture = SettableFuture.create();
                startNextCommandMap.put(libraryActionId, startNextCommandFuture);
                startNextCommandOptional = Optional.of(startNextCommandFuture);
              }

              Optional<SettableFuture<ActionId>> finalStartNextCommandOptional =
                  startNextCommandOptional;
              ListenableFuture<Unit> startPipeliningCommandFuture =
                  LISTENING_EXECUTOR_SERVICE.submit(
                      () -> {
                        LOG.debug(
                            "Starting execution of pipelining command with action ids: %s",
                            actionIds);

                        PipeliningJavaCommandExecutor.executePipeliningJavaCommand(
                            actionIds,
                            pipeliningCommand,
                            eventsOutputStream,
                            DOWNWARD_PROTOCOL,
                            eventBus,
                            platform,
                            processExecutor,
                            console,
                            clock,
                            classLoaderCache,
                            finalStartNextCommandOptional);

                        LOG.debug(
                            "Pipelining command has been executed. Action ids: %s", actionIds);

                        return Unit.UNIT;
                      });
              addWorkAdvanceCallback(startPipeliningCommandFuture);

              break;

            case START_NEXT_PIPELINING_COMMAND:
              StartNextPipeliningCommand startNextPipeliningCommand =
                  StartNextPipeliningCommand.parseDelimitedFrom(commandsInputStream);
              ActionId startNextPipeliningCommandActionId =
                  ActionId.of(startNextPipeliningCommand.getActionId());
              LOG.debug(
                  "Received start next pipelining command with action id: %s",
                  startNextPipeliningCommandActionId);

              SettableFuture<ActionId> startNextCommand =
                  checkNotNull(startNextCommandMap.remove(startNextPipeliningCommandActionId));
              // signal to pipelining runner that it could continue with pipelining command
              // execution
              startNextCommand.set(startNextPipeliningCommandActionId);
              break;

            case UNKNOWN:
            case UNRECOGNIZED:
            default:
              throw new IllegalStateException(commandType + " is not supported!");
          }
        }
      }
    }
  }

  private static void addWorkAdvanceCallback(ListenableFuture<Unit> future) {
    Futures.addCallback(
        future,
        new FutureCallback<Unit>() {
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

  private static void logCurrentJavacdState() {
    int activeCount = THREAD_POOL.getActiveCount();
    long completedTaskCount = THREAD_POOL.getCompletedTaskCount();
    int largestPoolSize = THREAD_POOL.getLargestPoolSize();
    long taskCount = THREAD_POOL.getTaskCount();

    PerfStatsTracking.MemoryPerfStatsEvent memory = PerfStatsTracking.getMemoryPerfStatsEvent();
    long totalMemoryBytes = memory.getTotalMemoryBytes();
    long freeMemoryBytes = memory.getFreeMemoryBytes();
    long usedMemory = SizeUnit.BYTES.toMegabytes(totalMemoryBytes - freeMemoryBytes);
    long freeMemory = SizeUnit.BYTES.toMegabytes(freeMemoryBytes);
    long totalMemory = SizeUnit.BYTES.toMegabytes(totalMemoryBytes);
    long maxMemory = SizeUnit.BYTES.toMegabytes(memory.getMaxMemoryBytes());
    long timeSpendInGc = TimeUnit.MILLISECONDS.toSeconds(memory.getTimeSpentInGcMs());
    String pools =
        memory.getCurrentMemoryBytesUsageByPool().entrySet().stream()
            .map(e -> e.getKey() + "=" + SizeUnit.BYTES.toMegabytes(e.getValue()))
            .collect(Collectors.joining(", "));

    LOG.info(
        "Javacd state: executing tasks: %s, completed tasks: %s, largest pool size: %s, task count: %s. Available processors: %s, Time spend in GC: %s seconds, "
            + "Used Memory: %s, Free Memory: %s, Total Memory: %s, Max Memory: %s, Pools: %s",
        activeCount,
        completedTaskCount,
        largestPoolSize,
        taskCount,
        AVAILABLE_PROCESSORS,
        timeSpendInGc,
        usedMemory,
        freeMemory,
        totalMemory,
        maxMemory,
        pools);
  }
}
