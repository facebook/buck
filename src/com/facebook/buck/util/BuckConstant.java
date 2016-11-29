/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util;

import java.nio.file.Path;
import java.nio.file.Paths;

public class BuckConstant {
  public static final String BUCK_LOG_FILE_NAME = "buck.log";
  public static final String BUCK_MACHINE_LOG_FILE_NAME = "buck-machine-log";
  public static final String DIST_BUILD_SLAVE_LOG_FILE_NAME_TEMPLATE = "dist-build-slave-%s-%s.log";
  public static final String RULE_KEY_LOGGER_FILE_NAME = "rule_key_logger.tsv";

  private static final String BUCK_OUTPUT_DIRECTORY = "buck-out";
  private static final Path BUCK_OUTPUT_PATH = Paths.get("buck-out");
  private static final Path CURRENT_VERSION_FILE = getBuckOutputPath().resolve(".currentversion");

  private static final Path BUCK_TRACE_DIR = getBuckOutputPath().resolve("log/traces");
  private static final String DEFAULT_CACHE_DIR = getBuckOutputDirectory() + "/cache";

  // We put a . at the front of the name so Spotlight doesn't try to index the contents on OS X.
  private static final Path TRASH_PATH = getBuckOutputPath().resolve(".trash");

  private BuckConstant() {}

  /**
   * The relative path to the directory where Buck will generate its files.
   *
   * NOTE: Should only ever be used from there and {@link com.facebook.buck.io.ProjectFilesystem}.
   */
  public static String getBuckOutputDirectory() {
    return BUCK_OUTPUT_DIRECTORY;
  }

  private static Path getBuckOutputPath() {
    return BUCK_OUTPUT_PATH;
  }

  /**
   * The version the buck output directory was created for
   */
  public static Path getCurrentVersionFile() {
    return CURRENT_VERSION_FILE;
  }

  public static Path getBuckTraceDir() {
    return BUCK_TRACE_DIR;
  }

  public static String getDefaultCacheDir() {
    return DEFAULT_CACHE_DIR;
  }

  public static Path getTrashPath() {
    return TRASH_PATH;
  }
}
