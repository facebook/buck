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

import com.facebook.buck.logd.proto.LogType;

/** This class encapsulates data for a log file in storage */
public class LogFileData {
  private final int logFileId;
  private final String buildId;
  private final LogType logType;
  private final String logFilePath;
  private int offset;

  /**
   * Constructor for LogFileData.
   *
   * @param logFileId log file identifier
   * @param buildId buck command's uuid
   * @param logType log file type
   * @param logFilePath path to log file
   * @param offset offset when file is first created in storage
   */
  public LogFileData(
      int logFileId, String buildId, LogType logType, String logFilePath, int offset) {
    this.logFileId = logFileId;
    this.buildId = buildId;
    this.logType = logType;
    this.logFilePath = logFilePath;
    this.offset = offset;
  }

  public int getLogFileId() {
    return logFileId;
  }

  public String getBuildId() {
    return buildId;
  }

  public LogType getLogType() {
    return logType;
  }

  public String getLogFilePath() {
    return logFilePath;
  }

  /**
   * Appends log to storage and updates file offset
   *
   * @param message log to be appended to log file
   */
  public synchronized void updateStorage(String message) {
    LogdUploader.uploadLogToStorage(buildId, logType, offset, message)
        .ifPresent(newOffset -> offset = newOffset);
  }
}
