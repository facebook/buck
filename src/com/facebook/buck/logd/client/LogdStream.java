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

import com.facebook.buck.logd.LogDaemonException;
import com.facebook.buck.logd.proto.LogMessage;
import com.facebook.buck.logd.proto.LogType;
import com.google.common.base.Charsets;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.OutputStream;

/** LogdStream streams log messages to LogD */
public class LogdStream extends OutputStream {
  private final StreamObserver<LogMessage> logdStream;
  private final byte[] buf = new byte[1];
  private final int logFileId;

  /**
   * Constructor for LogdStream
   *
   * @param logdClient client with connection to LogD server
   * @param path path to log file
   * @param logType type of log
   * @throws LogDaemonException if LogD fails to return a log file identifier
   */
  public LogdStream(LogDaemonClient logdClient, String path, LogType logType)
      throws LogDaemonException {
    this.logFileId = logdClient.createLogFile(path, logType);
    // empty message string as first call to openLog is only to get back a StreamObserver object
    this.logdStream = logdClient.openLog(logFileId, "");
  }

  private void write(String logMessage) {
    logdStream.onNext(
        LogMessage.newBuilder().setLogId(logFileId).setLogMessage(logMessage).build());
  }

  @Override
  public void write(int b) throws IOException {
    buf[0] = (byte) b;
    write(buf);
  }

  @Override
  public void write(byte b[]) throws IOException {
    // TODO(qahoang): this String instantiation should be deleted.
    // Required to change String to ByteString (immutable byte[]) data type in proto message
    // declaration
    write(new String(b, Charsets.UTF_8));
  }

  @Override
  public void close() throws IOException {
    logdStream.onCompleted();
  }
}
