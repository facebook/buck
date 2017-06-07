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

public interface WorkerProcessProtocol {

  /** When worker process starts up, main process and worker process should send handshakes. */
  void sendHandshake(int handshakeID) throws IOException;

  /** This method expects to receive a handshake from the other end. */
  void receiveHandshake(int handshakeID) throws IOException;

  /** Send the given command to the other end for invocation. */
  void sendCommand(int messageID, WorkerProcessCommand command) throws IOException;

  /** This method expects to receive a command to invoke on this end. */
  WorkerProcessCommand receiveCommand(int messageID) throws IOException;

  /** Sends a response for previously received command. */
  void sendCommandResponse(int messageID, String type, int exitCode) throws IOException;

  /** This method expects to receive a response for previously sent command. */
  int receiveCommandResponse(int messageID) throws IOException;

  /** Close connection and properly end the stream. */
  void close() throws IOException;
}
