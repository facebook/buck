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

package com.facebook.buck.rules.modern.builders.thrift;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.modern.builders.IsolatedExecution;
import com.facebook.thrift.transport.TTransportException;
import java.io.IOException;

/** Factory for creating thrift-based strategies. */
public class ThriftRemoteExecutionFactory {

  /** The remote strategy connects to a remote thrift remote execution service. */
  public static IsolatedExecution createRemote(
      String remoteExecutionEngineHost,
      int remoteExecutionEnginePort,
      String casHost,
      int casPort,
      BuckEventBus eventBus)
      throws IOException {
    ThriftRemoteExecutionClients clients =
        new ThriftRemoteExecutionClients(
            remoteExecutionEngineHost, remoteExecutionEnginePort, casHost, casPort);
    try {
      return new ThriftRemoteExecution(eventBus, clients) {
        @Override
        public void close() throws IOException {
          clients.close();
        }
      };
    } catch (TTransportException e) {
      throw new IOException("Could not create ThriftRemoteExecutionClients.", e);
    }
  }
}
