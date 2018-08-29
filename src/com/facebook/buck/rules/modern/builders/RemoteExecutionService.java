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

import com.facebook.buck.rules.modern.builders.Protocol.Digest;
import com.facebook.buck.rules.modern.builders.Protocol.OutputDirectory;
import com.facebook.buck.rules.modern.builders.Protocol.OutputFile;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Interface for a remote execution service. Used by RemoteExecution to build rules. */
public interface RemoteExecutionService {
  /** Represents the result of remote execution. */
  interface ExecutionResult {
    List<OutputDirectory> getOutputDirectories();

    List<OutputFile> getOutputFiles();

    int getExitCode();

    Optional<String> getStderr();
  }

  /**
   * This should run the command with the provided environment and inputs.
   *
   * <p>Returns an ActionResult with exit code, outputs, stdout/stderr, etc.
   */
  ExecutionResult execute(Digest actionDigest) throws IOException, InterruptedException;
}
