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

package com.facebook.buck.remoteexecution;

import build.bazel.remote.execution.v2.ExecuteOperationMetadata;
import build.bazel.remote.execution.v2.ExecutedActionMetadata;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputFile;
import com.facebook.buck.remoteexecution.proto.RemoteExecutionMetadata;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Interface for a client of the remote execution service. Used by RemoteExecution to build rules.
 */
public interface RemoteExecutionServiceClient {
  /** Represents the result of remote execution. */
  interface ExecutionResult {
    RemoteExecutionMetadata getRemoteExecutionMetadata();

    List<OutputDirectory> getOutputDirectories();

    List<OutputFile> getOutputFiles();

    int getExitCode();

    Optional<String> getStdout();

    Optional<String> getStderr();

    Digest getActionResultDigest();

    ExecutedActionMetadata getActionMetadata();
  }

  /** Handle for an execution in progress. */
  interface ExecutionHandle {
    ListenableFuture<ExecutionResult> getResult();

    ListenableFuture<ExecuteOperationMetadata> getExecutionStarted();

    void cancel();
  }

  /**
   * This should run the command with the provided environment and inputs.
   *
   * <p>Returns an ActionResult with exit code, outputs, stdout/stderr, etc.
   */
  ExecutionHandle execute(Digest actionDigest, String ruleName, MetadataProvider metadataProvider)
      throws IOException, InterruptedException;
}
