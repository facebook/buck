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

import com.facebook.buck.io.PathListing;
import com.facebook.buck.util.BuckConstant;

import com.google.common.base.Optional;
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
import java.util.Arrays;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Constructed by java.util.logging.LogManager via the system property
 * java.util.logging.LogManager.config.
 *
 * Extends LogManager's support for a single logging.properties file
 * to support a three-level config. Each existent property file is
 * concatenated together and used as a single LogManager configuration,
 * with later entries overriding earlier ones.
 *
 * 1) $BUCK_DIRECTORY/config/logging.properties
 * 2) $PROJECT_ROOT/.bucklogging.properties
 * 3) $PROJECT_ROOT/.bucklogging.local.properties
 */
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class LogConfig {

  private static final byte[] NEWLINE = {'\n'};
  private static final long MAX_LOG_SIZE = 100 * 1024 * 1024;

  /**
   * Default constructor, called by LogManager.
   */
  public LogConfig() throws IOException {
    setupLogging();
  }

  /**
   * Creates the log output directory and concatenates logging.properties
   * files together to configure or re-configure LogManager.
   */
  public static synchronized void setupLogging() throws IOException {
    // Bug JDK-6244047: The default FileHandler does not handle the directory not existing,
    // so we have to create it before any log statements actually run.
    Files.createDirectories(BuckConstant.LOG_PATH);

    try {
      deleteOldLogFiles();
    } catch (IOException e) {
      System.err.format(
          "Error deleting old log files (ignored): %s\n",
          e.getMessage());
    }

    ImmutableList.Builder<InputStream> inputStreamsBuilder = ImmutableList.builder();
    if (!LogConfigPaths.MAIN_PATH.isPresent()) {
      System.err.format(
          "Error: Couldn't read system property %s (it should be set by buck_common or buck.cmd)\n",
          LogConfigPaths.BUCK_CONFIG_FILE_PROPERTY);
    } else {
      if (!addInputStreamForPath(LogConfigPaths.MAIN_PATH.get(), inputStreamsBuilder)) {
        System.err.format(
            "Error: Couldn't open logging properties file %s\n",
            LogConfigPaths.MAIN_PATH.get());
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
    for (Handler h : Arrays.asList(handlers)) {
      h.flush();
    }
  }

  private static boolean addInputStreamForPath(
      Path path,
      ImmutableList.Builder<InputStream> inputStreamsBuilder) {
    try {
      inputStreamsBuilder.add(new FileInputStream(path.toString()));
      // Handle the case where a file doesn't end with a newline.
      inputStreamsBuilder.add(new ByteArrayInputStream(NEWLINE));
      return true;
    } catch (FileNotFoundException e) {
      return false;
    }
  }

  private static void deleteOldLogFiles() throws IOException {
    for (Path path : PathListing.listMatchingPathsWithFilters(
             BuckConstant.LOG_PATH,
             "buck-*.log*",
             PathListing.GET_PATH_MODIFIED_TIME,
             PathListing.FilterMode.EXCLUDE,
             Optional.<Integer>absent(),
             Optional.of(MAX_LOG_SIZE))) {
      Files.deleteIfExists(path);
    }
  }
}
