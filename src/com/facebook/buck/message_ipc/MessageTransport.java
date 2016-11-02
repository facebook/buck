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

package com.facebook.buck.message_ipc;

import com.facebook.buck.shell.WorkerJobResult;
import com.facebook.buck.shell.WorkerProcess;

public class MessageTransport {
  private final WorkerProcess workerProcess;
  private final MessageSerializer serializer;

  public MessageTransport(WorkerProcess workerProcess, MessageSerializer serializer) {
    this.workerProcess = workerProcess;
    this.serializer = serializer;
  }

  public ReturnResultMessage sendMessageAndWaitForResponse(
      InvocationMessage message)
      throws Exception {
    String serializedMessage = serializer.serializeInvocation(message);
    WorkerJobResult result = workerProcess.submitAndWaitForJob(serializedMessage);
    ReturnResultMessage resultMessage = serializer.deserializeResult(result.getStdout().orElse(""));
    return resultMessage;
  }
}
