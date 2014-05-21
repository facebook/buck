/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.event.listener;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.model.BuildId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.Subscribe;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class FileSerializationEventBusListener implements BuckEventListener, Closeable {

  private final ObjectMapper objectMapper;
  private final BufferedWriter bufferedWriter;

  public FileSerializationEventBusListener(Path outputPath) throws IOException {
    objectMapper = new ObjectMapper();
    bufferedWriter = Files.newBufferedWriter(
        outputPath,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.APPEND);
  }

  @Override
  public void outputTrace(BuildId buildId) {
    // Empty
  }

  @Subscribe
  public void writeEvent(BuckEvent event) {
    String json = serializeEvent(event);
    try {
      bufferedWriter.write(json + "\n");
      bufferedWriter.flush();
    } catch (IOException e) {
      // Event listeners should avoid throwing, so catch and print.  Printing isn't guaranteed to
      // actually print anything, due to the way listeners are isolated by buck.
      e.printStackTrace();
    }
  }

  private String serializeEvent(BuckEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (IOException e) {
      return String.format(
          "{\"errorType\":\"%s\",\"errorMessage\":\"%s\"}",
          e.getClass().toString(),
          e.getMessage().replace('"', '\''));
    }
  }

  @Override
  public void close() throws IOException {
    bufferedWriter.close();
  }
}
