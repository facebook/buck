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
package com.facebook.buck.shell;

import java.io.IOException;

public class FakeWorkerProcessProtocol implements WorkerProcessProtocol {

  private boolean isClosed = false;

  @Override
  public void sendHandshake(int handshakeID) throws IOException {}

  @Override
  public void receiveHandshake(int handshakeID) throws IOException {}

  @Override
  public void sendCommand(int messageID, WorkerProcessCommand command) throws IOException {}

  @Override
  public int receiveCommandResponse(int messageID) throws IOException {
    return 0;
  }

  @Override
  public void close() throws IOException {
    isClosed = true;
  }

  public boolean isClosed() {
    return isClosed;
  }
}
