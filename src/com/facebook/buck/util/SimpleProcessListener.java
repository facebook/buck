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

import com.google.common.base.Preconditions;

import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;

/**
 * Implementation of {@link ListeningProcessExecutor.ProcessListener} which decodes
 * bytes to and from Java String data and stores the result in memory.
 */
public class SimpleProcessListener extends AbstractCharsetProcessListener {
  private ListeningProcessExecutor.LaunchedProcess process;
  private final CharBuffer stdinToWrite;
  private final StringBuilder stdout;
  private final StringBuilder stderr;

  /**
   * Constructs a {@link SimpleProcessListener} which closes stdin immediately
   * and stores UTF-8 data received on stdout and stderr in memory.
   */
  public SimpleProcessListener() {
    this(null, StandardCharsets.UTF_8);
  }

  /**
   * Constructs a {@link SimpleProcessListener} which writes {@code stdinToWrite}
   * to stdin encoded in UTF-8, closes it, and stores UTF-8 data
   * received on stdout and stderr in memory.
   */
  public SimpleProcessListener(CharSequence stdinToWrite) {
    this(stdinToWrite, StandardCharsets.UTF_8);
  }

  /**
   * Constructs a {@link SimpleProcessListener} which writes {@code stdinToWrite}
   * to stdin encoded using {@code charset}, closes it, and stores data
   * decoded using {@code charset} received on stdout and stderr in memory.
   */
  public SimpleProcessListener(CharSequence stdinToWrite, Charset charset) {
    super(charset);
    if (stdinToWrite != null) {
      this.stdinToWrite = CharBuffer.wrap(stdinToWrite);
    } else {
      this.stdinToWrite = null;
    }
    this.stdout = new StringBuilder();
    this.stderr = new StringBuilder();
  }

  /**
   * Gets the entire contents of stdout sent by the process. Only call this after the
   * process has exited.
   */
  public String getStdout() {
    Preconditions.checkState(process != null, "Process didn't start yet");
    Preconditions.checkState(!process.isRunning(), "Process must not still be running");
    return stdout.toString();
  }

  /**
   * Gets the entire contents of stderr sent by the process. Only call this after the
   * process has exited.
   */
  public String getStderr() {
    Preconditions.checkState(process != null, "Process didn't start yet");
    Preconditions.checkState(!process.isRunning(), "Process must not still be running");
    return stderr.toString();
  }

  @Override
  public void onStart(ListeningProcessExecutor.LaunchedProcess process) {
    this.process = process;
    if (stdinToWrite == null) {
      this.process.closeStdin(/* force */ true);
    }
  }

  @Override
  protected boolean onStdinCharsReady(CharBuffer buffer) {
    if (stdinToWrite == null) {
      buffer.flip();
      return false;
    }

    int bytesAvailable = buffer.remaining();
    boolean wantMore;
    if (stdinToWrite.remaining() <= bytesAvailable) {
      // This updates the position of both 'buffer' and 'stdinToWrite'.
      buffer.put(stdinToWrite);
      wantMore = false;
      process.closeStdin(/* force */ false);
    } else {
      int oldLimit = stdinToWrite.limit();
      stdinToWrite.limit(stdinToWrite.position() + bytesAvailable);
      // Same as above wrt updating position.
      buffer.put(stdinToWrite);
      stdinToWrite.limit(oldLimit);
      wantMore = true;
    }

    buffer.flip();
    return wantMore;
  }

  @Override
  protected void onStdoutChars(CharBuffer buffer, boolean closed, CoderResult coderResult) {
    stdout.append(buffer);
    // Consume the entire buffer.
    buffer.position(buffer.limit());
  }

  @Override
  protected void onStderrChars(CharBuffer buffer, boolean closed, CoderResult coderResult) {
    stderr.append(buffer);
    // Consume the entire buffer.
    buffer.position(buffer.limit());
  }
}
