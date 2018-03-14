/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.rules.modern.builders.thrift.ActionResult;
import com.facebook.buck.rules.modern.builders.thrift.Digest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** Interface for a remote execution service. Used by RemoteExecution to build rules. */
interface RemoteExecutionService {
  /**
   * This should run the command with the provided environment and inputs.
   *
   * <p>Returns an ActionResult with exit code, outputs, stdout/stderr, etc.
   */
  ActionResult execute(
      ImmutableList<String> command,
      ImmutableSortedMap<String, String> commandEnvironment,
      Digest inputsRootDigest,
      Set<Path> outputs)
      throws IOException, InterruptedException;
}
