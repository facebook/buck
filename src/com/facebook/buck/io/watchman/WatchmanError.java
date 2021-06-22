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

/**
 * Reason why watchman was not initialized.
 *
 * <p>This is only used for Scuba logging, feel free to rename or regroup.
 */
public enum WatchmanError {
  NO_ERROR,

  API_STUBBER,
  CANNOT_EXTRACT_CAPABILITIES,
  DECODE,
  DEFAULT_FILE_HASH_CACHE,
  EXCEPTION,
  EXE_NOT_FOUND,
  GET_SOCKNAME_ERROR,
  GET_SOCKNAME_NO_SOCKNAME,
  GLOBAL_STATE_MANAGER,
  IJ_PROJECT_COMMAND_HELPER,
  ISOLATED_BUILDABLE_BUILDER,
  NO_CLOCK_FIELD,
  NO_DAEMON_NO_WATCHMAN,
  NO_WATCH_FIELD,
  PROJECT_BUILD_FILE_PARSER_OPTIONS,
  TEST,
  TIMEOUT_CLOCK,
  TIMEOUT_GET_SOCKNAME,
  TIMEOUT_VERSION,
  TIMEOUT_WATCH_PROJECT,
  UNARCHIVER,
  UNCLASSIFIED_REMOTE_ERROR,
  VERSION_NOT_STRING,
}
