/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.util;

import com.facebook.buck.timing.SettableFakeClock;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.Multimap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * Fake implementation of {@link ListeningProcessExecutor} for tests.
 */
public class FakeListeningProcessExecutor extends ListeningProcessExecutor {
  private final Function<ProcessExecutorParams, Collection<FakeListeningProcessState>>
    processStatesFunction;
  private final SettableFakeClock clock;

  public FakeListeningProcessExecutor(
      Multimap<ProcessExecutorParams, FakeListeningProcessState> processStates) {
    this(Functions.forMap(processStates.asMap()), new SettableFakeClock(0, 0));
  }

  public FakeListeningProcessExecutor(
      Multimap<ProcessExecutorParams, FakeListeningProcessState> processStates,
      SettableFakeClock clock) {
    this(Functions.forMap(processStates.asMap()), clock);
  }

  public FakeListeningProcessExecutor(
      Function<ProcessExecutorParams, Collection<FakeListeningProcessState>>
        processStatesFunction) {
    this(processStatesFunction, new SettableFakeClock(0, 0));
  }

  public FakeListeningProcessExecutor(
      Function<ProcessExecutorParams, Collection<FakeListeningProcessState>>
        processStatesFunction,
      SettableFakeClock clock) {
    this.processStatesFunction = processStatesFunction;
    this.clock = clock;
  }

  private static class FakeLaunchedProcessImpl implements LaunchedProcess {
    public final ProcessListener listener;
    public final Iterator<FakeListeningProcessState> states;
    public final SettableFakeClock clock;
    public final long processExecTimeNanos;
    public final ByteBuffer stdinBuffer;
    public boolean processingStates;
    public FakeListeningProcessState currentState;
    public ByteArrayOutputStream stdinBytes;
    public WritableByteChannel stdinBytesChannel;
    public boolean stdinClosed;
    public boolean wantsWrite;
    public int exitCode;
    public long startTimeNanos;
    public long processTimeNanos;

    public FakeLaunchedProcessImpl(
        ProcessListener listener,
        Iterator<FakeListeningProcessState> states,
        SettableFakeClock clock,
        long processExecTimeNanos) {
      this.listener = listener;
      this.states = states;
      this.clock = clock;
      this.processExecTimeNanos = processExecTimeNanos;
      this.stdinBuffer = ByteBuffer.allocate(BUFFER_CAPACITY);
      this.stdinBytes = new ByteArrayOutputStream();
      this.stdinBytesChannel = Channels.newChannel(stdinBytes);
      this.exitCode = -1;
      this.startTimeNanos = this.clock.nanoTime();
    }

    public void processAllStates() {
      if (processingStates) {
        // Don't recurse.
        return;
      }

      if (currentState != null) {
        if (!processState(currentState)) {
          return;
        } else {
          currentState = null;
        }
      }

      while (states.hasNext()) {
        currentState = states.next();
        if (!processState(currentState)) {
          return;
        } else {
          currentState = null;
        }
      }
    }

    private boolean processState(FakeListeningProcessState state) {
      processingStates = true;
      boolean result = true;
      switch (state.getType()) {
        case EXPECT_STDIN:
          if (stdinClosed) {
            throw new RuntimeException("stdin is closed");
          }
          if (!wantsWrite) {
            result = false;
            break;
          }
          while (wantsWrite) {
            wantsWrite = listener.onStdinReady(stdinBuffer);
            try {
              stdinBytesChannel.write(stdinBuffer);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
            stdinBuffer.clear();
          }
          if (!ByteBuffer.wrap(stdinBytes.toByteArray()).equals(state.getExpectedStdin().get())) {
            throw new RuntimeException("Did not reach expected stdin state");
          }
          stdinBytes = new ByteArrayOutputStream();
          stdinBytesChannel = Channels.newChannel(stdinBytes);
          break;
        case EXPECT_STDIN_CLOSED:
          if (!stdinClosed) {
            result = false;
            break;
          }
          break;
        case STDOUT:
          while (state.getStdout().get().hasRemaining()) {
            listener.onStdout(state.getStdout().get(), false);
          }
          break;
        case STDERR:
          while (state.getStderr().get().hasRemaining()) {
            listener.onStderr(state.getStderr().get(), false);
          }
          break;
        case WAIT:
          long stateWaitTime = state.getWaitNanos().get();
          if (clock.nanoTime() < startTimeNanos + processTimeNanos + stateWaitTime) {
            result = false;
            break;
          }
          processTimeNanos += stateWaitTime;
          break;
        case EXIT:
          exitCode = state.getExitCode().get();
          ByteBuffer empty = ByteBuffer.allocate(0);
          listener.onStdout(empty, true);
          listener.onStderr(empty, true);
          listener.onExit(exitCode);
          break;
      }
      processingStates = false;
      return result;
    }

    @Override
    public void wantWrite() {
      this.wantsWrite = true;
      processAllStates();
    }

    @Override
    public void writeStdin(ByteBuffer buffer) {
      try {
        stdinBytesChannel.write(buffer);
        processAllStates();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void closeStdin(boolean force) {
      stdinClosed = true;
      processAllStates();
    }

    @Override
    public boolean hasPendingWrites() {
      return stdinBytes.size() > 0;
    }

    @Override
    public boolean isRunning() {
      return states.hasNext();
    }
  }

  @Override
  public LaunchedProcess launchProcess(
      ProcessExecutorParams params,
      final ProcessListener listener) {
    Collection<FakeListeningProcessState> fakeProcessStates = processStatesFunction.apply(params);
    long processExecTimeNanos = 0;
    for (FakeListeningProcessState state : fakeProcessStates) {
      if (state.getType() == FakeListeningProcessState.Type.WAIT) {
        processExecTimeNanos += state.getWaitNanos().get();
      }
    }
    FakeLaunchedProcessImpl process = new FakeLaunchedProcessImpl(
        listener,
        processStatesFunction.apply(params).iterator(),
        clock,
        processExecTimeNanos);
    listener.onStart(process);
    return process;
  }

  @Override
  public int waitForProcess(LaunchedProcess process, long timeout, TimeUnit timeUnit)
    throws InterruptedException {
    FakeLaunchedProcessImpl processImpl = (FakeLaunchedProcessImpl) process;
    clock.advanceTimeNanos(Math.min(processImpl.processExecTimeNanos, timeUnit.toNanos(timeout)));
    processImpl.processAllStates();
    if (processImpl.isRunning()) {
      return Integer.MIN_VALUE;
    } else {
      return processImpl.exitCode;
    }
  }

  @Override
  public void destroyProcess(LaunchedProcess process, boolean force) {
    FakeLaunchedProcessImpl processImpl = (FakeLaunchedProcessImpl) process;
    while (processImpl.states.hasNext()) {
      processImpl.states.next();
    }
    processImpl.currentState = null;
  }
}
