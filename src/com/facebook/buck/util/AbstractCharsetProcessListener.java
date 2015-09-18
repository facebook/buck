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

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

/**
 * Implementation of {@link ListeningProcessExecutor.ProcessListener} which decodes
 * bytes to and from Java String data.
 */
public abstract class AbstractCharsetProcessListener
    implements ListeningProcessExecutor.ProcessListener {
  private final ListeningCharsetEncoder stdinEncoder;
  private final ListeningCharsetDecoder stdoutDecoder;
  private final ListeningCharsetDecoder stderrDecoder;

  private class StdinEncoderListener implements ListeningCharsetEncoder.EncoderListener {
    @Override
    public boolean onStdinReady(CharBuffer buffer) {
      return onStdinCharsReady(buffer);
    }

    @Override
    public void onEncoderError(CoderResult result) {
      onStdinEncoderError(result);
    }
  }

  private class StdoutDecoderListener implements ListeningCharsetDecoder.DecoderListener {
    @Override
    public void onDecode(CharBuffer buffer, boolean closed, CoderResult decoderResult) {
      onStdoutChars(buffer, closed, decoderResult);
    }
  }

  private class StderrDecoderListener implements ListeningCharsetDecoder.DecoderListener {
    @Override
    public void onDecode(CharBuffer buffer, boolean closed, CoderResult decoderResult) {
      onStderrChars(buffer, closed, decoderResult);
    }
  }

  /**
   * Creates a {@link AbstractCharsetProcessListener} which uses a single {@link Charset}
   * to encode and decode stdin, stdout, and stderr bytes to and from String data.
   */
  public AbstractCharsetProcessListener(Charset charset) {
    this(charset.newEncoder(), charset.newDecoder(), charset.newDecoder());
  }

  /**
   * Creates a {@link AbstractCharsetProcessListener} which uses
   * separate {@link CharsetEncoder} and {@link CharsetDecoder}s to
   * encode and decode stdin, stdout, and stderr bytes to and from
   * String data.
   */
  public AbstractCharsetProcessListener(
      CharsetEncoder stdinEncoder,
      CharsetDecoder stdoutDecoder,
      CharsetDecoder stderrDecoder) {
    this.stdinEncoder = new ListeningCharsetEncoder(new StdinEncoderListener(), stdinEncoder);
    this.stdoutDecoder = new ListeningCharsetDecoder(new StdoutDecoderListener(), stdoutDecoder);
    this.stderrDecoder = new ListeningCharsetDecoder(new StderrDecoderListener(), stderrDecoder);
  }

  @Override
  public void onStart(ListeningProcessExecutor.LaunchedProcess process) {

  }

  @Override
  public void onExit(int exitCode) {

  }

  @Override
  public final void onStdout(ByteBuffer buffer, boolean closed) {
    this.stdoutDecoder.onOutput(buffer, closed);
  }

  @Override
  public final void onStderr(ByteBuffer buffer, boolean closed) {
    this.stderrDecoder.onOutput(buffer, closed);
  }

  @Override
  public boolean onStdinReady(ByteBuffer buffer) {
    return this.stdinEncoder.onStdinReady(buffer);
  }

  /**
   * Called when the process is ready to receive string data on stdin.
   *
   * Before this method returns, you must set the {@code buffer}'s
   * {@link CharBuffer#position() position} and {@link CharBuffer#limit() limit} (for example, by
   * invoking {@link CharBuffer#flip()}) to indicate how much data is in the buffer
   * before returning from this method.
   *
   * You must first call {@link ListeningProcessExecutor.LaunchedProcess#wantWrite()} at
   * least once before this method will be invoked.
   *
   * If not all of the data needed to be written will fit in {@code buffer},
   * you can return {@code true} to indicate that you would like to write more
   * data.
   *
   * Otherwise, return {@code false} if you have no more data to write to
   * stdin. (You can always invoke {@link ListeningProcessExecutor.LaunchedProcess#wantWrite()} any
   * time in the future.
   */
  @SuppressWarnings("unused") // Unused parameters are meant for subclasses to override and use.
  protected boolean onStdinCharsReady(CharBuffer buffer) {
    return false;
  }

  /**
   * Called when there is an error encoding string data received from
   * {@link #onStdinCharsReady(CharBuffer)}.
   *
   * @param result The {@link CoderResult} indicating encoder error
   */
  protected void onStdinEncoderError(CoderResult result) {

  }

  /**
   * Override this to receive decoded Unicode Java string data read from
   * stdout.
   * <p>
   * Make sure to set the {@link CharBuffer#position() position} of
   * {@code buffer} to indicate how much data you have read before returning.
   *
   * @param buffer The {@link CharBuffer} receiving Unicode string data.
   */
  @SuppressWarnings("unused") // Unused parameters are meant for subclasses to override and use.
  protected void onStdoutChars(CharBuffer buffer, boolean closed, CoderResult coderResult) {
    // Consume the entire buffer by default.
    buffer.position(buffer.limit());
  }

  /**
   * Override this to receive decoded Unicode Java string data read from
   * stderr.
   * <p>
   * Make sure to set the {@link CharBuffer#position() position} of
   * {@code buffer} to indicate how much data you have read before returning.
   *
   * @param buffer The {@link CharBuffer} receiving Unicode string data.
   */
  @SuppressWarnings("unused") // Unused parameters are meant for subclasses to override and use.
  protected void onStderrChars(CharBuffer buffer, boolean closed, CoderResult coderResult) {
    // Consume the entire buffer by default.
    buffer.position(buffer.limit());
  }
}
