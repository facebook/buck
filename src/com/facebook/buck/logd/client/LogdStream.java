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

package com.facebook.buck.logd.client;

import com.facebook.buck.logd.proto.LogMessage;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import io.grpc.stub.StreamObserver;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** LogdStream streams log messages to LogD */
public class LogdStream extends OutputStream {
  private static final int KB_IN_BYTES = 1024;
  private static final int BUFFER_SIZE = 8 * KB_IN_BYTES;

  private final StreamObserver<LogMessage> logdStream;
  private byte[] buf;
  private int count;

  private final int logFileId;
  private final AtomicBoolean hasBeenClosed;

  /**
   * Constructor for LogdStream
   *
   * @param logdStream underlying {@code StreamObserver} that streams logs to LogD server
   * @param logFileId log file identifier returned by LogD server
   */
  public LogdStream(StreamObserver<LogMessage> logdStream, int logFileId) {
    this(logdStream, logFileId, BUFFER_SIZE);
  }

  private LogdStream(StreamObserver<LogMessage> logdStream, int logFileId, int size) {
    this.logdStream = logdStream;
    this.logFileId = logFileId;
    this.hasBeenClosed = new AtomicBoolean(false);

    Preconditions.checkArgument(size > 0, "Buffer size <= 0");
    // TODO(qahoang): decide buffer size empirically
    buf = new byte[size];
  }

  private synchronized void write(String logMessage) {
    logdStream.onNext(
        LogMessage.newBuilder().setLogId(logFileId).setLogMessage(logMessage).build());
  }

  @Override
  public synchronized void write(int b) {
    if (hasBeenClosed.get()) {
      return;
    }

    if (count >= buf.length) {
      flushBuffer();
    }
    buf[count++] = (byte) b;
  }

  private synchronized void flushBuffer() {
    if (count > 0) {
      String logMessage = new String(buf, 0, count, Charsets.UTF_8);
      write(logMessage);
      count = 0;
    }
  }

  @Override
  public void flush() {
    if (!hasBeenClosed.get()) {
      flushBuffer();
    }
  }

  @Override
  public void close() {
    // prevent double close if wrapped by other stream implementations
    if (hasBeenClosed.getAndSet(true)) {
      return;
    }

    flushBuffer();
    logdStream.onCompleted();
  }
}
