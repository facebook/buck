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

package com.facebook.buck.logd.server;

import java.io.IOException;
import java.io.Writer;

/** This class appends logs to storage */
public class LogdStorageWriter extends Writer {
  private final LogFileData logFileData;

  /**
   * Constructor for LogdStorageWriter
   *
   * @param logFileData log file info
   */
  public LogdStorageWriter(LogFileData logFileData) {
    this.logFileData = logFileData;
  }

  @Override
  public void write(char[] cbuf, int off, int len) throws IOException {
    logFileData.updateStorage(String.valueOf(cbuf, off, len));
  }

  @Override
  public void flush() {
    // do nothing
  }

  @Override
  public void close() {
    // do nothing
  }
}
