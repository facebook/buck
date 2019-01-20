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
package com.facebook.buck.worker;

import java.io.IOException;
import java.nio.file.Paths;

public class FakeWorkerProcessProtocol {

  public static class FakeCommandSender implements WorkerProcessProtocol.CommandSender {

    private boolean isClosed = false;

    @Override
    public void handshake(int messageId) {}

    @Override
    public void send(int messageId, WorkerProcessCommand command) {}

    @Override
    public int receiveCommandResponse(int messageID) throws IOException {
      return 0;
    }

    @Override
    public void close() {
      isClosed = true;
    }

    public boolean isClosed() {
      return isClosed;
    }
  }

  public static class FakeCommandReceiver implements WorkerProcessProtocol.CommandReceiver {

    private boolean isClosed = false;

    @Override
    public void handshake(int messageId) {}

    @Override
    public WorkerProcessCommand receiveCommand(int messageId) {
      return WorkerProcessCommand.of(Paths.get(""), Paths.get(""), Paths.get(""));
    }

    @Override
    public void sendResponse(int messageId, String type, int exitCode) {}

    @Override
    public boolean shouldClose() {
      return false;
    }

    @Override
    public void close() {
      isClosed = true;
    }

    public boolean isClosed() {
      return isClosed;
    }
  }
}
