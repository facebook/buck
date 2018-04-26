/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.worker;

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
interface AbstractWorkerProcessParams {
  /**
   * Temp folder location. This will be used to store .args, .out and .err files. Additionally, this
   * will be set into TMP environment variable which worker process tool can use to store other
   * temporary files, or locate .args, .out and .err files and perform output into them.
   */
  Path getTempDir();

  /**
   * Command that is used to start the worker job, e.g. "my_hashing_tool". It is expected that this
   * tool will be able to act as a worker process (communicate using specific protocol, accept jobs,
   * etc.)
   */
  ImmutableList<String> getStartupCommand();

  /** Environment that will be used to start the worker tool. */
  ImmutableMap<String, String> getStartupEnvironment();

  /** Maximum number of tools that pool can have. */
  int getMaxWorkers();

  /**
   * Identifies the instance of the persisted worker process pool. Defines when worker process pool
   * should be invalidated.
   *
   * <p>If this value is absent, then key will be automatically computed based on the startup
   * command and startup arguments.
   */
  Optional<WorkerProcessIdentity> getWorkerProcessIdentity();
}
