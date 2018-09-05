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

package com.facebook.buck.rules.modern.builders.thrift.executionengine;

import com.facebook.buck.rules.modern.builders.Protocol;
import com.facebook.buck.rules.modern.builders.Protocol.OutputDirectory;
import com.facebook.buck.rules.modern.builders.Protocol.OutputFile;
import com.facebook.buck.rules.modern.builders.RemoteExecutionService;
import com.facebook.buck.rules.modern.builders.thrift.ThriftProtocol;
import com.facebook.buck.rules.modern.builders.thrift.ThriftProtocol.ThriftOutputDirectory;
import com.facebook.buck.rules.modern.builders.thrift.ThriftProtocol.ThriftOutputFile;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.remoteexecution.cas.ContentAddressableStorage;
import com.facebook.remoteexecution.cas.ContentAddressableStorageException;
import com.facebook.remoteexecution.cas.ReadBlobRequest;
import com.facebook.remoteexecution.cas.ReadBlobResponse;
import com.facebook.remoteexecution.executionengine.ActionResult;
import com.facebook.remoteexecution.executionengine.ExecuteOperation;
import com.facebook.remoteexecution.executionengine.ExecuteRequest;
import com.facebook.remoteexecution.executionengine.ExecuteResponse;
import com.facebook.remoteexecution.executionengine.ExecutionEngine;
import com.facebook.remoteexecution.executionengine.ExecutionEngine.Client;
import com.facebook.remoteexecution.executionengine.ExecutionEngineException;
import com.facebook.remoteexecution.executionengine.GetExecuteOperationRequest;
import com.facebook.thrift.TException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/** A Thrift-based remote execution (execution engine) implementation. */
public class ThriftExecutionEngine implements RemoteExecutionService {

  private static final int INITIAL_POLL_INTERVAL_MS = 15;
  private static final int MAX_POLL_INTERVAL_MS = 500;
  private static final int POLL_FUZZ_SIZE_MS = 20;
  private static final float POLL_INTERVAL_GROWTH = 1.25f;
  private static final boolean SKIP_CACHE_LOOKUP = false;
  private static Charset CHARSET = Charset.forName("UTF-8");

  private final Client client;
  private final ContentAddressableStorage.Client casClient;

  public ThriftExecutionEngine(
      ExecutionEngine.Client client, ContentAddressableStorage.Client casClient) {
    this.client = client;
    this.casClient = casClient;
  }

  @Override
  public ExecutionResult execute(Protocol.Digest actionDigest)
      throws IOException, InterruptedException {

    ExecuteRequest request =
        new ExecuteRequest(SKIP_CACHE_LOOKUP, ThriftProtocol.get(actionDigest));

    try {
      ExecuteOperation operation;
      synchronized (client) {
        // TODO(shivanker): Thrift client is not thread-safe. We *really* don't want this for the
        // long-term. Will convert this to an async-client in the next diff.
        operation = client.execute(request);
      }
      ExecuteResponse response = waitForResponse(operation);
      ActionResult actionResult = response.result;
      return actionResultToExecutionResult(actionResult);
    } catch (TException | ExecutionEngineException e) {
      throw new RuntimeException(e);
    }
  }

  private ExecuteResponse waitForResponse(ExecuteOperation operation) throws TException {
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
        synchronized (client) {
          operation = client.getExecuteOperation(request);
        }
      } catch (ExecutionEngineException e) {
        throw new RuntimeException(e);
      }
    }
    return operation.response;
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
          System.err.println("Got stderr digest.");
          try {
            ReadBlobRequest request = new ReadBlobRequest(actionResult.stderr_digest);
            ReadBlobResponse response;
            synchronized (casClient) {
              response = casClient.readBlob(request);
            }
            return Optional.of(new String(response.data, CHARSET));
          } catch (TException | ContentAddressableStorageException e) {
            throw new RuntimeException(e);
          }
        } else {
          String stderr = new String(stderrRaw, CHARSET);
          System.err.println("Got raw stderr: " + stderr);
          return Optional.of(stderr);
        }
      }
    };
  }
}
