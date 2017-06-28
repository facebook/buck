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

import com.facebook.buck.worker.WorkerJobResult;
import com.facebook.buck.worker.WorkerProcess;
import com.google.common.base.Preconditions;

public class MessageTransport implements AutoCloseable {
  private final WorkerProcess workerProcess;
  private final MessageSerializer serializer;
  private final Runnable onClose;
  private boolean isClosed = false;

  public MessageTransport(
      WorkerProcess workerProcess, MessageSerializer serializer, Runnable onClose) {
    this.workerProcess = workerProcess;
    this.serializer = serializer;
    this.onClose = onClose;
  }

  public ReturnResultMessage sendMessageAndWaitForResponse(InvocationMessage message)
      throws Exception {
    checkNotClose();
    String serializedMessage = serializer.serializeInvocation(message);
    workerProcess.ensureLaunchAndHandshake();
    WorkerJobResult result = workerProcess.submitAndWaitForJob(serializedMessage);
    ReturnResultMessage resultMessage = serializer.deserializeResult(result.getStdout().orElse(""));
    return resultMessage;
  }

  @Override
  public void close() {
    checkNotClose();
    onClose.run();
    isClosed = true;
  }

  private void checkNotClose() {
    Preconditions.checkState(
        !isClosed,
        "%s <%d> is already closed",
        this.getClass().getSimpleName(),
        System.identityHashCode(this));
  }
}
