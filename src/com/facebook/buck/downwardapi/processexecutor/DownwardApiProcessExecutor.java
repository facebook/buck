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
import com.facebook.buck.downwardapi.namedpipes.DownwardPOSIXNamedPipeFactory;
import com.facebook.buck.downwardapi.processexecutor.context.DownwardApiExecutionContext;
import com.facebook.buck.downwardapi.utils.DownwardApiConstants;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.facebook.buck.io.namedpipes.windows.WindowsNamedPipeFactory;
import com.facebook.buck.util.ConsoleParams;
import com.facebook.buck.util.DelegateProcessExecutor;
import com.facebook.buck.util.DownwardApiProcessExecutorFactory;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.timing.Clock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
        IsolatedEventBus buckEventBus,
        String actionId,
        Clock clock) {
      return new DownwardApiProcessExecutor(
          delegate,
          consoleParams,
          buckEventBus,
          actionId,
          NamedPipeFactory.getFactory(
              DownwardPOSIXNamedPipeFactory.INSTANCE, WindowsNamedPipeFactory.INSTANCE),
          clock);
    }
  }

  @VisibleForTesting static final String HANDLER_THREAD_POOL_NAME = "DownwardApiHandler";

  @VisibleForTesting
  public static final ExecutorService HANDLER_THREAD_POOL =
      MostExecutors.newSingleThreadExecutor(HANDLER_THREAD_POOL_NAME);

  private static final ThreadPoolExecutor DOWNWARD_API_READER_THREAD_POOL =
      new ThreadPoolExecutor(
          0,
          Integer.MAX_VALUE,
          1,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          new MostExecutors.NamedThreadFactory("DownwardApiReader"));

  private final String isAnsiTerminal;
  private final String verbosity;
  private final String actionId;
  private final IsolatedEventBus buckEventBus;
  private final NamedPipeFactory namedPipeFactory;
  private final Clock clock;

  @VisibleForTesting
  DownwardApiProcessExecutor(
      ProcessExecutor delegate,
      ConsoleParams consoleParams,
      IsolatedEventBus buckEventBus,
      String actionId,
      NamedPipeFactory namedPipeFactory,
      Clock clock) {
    super(delegate);
    this.isAnsiTerminal = consoleParams.isAnsiEscapeSequencesEnabled();
    this.verbosity = consoleParams.getVerbosity();
    this.buckEventBus = buckEventBus;
    this.actionId = actionId;
    this.namedPipeFactory = namedPipeFactory;
    this.clock = clock;
  }

  @Override
  public ProcessExecutor.LaunchedProcess launchProcess(
      ProcessExecutorParams params, ImmutableMap<String, String> context) throws IOException {
    NamedPipeReader namedPipe = namedPipeFactory.createAsReader();
    String namedPipeName = namedPipe.getName();

    NamedPipeEventHandler namedPipeEventHandler =
        getNamedPipeEventHandler(namedPipe, DownwardApiExecutionContext.of(buckEventBus, clock));
    namedPipeEventHandler.runOn(DOWNWARD_API_READER_THREAD_POOL);

    ProcessExecutorParams updatedParams =
        ProcessExecutorParams.builder()
            .from(params)
            .setEnvironment(buildEnvs(params, namedPipeName))
            .build();
    ProcessExecutor.LaunchedProcess launchedProcess =
        getDelegate().launchProcess(updatedParams, context);

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
    builder.put(DownwardApiConstants.ENV_VERBOSITY, verbosity);
    builder.put(DownwardApiConstants.ENV_ANSI_ENABLED, isAnsiTerminal);
    builder.put(DownwardApiConstants.ENV_BUILD_UUID, buckEventBus.getBuildId().toString());
    builder.put(DownwardApiConstants.ENV_ACTION_ID, actionId);
    builder.put(DownwardApiConstants.ENV_EVENT_PIPE, namedPipeName);
    return builder.build();
  }

  private NamedPipeEventHandler getNamedPipeEventHandler(
      NamedPipeReader namedPipe, DownwardApiExecutionContext context) {
    return new NamedPipeEventHandler(namedPipe, context);
  }

  @Override
  public DownwardApiExecutionResult execute(
      ProcessExecutor.LaunchedProcess launchedProcess,
      Set<ProcessExecutor.Option> options,
      Optional<ProcessExecutor.Stdin> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException {

    checkState(launchedProcess instanceof DownwardApiLaunchedProcess);
    DownwardApiLaunchedProcess process = (DownwardApiLaunchedProcess) launchedProcess;

    Result result;
    try {
      result =
          getDelegate().execute(process.getDelegate(), options, stdin, timeOutMs, timeOutHandler);
    } catch (InterruptedException e) {
      LOG.info(e, "Downward process interrupted during execution");
      throw e;
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
      DownwardApiProcessExecutorFactory factory,
      IsolatedEventBus buckEventBus,
      String actionId,
      Clock clock) {
    return this;
  }
}
