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
import com.google.common.collect.ImmutableList;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Implementation of {@link ListeningProcessExecutor.ProcessListener} which decodes bytes to and
 * from Java String data and stores the result in memory.
 */
public class SimpleProcessListener extends AbstractCharsetProcessListener {
  private @Nullable ListeningProcessExecutor.LaunchedProcess process;
  private @Nullable Iterator<CharBuffer> nextStdInToWrite;
  private @Nullable CharBuffer stdInToWrite;
  private final StringBuilder stdout;
  private final StringBuilder stderr;

  /**
   * Constructs a {@link SimpleProcessListener} which closes stdin immediately and stores UTF-8 data
   * received on stdout and stderr in memory.
   */
  public SimpleProcessListener() {
    this((Iterator<CharBuffer>) null, StandardCharsets.UTF_8);
  }

  /**
   * Constructs a {@link SimpleProcessListener} which writes {@code nextStdInToWrite} to stdin
   * encoded in UTF-8, closes it, and stores UTF-8 data received on stdout and stderr in memory.
   */
  public SimpleProcessListener(CharSequence stdinToWrite) {
    this(stdinToWrite, StandardCharsets.UTF_8);
  }

  /**
   * Constructs a {@link SimpleProcessListener} which writes {@code nextStdInToWrite} to stdin
   * encoded using {@code charset}, closes it, and stores data decoded using {@code charset}
   * received on stdout and stderr in memory.
   */
  public SimpleProcessListener(CharSequence stdinToWrite, Charset charset) {
    this(ImmutableList.of(CharBuffer.wrap(stdinToWrite)).iterator(), charset);
  }

  /**
   * Constructs a {@link SimpleProcessListener} which writes data from {@code nextStdInToWrite} to
   * stdin encoded using {@code charset}, closes it, and stores data decoded using {@code charset}
   * received on stdout and stderr in memory.
   */
  public SimpleProcessListener(@Nullable Iterator<CharBuffer> stdinToWrite, Charset charset) {
    super(charset);
    this.process = null;
    this.nextStdInToWrite = stdinToWrite;
    this.stdout = new StringBuilder();
    this.stderr = new StringBuilder();
  }

  /**
   * Gets the entire contents of stdout sent by the process. Only call this after the process has
   * exited.
   */
  public String getStdout() {
    Preconditions.checkState(process != null, "Process didn't start yet");
    Preconditions.checkState(!process.isRunning(), "Process must not still be running");
    return stdout.toString();
  }

  /**
   * Gets the entire contents of stderr sent by the process. Only call this after the process has
   * exited.
   */
  public String getStderr() {
    Preconditions.checkState(process != null, "Process didn't start yet");
    Preconditions.checkState(!process.isRunning(), "Process must not still be running");
    return stderr.toString();
  }

  @Override
  public void onStart(ListeningProcessExecutor.LaunchedProcess process) {
    this.process = process;
    if (nextStdInToWrite == null) {
      this.process.closeStdin(/* force */ false);
    }
  }

  protected static boolean pushBytes(CharBuffer from, CharBuffer to) {
    int bytesAvailable = to.remaining();

    if (from.remaining() <= bytesAvailable) {
      // This updates the position of both 'buffer' and 'nextStdInToWrite'.
      to.put(from);
      return false;
    }

    int oldLimit = from.limit();
    from.limit(from.position() + bytesAvailable);
    // Same as above wrt updating position.
    to.put(from);
    from.limit(oldLimit);
    return true;
  }

  @Override
  protected boolean onStdinCharsReady(CharBuffer buffer) {
    if (nextStdInToWrite == null) {
      buffer.flip();
      return false;
    }

    if (stdInToWrite == null && nextStdInToWrite.hasNext()) {
      stdInToWrite = nextStdInToWrite.next();
    }

    if (stdInToWrite != null) {
      boolean hasMoreToWrite = pushBytes(stdInToWrite, buffer);
      if (!hasMoreToWrite) {
        stdInToWrite = nextStdInToWrite.hasNext() ? nextStdInToWrite.next() : null;
      }
    }

    if (stdInToWrite == null) {
      Objects.requireNonNull(process, "Process didn't start yet").closeStdin(/* force */ false);
      nextStdInToWrite = null;
    }

    buffer.flip();
    return stdInToWrite != null;
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
