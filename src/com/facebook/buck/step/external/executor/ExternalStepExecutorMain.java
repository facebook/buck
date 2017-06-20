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

package com.facebook.buck.step.external.executor;

import com.facebook.buck.worker.WorkerProcessProtocol;
import com.facebook.buck.worker.WorkerProcessProtocolZero;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** Entry point to out-of-process step executor */
public class ExternalStepExecutorMain {

  public static void main(String[] args) {
    AtomicInteger messageCounter = new AtomicInteger();

    try {
      WorkerProcessProtocol.CommandReceiver workerProcessProtocol = null;
      try {
        workerProcessProtocol =
            new WorkerProcessProtocolZero.CommandReceiver(System.out, System.in);

        workerProcessProtocol.handshake(messageCounter.getAndIncrement());
        while (true) {
          if (workerProcessProtocol.shouldClose()) {
            workerProcessProtocol.close();
            break;
          }
          int messageId = messageCounter.getAndIncrement();
          workerProcessProtocol.receiveCommand(messageId);
          workerProcessProtocol.sendResponse(messageId, "result", 0);
        }
      } finally {
        if (workerProcessProtocol != null) {
          workerProcessProtocol.close();
        }
      }
    } catch (IOException e) {
      System.exit(1);
    }
  }
}
