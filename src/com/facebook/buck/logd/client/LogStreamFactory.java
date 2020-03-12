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

import com.facebook.buck.logd.proto.LogType;
import java.io.IOException;
import java.io.OutputStream;

/** LogStreamFactory Interface */
public interface LogStreamFactory {

  /**
   * Creates an output stream to the log file at {@code path}. Log file is created if if does not
   * yet exist, else a stream is created and appends the existing file.
   *
   * @param path path to log file
   * @param logType type of log
   * @return an output stream to the log file at {@code path}
   */
  OutputStream createLogStream(String path, LogType logType) throws IOException;
}
