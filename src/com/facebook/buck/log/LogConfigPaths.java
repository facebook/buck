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

import com.google.common.base.Optional;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class containing constants for logging configuration paths.
 */
public class LogConfigPaths {
  /**
   * System property holding the path to the logging.properties file in the Buck repo.
   */
  public static final String BUCK_CONFIG_FILE_PROPERTY = "buck.logging_config_file";

  /**
   * If present, the path to the logging.properties file.
   */
  public static final Optional<Path> MAIN_PATH;

  /**
   * The path to the per-project logging.properties file.
   */
  public static final Path PROJECT_PATH = Paths.get(".bucklogging.properties");

  /**
   * The path to the per-project local (git ignored) logging.properties file.
   */
  public static final Path LOCAL_PATH = Paths.get(".bucklogging.local.properties");

  static {
    String buckConfigFileProperty = System.getProperty(BUCK_CONFIG_FILE_PROPERTY);
    if (buckConfigFileProperty == null) {
      MAIN_PATH = Optional.absent();
    } else {
      MAIN_PATH = Optional.of(Paths.get(buckConfigFileProperty));
    }
  }

  // Utility class. Do not instantiate.
  private LogConfigPaths() { }
}
