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
import com.facebook.remoteexecution.cas.Digest;
import com.facebook.remoteexecution.cas.ReadBlobRequest;
import com.facebook.remoteexecution.cas.ReadBlobResponse;
import com.facebook.remoteexecution.executionengine.Action;
import com.facebook.remoteexecution.executionengine.ActionResult;
import com.facebook.remoteexecution.executionengine.ExecuteRequest;
import com.facebook.remoteexecution.executionengine.ExecuteResponse;
import com.facebook.remoteexecution.executionengine.ExecutionEngine;
import com.facebook.remoteexecution.executionengine.ExecutionEngine.Client;
import com.facebook.remoteexecution.executionengine.ExecutionState;
import com.facebook.remoteexecution.executionengine.GetExecutionStateRequest;
import com.facebook.remoteexecution.executionengine.GetExecutionStateResponse;
import com.facebook.remoteexecution.executionengine.NonUniqueExecutionIdException;
import com.facebook.remoteexecution.executionengine.RejectedActionException;
import com.facebook.remoteexecution.executionengine.Requirements;
import com.facebook.remoteexecution.executionengine.ServiceOverloadedException;
import com.facebook.remoteexecution.executionengine.UnknownExecutionIdException;
import com.facebook.thrift.TException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** A Thrift-based remote execution (execution engine) implementation. */
public class ThriftExecutionEngine implements RemoteExecutionService {

  private static Charset CHARSET = Charset.forName("UTF-8");

  private static int initialPollInterval = 50;
  private static float pollIntervalGrowth = 1.5f;
  private static int maxPollInterval = 2000;

  private final Client client;
  private final ContentAddressableStorage.Client casClient;

  public ThriftExecutionEngine(
      ExecutionEngine.Client client, ContentAddressableStorage.Client casClient) {
    this.client = client;
    this.casClient = casClient;
  }

  @Override
  public ExecutionResult execute(
      Protocol.Digest command, Protocol.Digest inputsRootDigest, Set<Path> outputs)
      throws IOException, InterruptedException {

    Digest commandDigest = ThriftProtocol.get(command);
    List<String> outputFiles =
        outputs.stream().map(Path::toString).sorted().collect(Collectors.toList());
    Requirements requirements = new Requirements();

    Action action =
        new Action(
            commandDigest,
            outputFiles,
            outputFiles,
            requirements,
            0, // timeout
            false // do_not_cache
            );

    ExecuteRequest request =
        new ExecuteRequest(
            "", // instance_name
            action,
            false // skip_cache_lookup
            );

    try {
      ExecuteResponse response = client.execute(request);
      com.facebook.remoteexecution.executionengine.ExecutionResult result = waitForResult(response);
      ActionResult actionResult = result.action_result;
      return actionResultToExecutionResult(actionResult);
    } catch (TException
        | RejectedActionException
        | NonUniqueExecutionIdException
        | ServiceOverloadedException e) {
      throw new RuntimeException(e);
    }
  }

  private com.facebook.remoteexecution.executionengine.ExecutionResult waitForResult(
      ExecuteResponse response) throws TException {
    // TODO(orr): This is currently blocking and infinite, like the Grpc implementation. This is
    // not a good long-term solution:
    ExecutionState state = response.state;
    int pollInterval = initialPollInterval;
    while (!state.done) {
      try {
        Thread.sleep(pollInterval);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BuckUncheckedExecutionException(e);
      }
      pollInterval = (int) Math.min(pollInterval * pollIntervalGrowth, maxPollInterval);

      GetExecutionStateRequest request = new GetExecutionStateRequest(response.state.execution_id);
      GetExecutionStateResponse stateResponse;

      try {
        stateResponse = client.getExecutionState(request);
      } catch (UnknownExecutionIdException e) {
        throw new RuntimeException(e);
      }
      state = stateResponse.state;
    }
    return state.result;
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
            ReadBlobResponse response = casClient.readBlob(request);
            return Optional.of(new String(response.data, CHARSET));
          } catch (TException e) {
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
