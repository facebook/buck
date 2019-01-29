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

import java.io.Closeable;
import java.io.IOException;

public interface WorkerProcessProtocol {

  interface CommandSender extends Closeable {
    void handshake(int messageId) throws IOException;

    void send(int messageId, WorkerProcessCommand command) throws IOException;

    int receiveCommandResponse(int messageID) throws IOException;

    /** Instructs the CommandReceiver to shut itself down. */
    @Override
    void close() throws IOException;
  }
}
