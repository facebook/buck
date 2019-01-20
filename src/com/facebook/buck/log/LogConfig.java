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

package com.facebook.buck.log;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.file.PathListing;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger; // NOPMD
import org.stringtemplate.v4.ST;

/**
 * Constructed by java.util.logging.LogManager via the system property
 * java.util.logging.LogManager.config.
 *
 * <p>Extends LogManager's support for a single logging.properties file to support a three-level
 * config. Each existent property file is concatenated together and used as a single LogManager
 * configuration, with later entries overriding earlier ones.
 *
 * <p>1) $BUCK_DIRECTORY/config/logging.properties.st 2) $PROJECT_ROOT/.bucklogging.properties 3)
 * $PROJECT_ROOT/.bucklogging.local.properties
 */
public class LogConfig {

  private static final byte[] NEWLINE = {'\n'};
  private static volatile boolean useAsyncFileLogging = false;

  /** Default constructor, called by LogManager. */
  public LogConfig() throws IOException {
    setupLogging(
        LogConfigSetup.builder()
            .from(LogConfigSetup.DEFAULT_SETUP)
            .setLogFilePrefix("launch-")
            .setCount(1)
            .build());
  }

  public static void setUseAsyncFileLogging(boolean useAsyncFileLogging) {
    LogConfig.useAsyncFileLogging = useAsyncFileLogging;
  }

  public static boolean shouldUseAsyncFileLogging() {
    return useAsyncFileLogging;
  }

  /**
   * Creates the log output directory and concatenates logging.properties files together to
   * configure or re-configure LogManager.
   */
  public static synchronized void setupLogging(LogConfigSetup logConfigSetup) throws IOException {
    // Bug JDK-6244047: The default FileHandler does not handle the directory not existing,
    // so we have to create it before any log statements actually run.
    Files.createDirectories(logConfigSetup.getLogDir());

    if (logConfigSetup.getRotateLog()) {
      try {
        deleteOldLogFiles(logConfigSetup);
      } catch (IOException e) {
        System.err.format("Error deleting old log files (ignored): %s\n", e.getMessage());
      }
    }

    ImmutableList.Builder<InputStream> inputStreamsBuilder = ImmutableList.builder();
    if (!LogConfigPaths.MAIN_PATH.isPresent()) {
      System.err.format(
          "Error: Couldn't read system property %s (it should be set by buck_common or buck.cmd)\n",
          LogConfigPaths.BUCK_CONFIG_STRING_TEMPLATE_FILE_PROPERTY);
    } else {
      if (!addInputStreamForTemplate(
          LogConfigPaths.MAIN_PATH.get(), logConfigSetup, inputStreamsBuilder)) {
        System.err.format(
            "Error: Couldn't open logging properties file %s\n", LogConfigPaths.MAIN_PATH.get());
      }
    }

    // We ignore the return value for these files; they don't need to exist.
    addInputStreamForPath(LogConfigPaths.PROJECT_PATH, inputStreamsBuilder);
    addInputStreamForPath(LogConfigPaths.LOCAL_PATH, inputStreamsBuilder);

    // Concatenate each of the files together and read them in as a single properties file
    // for log settings.
    try (InputStream is =
        new SequenceInputStream(Iterators.asEnumeration(inputStreamsBuilder.build().iterator()))) {
      LogManager.getLogManager().readConfiguration(is);
    }
  }

  public static void flushLogs() {
    Logger rootLogger = LogManager.getLogManager().getLogger("");
    if (rootLogger == null) {
      return;
    }
    Handler[] handlers = rootLogger.getHandlers();
    if (handlers == null) {
      return;
    }
    for (Handler h : handlers) {
      h.flush();
    }
  }

  private static boolean addInputStreamForTemplate(
      Path path,
      LogConfigSetup logConfigSetup,
      ImmutableList.Builder<InputStream> inputStreamsBuilder)
      throws IOException {
    try {
      String template = new String(Files.readAllBytes(path), Charsets.UTF_8);
      ST st = new ST(template);
      st.add(
          "default_file_pattern",
          MorePaths.pathWithUnixSeparators(logConfigSetup.getLogFilePath()));
      st.add("default_count", logConfigSetup.getCount());
      st.add("default_max_size_bytes", logConfigSetup.getMaxLogSizeBytes());
      String result = st.render();
      inputStreamsBuilder.add(new ByteArrayInputStream(result.getBytes(Charsets.UTF_8)));
      inputStreamsBuilder.add(new ByteArrayInputStream(NEWLINE));
      return true;
    } catch (FileNotFoundException e) {
      return false;
    }
  }

  private static boolean addInputStreamForPath(
      Path path, ImmutableList.Builder<InputStream> inputStreamsBuilder) {
    try {
      inputStreamsBuilder.add(new FileInputStream(path.toString()));
      // Handle the case where a file doesn't end with a newline.
      inputStreamsBuilder.add(new ByteArrayInputStream(NEWLINE));
      return true;
    } catch (FileNotFoundException e) {
      return false;
    }
  }

  private static void deleteOldLogFiles(LogConfigSetup logConfigSetup) throws IOException {
    for (Path path :
        PathListing.listMatchingPathsWithFilters(
            logConfigSetup.getLogDir(),
            logConfigSetup.getLogFilePrefix() + "*.log*",
            PathListing.GET_PATH_MODIFIED_TIME,
            PathListing.FilterMode.EXCLUDE,
            OptionalInt.empty(),
            Optional.of(logConfigSetup.getMaxLogSizeBytes()))) {
      Files.deleteIfExists(path);
    }
  }
}
