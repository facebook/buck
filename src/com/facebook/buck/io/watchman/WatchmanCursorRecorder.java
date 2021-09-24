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

package com.facebook.buck.io.watchman;

import com.facebook.buck.core.util.log.Logger;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * WatchmanCursorRecorder records the last watchman cursor record used by the latest buck build to
 * the path project/buck-out/watchman_cursor. Watchman can use this history cursor to check file
 * changes since last build
 */
public class WatchmanCursorRecorder {
  private static final String WATCHMAN_CURSOR_FILE_NAME = "watchman_cursor_record";
  private static final Logger LOG = Logger.get(WatchmanCursorRecorder.class);
  private final ObjectMapper objectMapper;
  private final Path watchmanCursorFilePath;

  public WatchmanCursorRecorder(Path buckOutPath) {
    this.watchmanCursorFilePath = buckOutPath.resolve(WATCHMAN_CURSOR_FILE_NAME);
    this.objectMapper = new ObjectMapper();
    objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
  }

  /**
   * recordWatchmanCursor write the latest watchman cursor record to the path {@link
   * WatchmanCursorRecorder#watchmanCursorFilePath}
   */
  public void recordWatchmanCursor(WatchmanCursorRecord cursorRecord) {
    try {
      objectMapper.writeValue(watchmanCursorFilePath.toFile(), cursorRecord);
      LOG.info(
          "Record the latest watchman cursor record: %s to the file:%s",
          cursorRecord, watchmanCursorFilePath);
    } catch (Exception e) {
      LOG.error(
          e,
          "Failed to record the latest watchman cursor record[%s] to the path:%s",
          cursorRecord,
          watchmanCursorFilePath);
    }
  }

  /**
   * getWatchmanCursorRecord read the latest watchman cursor record from the path {@link
   * WatchmanCursorRecorder#watchmanCursorFilePath}
   */
  public Optional<WatchmanCursorRecord> readWatchmanCursorRecord() {
    if (!Files.exists(watchmanCursorFilePath)) {
      return Optional.empty();
    }
    try {
      WatchmanCursorRecord cursorRecord =
          objectMapper.readValue(watchmanCursorFilePath.toFile(), WatchmanCursorRecord.class);
      LOG.info(
          "Reading the watchman cursor record: %s used by last build under the path:%s",
          cursorRecord, watchmanCursorFilePath);
      return Optional.of(cursorRecord);
    } catch (Exception e) {
      LOG.error(
          e,
          "Failed reading from the latest watchman cursor from the path:%s",
          watchmanCursorFilePath);
      return Optional.empty();
    }
  }
}
