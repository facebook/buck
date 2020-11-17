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
import com.facebook.buck.downward.model.EndEvent;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downwardapi.namedpipes.DownwardPOSIXNamedPipeFactory;
import com.facebook.buck.downwardapi.processexecutor.handlers.EventHandler;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.downwardapi.protocol.InvalidDownwardProtocolException;
import com.facebook.buck.downwardapi.utils.DownwardApiConstants;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.io.namedpipes.NamedPipe;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.io.namedpipes.PipeNotConnectedException;
import com.facebook.buck.io.namedpipes.windows.WindowsNamedPipeFactory;
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
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.annotation.Nullable;

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

  @VisibleForTesting static final Logger LOG = Logger.get(DownwardApiProcessExecutor.class);

  public static final DownwardApiProcessExecutorFactory FACTORY = Factory.INSTANCE;

  private enum Factory implements DownwardApiProcessExecutorFactory {
    INSTANCE;

    @Override
    public DownwardApiProcessExecutor create(
        ProcessExecutor delegate,
        ConsoleParams consoleParams,
        IsolatedEventBus buckEventBus,
        String actionId) {
      return new DownwardApiProcessExecutor(
          delegate,
          consoleParams,
          buckEventBus,
          actionId,
          NamedPipeFactory.getFactory(
              DownwardPOSIXNamedPipeFactory.INSTANCE, WindowsNamedPipeFactory.INSTANCE));
    }
  }

  @VisibleForTesting static final String HANDLER_THREAD_POOL_NAME = "DownwardApiHandler";

  @VisibleForTesting
  static final ExecutorService HANDLER_THREAD_POOL =
      MostExecutors.newSingleThreadExecutor(HANDLER_THREAD_POOL_NAME);

  private static final ThreadPoolExecutor DOWNWARD_API_READER_THREAD_POOL =
      new ThreadPoolExecutor(
          0,
          Integer.MAX_VALUE,
          1,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          new MostExecutors.NamedThreadFactory("DownwardApiReader"));

  private static final long SHUTDOWN_TIMEOUT = 2;
  private static final TimeUnit SHUTDOWN_TIMEOUT_UNIT = TimeUnit.SECONDS;

  private final String isAnsiTerminal;
  private final String verbosity;
  private final String actionId;
  private final IsolatedEventBus buckEventBus;
  private final NamedPipeFactory namedPipeFactory;

  @VisibleForTesting
  DownwardApiProcessExecutor(
      ProcessExecutor delegate,
      ConsoleParams consoleParams,
      IsolatedEventBus buckEventBus,
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
      cancelHandler();
      closeNamedPipe();
    }

    private void cancelHandler() {
      readerThreadTerminated = true;
      try {
        namedPipeEventHandler.terminateAndWait(SHUTDOWN_TIMEOUT, SHUTDOWN_TIMEOUT_UNIT);
      } catch (CancellationException e) {
        // this is fine. it's just canceled
      } catch (InterruptedException e) {
        LOG.info(e, "Got interrupted while cancelling downward events processing");
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) {
        LOG.warn(e.getCause(), "Exception while cancelling named pipe events processing.");
      } catch (TimeoutException e) {
        LOG.error(
            "Cannot shutdown downward api reader handler for named pipe: '%s'. Timeout: %s",
            namedPipe.getName(), humanReadableFormat(SHUTDOWN_TIMEOUT_UNIT, SHUTDOWN_TIMEOUT));
        readerThreadTerminated = false;
      }
    }

    /**
     * Converts {@link TimeUnit} and {@code duration} into a human readable format.
     *
     * <p>The result will look like:
     *
     * <ul>
     *   <li>5h
     *   <li>7h 15m
     *   <li>6h 50m 15s
     *   <li>2h 5s
     *   <li>0.1s
     * </ul>
     */
    private static String humanReadableFormat(TimeUnit timeUnit, long duration) {
      return Duration.ofMillis(timeUnit.toMillis(duration))
          .toString()
          .substring(2)
          .replaceAll("(\\d[HMS])(?!$)", "$1 ")
          .toLowerCase();
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
  public DownwardApiLaunchedProcess launchProcess(
      ProcessExecutorParams params, ImmutableMap<String, String> context) throws IOException {

    NamedPipeReader namedPipe = namedPipeFactory.createAsReader();
    String namedPipeName = namedPipe.getName();

    NamedPipeEventHandler namedPipeEventHandler =
        getNamedPipeEventHandler(namedPipe, DownwardApiExecutionContext.of(buckEventBus));
    namedPipeEventHandler.runOn(DOWNWARD_API_READER_THREAD_POOL);

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

  /**
   * Handler that continuously reads events from a named pipe and passes the events to their
   * respective {@link EventHandler}s.
   *
   * <p>It does so by executing the following:
   *
   * <ol>
   *   <li>Read the {@link DownwardProtocol} from the named pipe
   *   <li>Once the protocol is read, read the event type from the named pipe with the given
   *       protocol
   *   <li>Once the event type is read, read the event with the associated event type and protocol
   *   <li>Repeat steps 2 to 3 until termination
   * </ol>
   *
   * <p>Termination occurs when one of the following conditions are met:
   *
   * <ol>
   *   <li>Handler receives {@link EndEvent}. This event is written by buck in {@link
   *       #terminateAndWait(long, TimeUnit)} after the subprocess has completed. Clients may also
   *       choose to write this event themselves
   *   <li>Handler encounters an exception before delegating the event to its respective {@link
   *       EventHandler} Examples:
   *       <ul>
   *         <li>{@link IOException} due to named pipe's underlying random access file getting
   *             deleted
   *         <li>{@link InvalidProtocolBufferException} due to client not following the established
   *             protocol
   *       </ul>
   *   <li>
   * </ol>
   *
   * <p>Note that termination does not occur if this handler is able to delegate to an {@link
   * EventHandler}, even if the delegated {@link EventHandler} is not able to process the event.
   *
   * <p>The reads from the named pipe are blocking reads. Consequently, if a client never writes
   * anything into the named pipe, this handler is expected to hang at the read protocol line until
   * buck arbitrarily writes a protocol into the named pipe to write the {@link EndEvent}.
   *
   * <p>Events are always expected to be written after the event type. It follows that hanging at
   * reading the event is unexpected, while hanging at reading the event type is expected if the
   * handler has finished reading all of the events written by the client (if any), but the
   * subprocess has not completed.
   */
  private static class NamedPipeEventHandler {

    private final NamedPipeReader namedPipe;
    private final DownwardApiExecutionContext context;
    private final SettableFuture<Void> done = SettableFuture.create();

    private Optional<Future<?>> running = Optional.empty();
    @Nullable private volatile DownwardProtocol downwardProtocol = null;

    NamedPipeEventHandler(NamedPipeReader namedPipe, DownwardApiExecutionContext context) {
      this.namedPipe = namedPipe;
      this.context = context;
    }

    void runOn(ThreadPoolExecutor threadPool) {
      running = Optional.of(threadPool.submit(this::run));
    }

    private void run() {
      String namedPipeName = namedPipe.getName();
      try (InputStream inputStream = namedPipe.getInputStream()) {
        LOG.info("Trying to establish downward protocol for pipe %s", namedPipeName);
        if (downwardProtocol == null) {
          downwardProtocol = DownwardProtocolType.readProtocol(inputStream);
          LOG.info(
              "Starting to read events from named pipe %s with protocol %s",
              namedPipeName, downwardProtocol.getProtocolName());
        }

        processEvents(namedPipeName, inputStream);
        LOG.info(
            "Finishing reader thread for pipe: %s; interrupted = %s",
            namedPipeName, Thread.currentThread().isInterrupted());
      } catch (PipeNotConnectedException e) {
        LOG.info("Named pipe %s is closed", namedPipeName);
      } catch (IOException e) {
        LOG.error(e, "Cannot read from named pipe: %s", namedPipeName);
      } catch (InvalidDownwardProtocolException e) {
        LOG.error(e, "Received invalid downward protocol");
      } catch (Exception e) {
        LOG.warn(e, "Unhandled exception while reading from named pipe: %s", namedPipeName);
      } finally {
        done.set(null);
      }
    }

    private void processEvents(String namedPipeName, InputStream inputStream) {
      while (true) {
        try {
          EventTypeMessage.EventType eventType = downwardProtocol.readEventType(inputStream);
          AbstractMessage event = downwardProtocol.readEvent(inputStream, eventType);
          if (eventType.equals(EventTypeMessage.EventType.END_EVENT)) {
            LOG.info("Received end event for named pipe %s", namedPipeName);
            break;
          }
          HANDLER_THREAD_POOL.execute(() -> processEvent(eventType, event));
        } catch (PipeNotConnectedException e) {
          LOG.info("Named pipe %s is closed", namedPipeName);
          break;
        } catch (IOException e) {
          LOG.error(e, "Exception during processing events from named pipe: %s", namedPipeName);
          break;
        }
      }
    }

    private void processEvent(EventTypeMessage.EventType eventType, AbstractMessage event) {
      LOG.debug(
          "Processing event of type %s in the thread: %s",
          eventType, Thread.currentThread().getName());

      EventHandler<AbstractMessage> eventHandler = EventHandler.getEventHandler(eventType);
      try {
        eventHandler.handleEvent(context, event);
      } catch (Exception e) {
        LOG.error(e, "Cannot handle event: %s", event);
      }
    }

    /**
     * Terminate and wait for {@link NamedPipeEventHandler} to finish processing events.
     *
     * <p>At this point, the {@link DownwardApiLaunchedProcess} has completed, and all of its {@link
     * NamedPipeWriter} instances should be closed. This method connects a new instance of {@link
     * NamedPipeWriter}, which should be the only connected writer now. This writer writes the
     * {@link EndEvent} into the named pipe, which the {@link NamedPipeEventHandler} will consume as
     * a signal for termination.
     *
     * <p>In case writing the {@link EndEvent} fails, fall back to terminating the event handler by
     * canceling its associated {@link Future}. Note that on POSIX systems, canceling the future
     * results in closing the {@link NamedPipeReader}'s {@link InputStream}, which means that there
     * may be unread events left in the named pipe.
     *
     * <p>Warning: On Windows, the fallback flow is currently the expected flow if the client writes
     * something into the named pipe. Additionally, the issue where events are orphaned in the named
     * pipe is present on windows. Update this javadoc after updating the windows flow
     */
    void terminateAndWait(long timeout, TimeUnit unit)
        throws CancellationException, InterruptedException, ExecutionException, TimeoutException {
      NamedPipeFactory namedPipeFactory =
          NamedPipeFactory.getFactory(
              DownwardPOSIXNamedPipeFactory.INSTANCE, WindowsNamedPipeFactory.INSTANCE);
      try (NamedPipeWriter writer =
              namedPipeFactory.connectAsWriter(Paths.get(namedPipe.getName()));
          OutputStream outputStream = writer.getOutputStream()) {
        writeEndEvent(outputStream);
      } catch (IOException e) {
        // TODO: Windows currently follows this code path. We should fix this
        LOG.error(e, "Failed to write protocol termination event. Canceling handler");
        running.map(future -> future.cancel(true));
      }
      done.get(timeout, unit);
    }

    private void writeEndEvent(OutputStream outputStream) throws IOException {
      // This null check is not perfectly synchronized with the handler, but in practice by the
      // time the subprocess has finished, the handler should have read the protocol from the
      // subprocess already, if any, so this is okay.
      DownwardProtocol protocol = downwardProtocol;
      if (protocol == null) {
        // Client has not written anything into named pipe. Arbitrarily pick binary protocol to
        // communicate with handler
        DownwardProtocolType protocolType = DownwardProtocolType.BINARY;
        protocolType.writeDelimitedTo(outputStream);
        protocol = protocolType.getDownwardProtocol();
      }
      protocol.write(
          EventTypeMessage.newBuilder().setEventType(EventTypeMessage.EventType.END_EVENT).build(),
          EndEvent.getDefaultInstance(),
          outputStream);
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
      DownwardApiProcessExecutorFactory factory, IsolatedEventBus buckEventBus) {
    return this;
  }
}
