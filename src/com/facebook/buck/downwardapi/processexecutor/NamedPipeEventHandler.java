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

import com.facebook.buck.downward.model.EndEvent;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downwardapi.namedpipes.SupportsDownwardProtocol;
import com.facebook.buck.downwardapi.processexecutor.context.DownwardApiExecutionContext;
import com.facebook.buck.downwardapi.processexecutor.handlers.EventHandler;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.downwardapi.protocol.InvalidDownwardProtocolException;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.facebook.buck.io.namedpipes.NamedPipeServer;
import com.facebook.buck.io.namedpipes.PipeNotConnectedException;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * Handler that continuously reads events from a named pipe and passes the events to their
 * respective {@link EventHandler}s.
 *
 * <p>It does so by executing the following:
 *
 * <ol>
 *   <li>Read the {@link DownwardProtocol} from the named pipe
 *   <li>Once the protocol is read, read the event type from the named pipe with the given protocol
 *   <li>Once the event type is read, read the event with the associated event type and protocol
 *   <li>Repeat steps 2 to 3 until termination
 * </ol>
 *
 * <p>Termination occurs when one of the following conditions are met:
 *
 * <ol>
 *   <li>Handler receives {@link EndEvent}. This event is written by buck in {@link
 *       #terminateAndWait()} after the subprocess has completed. Clients may also choose to write
 *       this event themselves
 *   <li>Handler encounters an exception before delegating the event to its respective {@link
 *       EventHandler} Examples:
 *       <ul>
 *         <li>{@link IOException} due to named pipe's underlying random access file getting deleted
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
 * handler has finished reading all of the events written by the client (if any), but the subprocess
 * has not completed.
 */
class NamedPipeEventHandler {

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
      DownwardApiProcessExecutor.LOG.info(
          "Trying to establish downward protocol for pipe %s", namedPipeName);
      if (downwardProtocol == null) {
        downwardProtocol = DownwardProtocolType.readProtocol(inputStream);
        DownwardApiProcessExecutor.LOG.info(
            "Starting to read events from named pipe %s with protocol %s",
            namedPipeName, downwardProtocol.getProtocolName());
      }

      processEvents(namedPipeName, inputStream);
      DownwardApiProcessExecutor.LOG.info(
          "Finishing reader thread for pipe: %s; interrupted = %s",
          namedPipeName, Thread.currentThread().isInterrupted());
    } catch (PipeNotConnectedException e) {
      DownwardApiProcessExecutor.LOG.info("Named pipe %s is closed", namedPipeName);
    } catch (IOException e) {
      DownwardApiProcessExecutor.LOG.error(e, "Cannot read from named pipe: %s", namedPipeName);
    } catch (InvalidDownwardProtocolException e) {
      DownwardApiProcessExecutor.LOG.error(e, "Received invalid downward protocol");
    } catch (Exception e) {
      DownwardApiProcessExecutor.LOG.warn(
          e, "Unhandled exception while reading from named pipe: %s", namedPipeName);
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
          DownwardApiProcessExecutor.LOG.info(
              "Received end event for named pipe %s", namedPipeName);
          break;
        }
        DownwardApiProcessExecutor.HANDLER_THREAD_POOL.execute(
            () -> processEvent(eventType, event));
      } catch (PipeNotConnectedException e) {
        DownwardApiProcessExecutor.LOG.info("Named pipe %s is closed", namedPipeName);
        break;
      } catch (IOException e) {
        DownwardApiProcessExecutor.LOG.error(
            e, "Exception during processing events from named pipe: %s", namedPipeName);
        break;
      }
    }
  }

  private void processEvent(EventTypeMessage.EventType eventType, AbstractMessage event) {
    DownwardApiProcessExecutor.LOG.debug(
        "Processing event of type %s in the thread: %s",
        eventType, Thread.currentThread().getName());

    EventHandler<AbstractMessage> eventHandler = EventHandler.getEventHandler(eventType);
    try {
      eventHandler.handleEvent(context, event);
    } catch (Exception e) {
      DownwardApiProcessExecutor.LOG.error(e, "Cannot handle event: %s", event);
    }
  }

  /**
   * Terminate and wait for {@link NamedPipeEventHandler} to finish processing events.
   *
   * <p>In case the standard platform-specific flow for terminating the event handler fails, fall
   * back to terminating the event handler by canceling its associated {@link Future}. Note that
   * canceling the future means that there ay be unread events left in the named pipe.
   */
  void terminateAndWait()
      throws CancellationException, InterruptedException, ExecutionException, TimeoutException {
    checkState(
        namedPipe instanceof NamedPipeServer,
        "DownwardApiProcessExecutor's named pipe must be a server!");
    NamedPipeServer namedPipeServer = (NamedPipeServer) namedPipe;
    maybeSetProtocol(namedPipeServer);
    try {
      namedPipeServer.prepareToClose(done);
    } catch (IOException e) {
      DownwardApiProcessExecutor.LOG.error(
          e, "Failed to prepare to close named pipe. Canceling handler");
      running.map(future -> future.cancel(true));
    }
  }

  private void maybeSetProtocol(NamedPipeServer namedPipeServer) {
    if (namedPipeServer instanceof SupportsDownwardProtocol) {
      ((SupportsDownwardProtocol) namedPipeServer).setProtocol(downwardProtocol);
    }
  }
}
