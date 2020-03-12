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
import com.facebook.buck.logd.proto.LogType;
import java.io.IOException;
import java.io.OutputStream;

/** Provides a LogdStreamFactory for when LogD is enabled */
public class LogdStreamFactory implements LogStreamFactory {
  private final LogDaemonClient logdClient;

  /**
   * Constructor for LogdStreamFactory
   *
   * @param logdClient logdClient reference if LogD is enabled
   */
  public LogdStreamFactory(LogDaemonClient logdClient) {
    this.logdClient = logdClient;
  }

  @Override
  public OutputStream createLogStream(String path, LogType logType) throws IOException {
    try {
      return new LogdStream(logdClient, path, logType);
    } catch (LogDaemonException e) {
      throw new IOException("Failed to create a LogD stream", e);
    }
  }
}
