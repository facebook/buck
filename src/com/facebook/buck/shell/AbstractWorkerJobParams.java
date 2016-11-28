/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.Optional;

@Value.Immutable
@BuckStyleTuple
interface AbstractWorkerJobParams {
  /**
   * Temp folder location.
   */
  Path getTempDir();

  /**
   * Command that is used to start the worker job, e.g. "my_hashing_tool". It is expected that this
   * tool will be able to act as a worker process (communicate using specific protocol, accept jobs,
   * etc.)
   */
  ImmutableList<String> getStartupCommand();

  /**
   * All args combined into single string and separated with a white space, e.g. if tool is expected
   * to compute hashes on the fly you may pass something like "--compute-hashes".
   * This string will be escaped for you using Escaper#SHELL_ESCAPER.
   */
  String getStartupArgs();

  /**
   * Environment that will be used to start the worker tool.
   */
  ImmutableMap<String, String> getStartupEnvironment();

  /**
   * The arguments of the actual job once tool has started and ready to accept jobs. For example,
   * "--hash-file /path/to/file".
   */
  String getJobArgs();

  /**
   * Maximum number of tools that pool can have.
   */
  int getMaxWorkers();

  /**
   * If this value is set, it will be used to obtain the instance of the worker process pool.
   * If the value is absent, then key is computed based on the startup command and startup
   * arguments.
   */
  Optional<String> getPersistentWorkerKey();

  /**
   * Hash to identify the specific worker pool and kind of a mechanism for invalidating existing
   * pools.
   * If this value for the given persistent worker key changes, old pool will be destroyed and
   * the new pool will be recreated.
   */
  Optional<HashCode> getWorkerHash();
}
