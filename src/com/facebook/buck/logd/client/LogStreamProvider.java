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

import java.util.Optional;

/** A class that provides appropriate {@code LogStreamFactory} implementation */
public class LogStreamProvider {
  private final Optional<LogDaemonClient> logdClient;

  /** Constructor for LogStreamProvider */
  private LogStreamProvider(Optional<LogDaemonClient> logdClient) {
    this.logdClient = logdClient;
  }

  /**
   * Returns a LogStreamProvider
   *
   * @param logdClient an optional {@code LogDaemonClient}
   * @return a LogStreamProvider
   */
  public static LogStreamProvider of(Optional<LogDaemonClient> logdClient) {
    return new LogStreamProvider(logdClient);
  }

  /**
   * Returns the appropriate LogStreamFactory implementation
   *
   * @return the appropriate LogStreamFactory implementation
   */
  public LogStreamFactory getLogStreamFactory() {
    return logdClient
        .map(client -> (LogStreamFactory) new LogdStreamFactory(client))
        .orElseGet(FileOutputStreamFactory::new);
  }
}
