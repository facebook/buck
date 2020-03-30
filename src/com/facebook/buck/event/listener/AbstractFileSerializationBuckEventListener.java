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

package com.facebook.buck.event.listener;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.util.concurrent.MostExecutors;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Abstract class that implements {@link BuckEventListener} and has a basic logic for creating file
 * output {@link BufferedWriter} and common methods: {@code writeLine()}, {@code close()}, etc.
 */
abstract class AbstractFileSerializationBuckEventListener implements BuckEventListener {

  protected final Logger LOG = Logger.get(getClass());

  private final ExecutorService executor =
      MostExecutors.newSingleThreadExecutor(getClass().getSimpleName());

  private final BufferedWriter writer;
  private final Path outputPath;

  public AbstractFileSerializationBuckEventListener(Path outputPath, OpenOption... openOptions)
      throws IOException {
    this.outputPath = outputPath;
    this.writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8, openOptions);
  }

  protected final void scheduleWrite(String line) {
    executor.submit(() -> writeLine(line));
  }

  private void writeLine(String line) {
    try {
      writer.write(line);
      writer.newLine();
      writer.flush();
    } catch (IOException e) {
      LOG.warn(e, "I/O exception during writing the line: '%s' to the file: %s", line, outputPath);
    }
  }

  @Override
  public void close() {
    shutdownExecutorService();
    closeWriter();
  }

  private void shutdownExecutorService() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(100, TimeUnit.MILLISECONDS)) {
        LOG.warn(
            "ExecutorService failed to shutdown for a specified amount of time."
                + " Invoking force shutdown method.");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      LOG.warn(e, "Failed to shutdown ExecutorService");
    }
  }

  private final void closeWriter() {
    try {
      writer.close();
    } catch (IOException e) {
      LOG.warn(e, "I/O exception during closing the file: %s", outputPath);
    }
  }
}
