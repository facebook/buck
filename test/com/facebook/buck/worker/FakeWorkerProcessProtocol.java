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

package com.facebook.buck.worker;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;

public class FakeWorkerProcessProtocol {

  public static class FakeCommandSender implements WorkerProcessProtocol.CommandSender {

    private volatile boolean isClosed = false;
    private final ArrayBlockingQueue<Integer> messageIds = new ArrayBlockingQueue<>(10);

    @Override
    public void handshake(int messageId) {}

    @Override
    public void send(int messageId, WorkerProcessCommand command) throws IOException {
      try {
        messageIds.put(messageId);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public WorkerProcessProtocol.CommandResponse receiveNextCommandResponse() throws IOException {
      if (isClosed) {
        throw new RuntimeException("Closed");
      }
      try {
        return new WorkerProcessProtocol.CommandResponse(messageIds.take(), 0);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public synchronized void close() {
      isClosed = true;
      try {
        messageIds.put(-1);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

    public boolean isClosed() {
      return isClosed;
    }
  }
}
