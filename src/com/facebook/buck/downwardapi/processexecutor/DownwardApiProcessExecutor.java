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

package com.facebook.buck.downwardapi.processexecutor;

import static com.google.common.base.Preconditions.checkState;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downwardapi.processexecutor.handlers.EventHandler;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.namedpipes.NamedPipe;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.util.ConsoleParams;
import com.facebook.buck.util.DelegateLaunchedProcess;
import com.facebook.buck.util.DelegateProcessExecutor;
import com.facebook.buck.util.DownwardApiProcessExecutorFactory;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.AbstractMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Runs external process with DownwardAPI protocol:
 *
 * <p>Buck Downward API allows invoked tools to send the following events back to buck client:
 * <li>Console event - event which goes into buck console output (ex. warning level console event
 *     will be displayed with yellowish color font in the buck’s superconsole).
 * <li>Log event - event which goes into buck logging file.
 * <li>Chrome trace event - event which goes into tracing file visible with Google Chrome trace
 *     viewer.
 * <li>Step event - event which goes into chrome trace, prints into superconsole as a current
 *     executing step, goes into Scuba `buck_build_steps` table, …
 *
 *     <p>Also Buck Downward API provides a way to send information from buck to execution tool such
 *     as verbosity level, whether buck supports ANSI escape sequences, buck build UUID, action id.
 */
public class DownwardApiProcessExecutor extends DelegateProcessExecutor {

  private static final Logger LOG = Logger.get(DownwardApiProcessExecutor.class);

  public static final DownwardApiProcessExecutorFactory FACTORY = Factory.INSTANCE;

  private enum Factory implements DownwardApiProcessExecutorFactory {
    INSTANCE;

    @Override
    public DownwardApiProcessExecutor create(
        ProcessExecutor delegate,
        ConsoleParams consoleParams,
        BuckEventBus buckEventBus,
        String actionId) {
      return new DownwardApiProcessExecutor(
          delegate, consoleParams, buckEventBus, actionId, NamedPipeFactory.getFactory());
    }
  }

  private static final ThreadPoolExecutor DOWNWARD_API_THREAD_POOL =
      new ThreadPoolExecutor(
          0,
          Integer.MAX_VALUE,
          1,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          new MostExecutors.NamedThreadFactory("DownwardApi"));

  private static final long SHUTDOWN_TIMEOUT = 100;
  private static final TimeUnit SHUTDOWN_TIMEOUT_UNIT = TimeUnit.MILLISECONDS;

  private final String isAnsiTerminal;
  private final String verbosity;
  private final String actionId;
  private final BuckEventBus buckEventBus;
  private final NamedPipeFactory namedPipeFactory;

  @VisibleForTesting
  DownwardApiProcessExecutor(
      ProcessExecutor delegate,
      ConsoleParams consoleParams,
      BuckEventBus buckEventBus,
      String actionId,
      NamedPipeFactory namedPipeFactory) {
    super(delegate);
    this.isAnsiTerminal = consoleParams.isAnsiEscapeSequencesEnabled();
    this.verbosity = consoleParams.getVerbosity();
    this.buckEventBus = buckEventBus;
    this.actionId = actionId;
    this.namedPipeFactory = namedPipeFactory;
  }

  /** Process launched inside the {@link DownwardApiProcessExecutor} */
  private static class DownwardApiLaunchedProcess extends DelegateLaunchedProcess {

    private final NamedPipe namedPipe;
    private final NamedPipeEventHandler namedPipeEventHandler;
    private boolean readerThreadTerminated = false;

    public DownwardApiLaunchedProcess(
        LaunchedProcess delegate,
        NamedPipe namedPipe,
        NamedPipeEventHandler namedPipeEventHandler) {
      super(delegate);
      this.namedPipe = namedPipe;
      this.namedPipeEventHandler = namedPipeEventHandler;
    }

    @Override
    public void close() {
      super.close();
      closeNamedPipe();
      cancelHandler();
    }

    private void cancelHandler() {
      readerThreadTerminated = true;
      try {
        namedPipeEventHandler.terminateAndWait(SHUTDOWN_TIMEOUT, SHUTDOWN_TIMEOUT_UNIT);
      } catch (CancellationException e) {
        // this is fine. it's just canceled
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) {
        LOG.warn(e.getCause(), "Exception while cancelling named pipe events processing.");
      } catch (TimeoutException e) {
        LOG.error(
            "Cannot shutdown downward api reader handler for named pipe: %s", namedPipe.getName());
        readerThreadTerminated = false;
      }
    }

    private void closeNamedPipe() {
      try {
        namedPipe.close();
      } catch (IOException e) {
        LOG.error(e, "Cannot close named pipe: %s", namedPipe.getName());
      }
    }

    @VisibleForTesting
    boolean isReaderThreadTerminated() {
      return readerThreadTerminated;
    }
  }

  @Override
  public LaunchedProcess launchProcess(
      ProcessExecutorParams params, ImmutableMap<String, String> context) throws IOException {

    NamedPipe namedPipe = namedPipeFactory.create();
    String namedPipeName = namedPipe.getName();

    NamedPipeEventHandler namedPipeEventHandler =
        getNamedPipeEventHandler(namedPipeName, DownwardApiExecutionContext.of(buckEventBus));
    namedPipeEventHandler.runOn(DOWNWARD_API_THREAD_POOL);

    ProcessExecutorParams updatedParams =
        ProcessExecutorParams.builder()
            .from(params)
            .setEnvironment(buildEnvs(params, namedPipeName))
            .build();
    LaunchedProcess launchedProcess = getDelegate().launchProcess(updatedParams, context);

    return new DownwardApiLaunchedProcess(launchedProcess, namedPipe, namedPipeEventHandler);
  }

  private ImmutableMap<String, String> buildEnvs(
      ProcessExecutorParams params, String namedPipeName) {
    ImmutableMap<String, String> envs = params.getEnvironment().orElseGet(ImmutableMap::of);
    ImmutableMap<String, String> extraEnvs = downwardApiEnvs(namedPipeName);

    Sets.SetView<String> intersection = Sets.intersection(envs.keySet(), extraEnvs.keySet());
    if (!intersection.isEmpty()) {
      LOG.warn(
          "Env variables have the same keys [%s]. "
              + "Replacing these keys with downward api related values.",
          intersection);
      envs =
          envs.entrySet().stream()
              .filter(e -> !intersection.contains(e.getKey()))
              .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    return ImmutableMap.<String, String>builderWithExpectedSize(envs.size() + extraEnvs.size())
        .putAll(envs)
        .putAll(extraEnvs)
        .build();
  }

  private ImmutableMap<String, String> downwardApiEnvs(String namedPipeName) {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builderWithExpectedSize(5);
    builder.put("BUCK_VERBOSITY", verbosity);
    builder.put("BUCK_ANSI_ENABLED", isAnsiTerminal);
    builder.put("BUCK_BUILD_UUID", buckEventBus.getBuildId().toString());
    builder.put("BUCK_ACTION_ID", actionId);
    builder.put("BUCK_EVENT_PIPE", namedPipeName);
    return builder.build();
  }

  private NamedPipeEventHandler getNamedPipeEventHandler(
      String namedPipeName, DownwardApiExecutionContext context) {
    return new NamedPipeEventHandler(namedPipeName, context, namedPipeFactory);
  }

  private static class NamedPipeEventHandler {

    private final NamedPipeFactory namedPipeFactory;
    private final String namedPipeName;
    private final DownwardApiExecutionContext context;
    private final SettableFuture<Void> done = SettableFuture.create();

    private Optional<Future<?>> running = Optional.empty();

    NamedPipeEventHandler(
        String namedPipeName,
        DownwardApiExecutionContext context,
        NamedPipeFactory namedPipeFactory) {
      this.namedPipeFactory = namedPipeFactory;
      this.namedPipeName = namedPipeName;
      this.context = context;
    }

    void runOn(ThreadPoolExecutor threadPool) {
      running = Optional.of(threadPool.submit(this::run));
    }

    void run() {
      try (NamedPipe namedPipe = namedPipeFactory.connect(Paths.get(namedPipeName));
          InputStream inputStream = namedPipe.getInputStream()) {
        LOG.info("Starting to read events from named pipe: %s", namedPipeName);
        DownwardProtocol downwardProtocol = null;
        while (!Thread.currentThread().isInterrupted()) {
          try {
            if (downwardProtocol == null) {
              downwardProtocol = DownwardProtocolType.readProtocol(inputStream);
            }
            EventTypeMessage.EventType eventType = downwardProtocol.readEventType(inputStream);
            AbstractMessage event = downwardProtocol.readEvent(inputStream, eventType);
            EventHandler<AbstractMessage> eventHandler = EventHandler.getEventHandler(eventType);
            try {
              eventHandler.handleEvent(context, event);
            } catch (Exception e) {
              LOG.error(e, "Cannot handle event: %s", event);
            }
          } catch (IOException e) {
            LOG.error(e, "Exception during processing events from named pipe: %s", namedPipeName);
          }
        }
        LOG.info("Finishing reader thread for pipe: %s", namedPipeName);
      } catch (IOException e) {
        LOG.error(e, "Cannot read from named pipe: %s", namedPipeName);
      } finally {
        done.set(null);
      }
    }

    void terminateAndWait(long timeout, TimeUnit unit)
        throws CancellationException, InterruptedException, ExecutionException, TimeoutException {
      running.map(future -> future.cancel(true));
      done.get(timeout, unit);
    }
  }

  @Override
  public DownwardApiExecutionResult execute(
      LaunchedProcess launchedProcess,
      Set<Option> options,
      Optional<String> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException {

    checkState(launchedProcess instanceof DownwardApiLaunchedProcess);
    DownwardApiLaunchedProcess process = (DownwardApiLaunchedProcess) launchedProcess;

    Result result;
    try {
      result = getDelegate().execute(process.delegate, options, stdin, timeOutMs, timeOutHandler);
    } finally {
      launchedProcess.close();
    }

    return new DownwardApiExecutionResult(
        result.getExitCode(),
        result.isTimedOut(),
        result.getStdout(),
        result.getStderr(),
        result.getCommand(),
        process.isReaderThreadTerminated());
  }

  @Override
  public ProcessExecutor withDownwardAPI(
      DownwardApiProcessExecutorFactory factory, BuckEventBus buckEventBus) {
    return this;
  }
}
