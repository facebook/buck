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

import com.facebook.buck.core.util.log.Logger;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.zaxxer.nuprocess.NuAbstractProcessHandler;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessBuilder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Replacement for {@link ProcessBuilder} which provides an asynchronous callback interface to
 * notify the caller on a background thread when a process starts, performs I/O, or exits.
 *
 * <p>Unlike {@link ProcessExecutor}, this does not automatically forward output to {@link Console}
 * formatted with ANSI escapes.
 */
public class ListeningProcessExecutor {

  private static final Logger LOG = Logger.get(ListeningProcessExecutor.class);

  /**
   * Callback API to notify the caller on a background thread when a process starts, exits, has
   * stdout or stderr bytes to read, or is ready to receive bytes on stdin.
   */
  public interface ProcessListener {
    /** Called just after the process starts. */
    void onStart(LaunchedProcess process);

    /** Called just after the process exits. */
    void onExit(int exitCode);

    /**
     * Called when the process writes bytes to stdout.
     *
     * <p>Before this method returns, you must set {@code buffer.position()} to indicate how many
     * bytes you have consumed.
     *
     * <p>If you do not consume the entire buffer, any remaining bytes will be passed back to you
     * upon the next invocation (for example, when implementing a UTF-8 decoder which might contain
     * a byte sequence spanning multiple reads).
     *
     * <p>If {@code closed} is {@code true}, then stdout has been closed and no more bytes will be
     * received.
     */
    void onStdout(ByteBuffer buffer, boolean closed);

    /**
     * Called when the process writes bytes to stderr.
     *
     * <p>Before this method returns, you must set {@code buffer.position()} to indicate how many
     * bytes you have consumed.
     *
     * <p>If you do not consume the entire buffer, any remaining bytes will be passed back to you
     * upon the next invocation (for example, when implementing a UTF-8 decoder which might contain
     * a byte sequence spanning multiple reads).
     *
     * <p>If {@code closed} is {@code true}, then stdout has been closed and no more bytes will be
     * received.
     */
    void onStderr(ByteBuffer buffer, boolean closed);

    /**
     * Called when the process is ready to receive bytes on stdin.
     *
     * <p>Before this method returns, you must set the {@code buffer}'s {@link ByteBuffer#position()
     * position} and {@link ByteBuffer#limit() limit} (for example, by invoking {@link
     * ByteBuffer#flip()}) to indicate how much data is in the buffer before returning from this
     * method.
     *
     * <p>You must first call {@link LaunchedProcess#wantWrite()} at least once before this method
     * will be invoked.
     *
     * <p>If not all of the data needed to be written will fit in {@code buffer}, you can return
     * {@code true} to indicate that you would like to write more data.
     *
     * <p>Otherwise, return {@code false} if you have no more data to write to stdin. (You can
     * always invoke {@link LaunchedProcess#wantWrite()} any time in the future.
     */
    boolean onStdinReady(ByteBuffer buffer);
  }

  /** Represents a process which was launched by a {@link ListeningProcessExecutor}. */
  public interface LaunchedProcess {
    /** The capacity of each I/O buffer, in bytes. */
    int BUFFER_CAPACITY = NuProcess.BUFFER_CAPACITY;

    /**
     * Invoke this to indicate you wish to write to the launched process's stdin.
     *
     * <p>Your {@link ProcessListener#onStdinReady(ByteBuffer)} method will be invoked
     * asynchronously when the process is ready to receive data on stdin.
     */
    void wantWrite();

    /**
     * Invoke this to directly write data to the launched process's stdin. This method does not
     * block, and will enqueue the buffer to be written to the launched process's stdin at a later
     * date.
     *
     * <p>If you need to be notified when the write to stdin completes, use {@link #wantWrite()} and
     * {@link ProcessListener#onStdinReady(ByteBuffer)} instead.
     */
    void writeStdin(ByteBuffer buffer);

    /**
     * Closes the stdin of the process. Call this if the process expects stdin to be closed before
     * it writes to stdout.
     *
     * <p>If {@code force} is {@code true}, then pending writes to stdin are discarded. Otherwise,
     * waits for pending writes to flush, then closes stdin.
     */
    void closeStdin(boolean force);

    /**
     * Returns {@code true} if the process has any data queued to write to stdin, {@code false}
     * otherwise.
     */
    boolean hasPendingWrites();

    /** Returns {@code true} if the process is running, {@code false} otherwise. */
    boolean isRunning();
  }

  private static class LaunchedProcessImpl implements LaunchedProcess {
    public final NuProcess nuProcess;
    public final ProcessExecutorParams params;

    public LaunchedProcessImpl(NuProcess nuProcess, ProcessExecutorParams params) {
      this.nuProcess = nuProcess;
      this.params = params;
    }

    @Override
    public void wantWrite() {
      nuProcess.wantWrite();
    }

    @Override
    public void writeStdin(ByteBuffer buffer) {
      nuProcess.writeStdin(buffer);
    }

    @Override
    public void closeStdin(boolean force) {
      nuProcess.closeStdin(force);
    }

    @Override
    public boolean hasPendingWrites() {
      return nuProcess.hasPendingWrites();
    }

    @Override
    public boolean isRunning() {
      return nuProcess.isRunning();
    }
  }

  private static class ListeningProcessHandler extends NuAbstractProcessHandler {
    private final ProcessExecutorParams params;
    private final ProcessListener listener;
    @Nullable public LaunchedProcessImpl process;

    public ListeningProcessHandler(ProcessListener listener, ProcessExecutorParams params) {
      this.listener = listener;
      this.params = params;
    }

    @Override
    public void onPreStart(NuProcess process) {
      this.process = new LaunchedProcessImpl(process, params);
    }

    @Override
    public void onStart(NuProcess process) {
      Preconditions.checkNotNull(this.process);
      Preconditions.checkState(this.process.nuProcess == process);
      listener.onStart(this.process);
    }

    @Override
    public void onExit(int exitCode) {
      listener.onExit(exitCode);
    }

    @Override
    public void onStdout(ByteBuffer buffer, boolean closed) {
      listener.onStdout(buffer, closed);
    }

    @Override
    public void onStderr(ByteBuffer buffer, boolean closed) {
      listener.onStderr(buffer, closed);
    }

    @Override
    public boolean onStdinReady(ByteBuffer buffer) {
      return listener.onStdinReady(buffer);
    }
  }

  private final ProcessRegistry processRegistry;

  public ListeningProcessExecutor() {
    processRegistry = ProcessRegistry.getInstance();
  }

  /**
   * Launches a process and asynchronously sends notifications to {@code listener} on a background
   * thread when the process starts, has I/O, or exits.
   */
  public LaunchedProcess launchProcess(ProcessExecutorParams params, ProcessListener listener)
      throws IOException {
    LOG.debug("Launching process with params %s", params);

    ListeningProcessHandler processHandler = new ListeningProcessHandler(listener, params);

    // Unlike with Java's ProcessBuilder, we don't need special param escaping for Win32 platforms.
    NuProcessBuilder processBuilder = new NuProcessBuilder(processHandler, params.getCommand());
    if (params.getEnvironment().isPresent()) {
      processBuilder.environment().clear();
      processBuilder.environment().putAll(params.getEnvironment().get());
    }
    if (params.getDirectory().isPresent()) {
      processBuilder.setCwd(params.getDirectory().get());
    }

    NuProcess process = BgProcessKiller.startProcess(processBuilder);
    if (process == null) {
      throw new IOException(String.format("Could not start process with params %s", params));
    }
    LOG.debug("Successfully launched process %s", process);

    // This should be set by onPreStart().
    Preconditions.checkState(processHandler.process != null);
    processRegistry.registerProcess(processHandler.process.nuProcess, params, ImmutableMap.of());
    return processHandler.process;
  }

  /**
   * Blocks the calling thread until either the process exits or the timeout expires, whichever is
   * first.
   *
   * @return {@code Integer.MIN_VALUE} if the timeout expired or the process failed to start, or the
   *     exit code of the process otherwise.
   */
  public int waitForProcess(LaunchedProcess process, long timeout, TimeUnit timeUnit)
      throws InterruptedException {
    LOG.debug("Waiting for process %s timeout %d %s", process, timeout, timeUnit);
    Preconditions.checkArgument(process instanceof LaunchedProcessImpl);
    LaunchedProcessImpl processImpl = (LaunchedProcessImpl) process;
    int exitCode = processImpl.nuProcess.waitFor(timeout, timeUnit);
    LOG.debug("Wait for process returned %d", exitCode);
    return exitCode;
  }

  /**
   * Blocks the calling thread until the process exits.
   *
   * @return the exit code of the process.
   * @throws IOException if the process failed to start.
   */
  public int waitForProcess(LaunchedProcess process) throws InterruptedException, IOException {
    long infiniteWait = 0;
    int exitCode = waitForProcess(process, infiniteWait, TimeUnit.SECONDS);
    if (exitCode == Integer.MIN_VALUE) {
      // Specifying 0 for timeout guarantees that the wait will not time out. This way we know that
      // an exit code equal to Integer.MIN_VALUE must mean that the process failed to start.
      Preconditions.checkArgument(process instanceof LaunchedProcessImpl);
      LaunchedProcessImpl processImpl = (LaunchedProcessImpl) process;
      throw new IOException(
          String.format("Failed to start process %s", processImpl.params.getCommand()));
    }
    return exitCode;
  }

  /**
   * Destroys a process. If {@code force} is {@code true}, then forcibly destroys the process in a
   * way it cannot ignore.
   */
  public void destroyProcess(LaunchedProcess process, boolean force) {
    LOG.debug("Destroying process %s (force %s)", process, force);
    Preconditions.checkArgument(process instanceof LaunchedProcessImpl);
    LaunchedProcessImpl processImpl = (LaunchedProcessImpl) process;
    processImpl.nuProcess.destroy(force);
  }
}
