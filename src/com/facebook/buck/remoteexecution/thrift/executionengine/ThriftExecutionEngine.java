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

package com.facebook.buck.remoteexecution.thrift.executionengine;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.remoteexecution.Protocol;
import com.facebook.buck.remoteexecution.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.Protocol.OutputFile;
import com.facebook.buck.remoteexecution.RemoteExecutionService;
import com.facebook.buck.remoteexecution.thrift.ThriftProtocol;
import com.facebook.buck.remoteexecution.thrift.ThriftProtocol.ThriftOutputDirectory;
import com.facebook.buck.remoteexecution.thrift.ThriftProtocol.ThriftOutputFile;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.remoteexecution.cas.ContentAddressableStorage;
import com.facebook.remoteexecution.cas.ContentAddressableStorageException;
import com.facebook.remoteexecution.cas.ReadBlobRequest;
import com.facebook.remoteexecution.cas.ReadBlobResponse;
import com.facebook.remoteexecution.executionengine.ActionResult;
import com.facebook.remoteexecution.executionengine.ExecuteOperation;
import com.facebook.remoteexecution.executionengine.ExecuteRequest;
import com.facebook.remoteexecution.executionengine.ExecuteRequestMetadata;
import com.facebook.remoteexecution.executionengine.ExecuteResponse;
import com.facebook.remoteexecution.executionengine.ExecutionEngine;
import com.facebook.remoteexecution.executionengine.ExecutionEngineException;
import com.facebook.remoteexecution.executionengine.GetExecuteOperationRequest;
import com.facebook.thrift.TException;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/** A Thrift-based remote execution (execution engine) implementation. */
public class ThriftExecutionEngine implements RemoteExecutionService {
  private static final Logger LOG = Logger.get(ThriftExecutionEngine.class);

  private static final int INITIAL_POLL_INTERVAL_MS = 15;
  private static final int MAX_POLL_INTERVAL_MS = 500;
  private static final int POLL_FUZZ_SIZE_MS = 20;
  private static final float POLL_INTERVAL_GROWTH = 1.25f;
  private static final boolean SKIP_CACHE_LOOKUP = false;
  private static Charset CHARSET = Charset.forName("UTF-8");

  private final ExecutionEngine.Iface reeClient;
  private final ContentAddressableStorage.Iface casClient;
  private final Optional<String> traceId;

  public ThriftExecutionEngine(
      ExecutionEngine.Iface reeClient,
      ContentAddressableStorage.Iface casClient,
      Optional<String> traceId) {
    this.reeClient = reeClient;
    this.casClient = casClient;
    this.traceId = traceId;
  }

  @Override
  public ExecutionResult execute(Protocol.Digest actionDigest)
      throws IOException, InterruptedException {

    ExecuteRequest request =
        new ExecuteRequest(SKIP_CACHE_LOOKUP, ThriftProtocol.get(actionDigest));
    ExecuteRequestMetadata metadata = new ExecuteRequestMetadata();
    traceId.ifPresent(metadata::setTrace_id);
    request.setMetadata(metadata);

    ExecuteResponse response;
    try {
      ExecuteOperation operation;
      operation = reeClient.execute(request);
      response = waitForResponse(operation);
    } catch (TException | ExecutionEngineException e) {
      // ExecutionEngineException thrown here means an Infra Failure.
      throw new RuntimeException(e);
    }

    try {
      if (response.isSetResult()) {
        ActionResult actionResult = response.result;
        return actionResultToExecutionResult(actionResult);
      } else if (response.isSetEx()) {
        throw response.ex;
      } else {
        throw new IllegalStateException(
            "Neither ActionResult nor ExecutionEngineException was set in ExecuteResponse.");
      }
    } catch (ExecutionEngineException e) {
      // ExecutionEngineException thrown here means a failure while executing the action.
      return new ExecutionResult() {
        @Override
        public List<OutputDirectory> getOutputDirectories() {
          return new ArrayList<>();
        }

        @Override
        public List<OutputFile> getOutputFiles() {
          return new ArrayList<>();
        }

        @Override
        public int getExitCode() {
          // This is suppose to be non-zero, because we got an exception from the ExecutionEngine.
          if (e.getCode() == 0) {
            LOG.warn(
                "Received an ExecutionEngineException with code 0. "
                    + "Returning exit code -1 for this Execution.");
            return -1;
          }
          return e.getCode();
        }

        @Override
        public Optional<String> getStderr() {
          String message = e.getMessage();
          if (e.getDetails().size() > 0) {
            message += String.format("\nDetails:\n%s", String.join("\n", e.getDetails()));
          }

          return Optional.of(message);
        }
      };
    }
  }

  private ExecuteResponse waitForResponse(ExecuteOperation operation)
      throws TException, ExecutionEngineException {
    // TODO(orr): This is currently blocking and infinite, like the Grpc implementation. This is
    // not a good long-term solution:
    int pollInterval = INITIAL_POLL_INTERVAL_MS;
    pollInterval += ThreadLocalRandom.current().nextInt(POLL_FUZZ_SIZE_MS);
    while (!operation.done) {
      try {
        Thread.sleep(pollInterval);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BuckUncheckedExecutionException(e);
      }
      pollInterval = (int) Math.min(pollInterval * POLL_INTERVAL_GROWTH, MAX_POLL_INTERVAL_MS);
      pollInterval += ThreadLocalRandom.current().nextInt(POLL_FUZZ_SIZE_MS);

      GetExecuteOperationRequest request = new GetExecuteOperationRequest(operation.execution_id);

      try {
        operation = reeClient.getExecuteOperation(request);
        Preconditions.checkState(operation.isSetDone(), "Invalid ExecuteOperation received.");
      } catch (ExecutionEngineException e) {
        throw new RuntimeException(e);
      }
    }
    if (operation.isSetResponse()) {
      return operation.response;
    } else if (operation.isSetEx()) {
      throw operation.ex;
    } else {
      throw new IllegalStateException(
          "Neither ExecuteResponse nor ExecutionEngineException was set in ExecuteOperation.");
    }
  }

  private ExecutionResult actionResultToExecutionResult(ActionResult actionResult) {
    return new ExecutionResult() {
      @Override
      public List<OutputDirectory> getOutputDirectories() {
        return actionResult
            .output_directories
            .stream()
            .map(ThriftOutputDirectory::new)
            .collect(Collectors.toList());
      }

      @Override
      public List<OutputFile> getOutputFiles() {
        return actionResult
            .output_files
            .stream()
            .map(ThriftOutputFile::new)
            .collect(Collectors.toList());
      }

      @Override
      public int getExitCode() {
        return actionResult.exit_code;
      }

      @Override
      public Optional<String> getStderr() {
        byte[] stderrRaw = actionResult.stderr_raw;

        if (stderrRaw == null
            || (stderrRaw.length == 0 && actionResult.stderr_digest.size_bytes > 0)) {
          LOG.warn("Got stderr digest.");
          try {
            ReadBlobRequest request = new ReadBlobRequest(actionResult.stderr_digest);
            ReadBlobResponse response;
            response = casClient.readBlob(request);
            return Optional.of(new String(response.data, CHARSET));
          } catch (TException | ContentAddressableStorageException e) {
            throw new RuntimeException(e);
          }
        } else {
          String stderr = new String(stderrRaw, CHARSET);
          LOG.warn("Got raw stderr: " + stderr);
          return Optional.of(stderr);
        }
      }
    };
  }
}
