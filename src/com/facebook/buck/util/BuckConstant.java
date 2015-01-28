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


import com.google.common.base.Preconditions;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nullable;

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

  private BuckConstant() {}

  /**
   * An optional path-component for the directory where test-results are written.
   * <p>
   * See the --one-time-directory command line option in
   * {@link com.facebook.buck.cli.TestCommandOptions} and {@link com.facebook.buck.cli.TestCommand}
   * where this is used to give each parallel buck processes a unique test-results-directory
   * thereby stopping the parallel processes from interfering with each others results.
   * <p>
   * TODO(#4473736) Create a long-term non-hacky solution to this problem!
   */
  @Nullable
  public static String oneTimeTestSubdirectory = null;

  public static void setOneTimeTestSubdirectory(String oneTimeTestSubdirectory) {
    Preconditions.checkState(!oneTimeTestSubdirectory.isEmpty(), "cannot be an empty string");
    BuckConstant.oneTimeTestSubdirectory = oneTimeTestSubdirectory;
  }

}
