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

  /**
   * The relative path to the directory where Buck will generate its files.
   */
  public static final String BUCK_OUTPUT_DIRECTORY = "buck-out";
  public static final Path BUCK_OUTPUT_PATH = Paths.get("buck-out");

  // TODO(mbolin): The constants GEN_DIR, BIN_DIR, and ANNOTATION_DIR should be
  // package-private to the com.facebook.buck.rules directory. Currently, they are also used in the
  // com.facebook.buck.shell package, but these values should be injected into shell commands rather
  // than hardcoded therein. This ensures that shell commands stay build-rule-agnostic.

  public static final String GEN_DIR = BUCK_OUTPUT_DIRECTORY + "/gen";
  public static final Path GEN_PATH = BUCK_OUTPUT_PATH.resolve("gen");

  public static final String BIN_DIR = BUCK_OUTPUT_DIRECTORY + "/bin";
  public static final Path BIN_PATH = BUCK_OUTPUT_PATH.resolve("bin");

  public static final String ANNOTATION_DIR = BUCK_OUTPUT_DIRECTORY + "/annotation";
  public static final Path ANNOTATION_PATH = BUCK_OUTPUT_PATH.resolve("annotation");

  public static final Path LOG_PATH = BUCK_OUTPUT_PATH.resolve("log");

  public static final Path BUCK_TRACE_DIR = BUCK_OUTPUT_PATH.resolve("log/traces");

  /**
   * This variable is package-private because conceptually, only parsing logic should be concerned
   * with the files that define build rules. Note that if the value of this variable changes, the
   * {@code BUILD_RULES_FILE_NAME} constant in {@code buck.py} must be updated, as well.
   */
  public static final String BUILD_RULES_FILE_NAME = "BUCK";

  private BuckConstant() {}
}
