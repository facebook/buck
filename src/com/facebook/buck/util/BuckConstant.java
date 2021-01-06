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

package com.facebook.buck.util;

import java.nio.file.Path;
import java.nio.file.Paths;

public class BuckConstant {
  public static final String BUCK_LOG_FILE_NAME = "buck.log";
  public static final String BUCK_MACHINE_LOG_FILE_NAME = "buck-machine-log";
  public static final String DEFAULT_BUCK_OUT_DIR_NAME = "buck-out";
  private static final Path BUCK_OUTPUT_PATH_DEFAULT =
      Paths.get(System.getProperty("buck.base_buck_out_dir", DEFAULT_BUCK_OUT_DIR_NAME));

  public static final String RULE_KEY_LOGGER_FILE_NAME = "rule_key_logger.tsv";
  public static final String RULE_KEY_DIAG_KEYS_FILE_NAME = "rule_key_diag_keys.txt";
  public static final String RULE_KEY_DIAG_GRAPH_FILE_NAME = "rule_key_diag_graph.txt";

  public static final String CONFIG_JSON_FILE_NAME = "buckconfig.json";

  public static final String BUCK_FIX_SPEC_FILE_NAME = "buck_fix_spec.json";

  public static final String BUCK_SIMPLE_CONSOLE_LOG_FILE_NAME = "simple_console.log";

  private BuckConstant() {}

  /**
   * The relative path to the directory where Buck will generate its files.
   *
   * <p>NOTE: Should only ever be used from there and {@link
   * com.facebook.buck.io.filesystem.ProjectFilesystem}.
   */
  public static Path getBuckOutputPath() {
    return BUCK_OUTPUT_PATH_DEFAULT;
  }
}
