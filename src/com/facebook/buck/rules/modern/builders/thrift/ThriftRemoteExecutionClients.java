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

import com.facebook.remoteexecution.cas.ContentAddressableStorage;
import com.facebook.remoteexecution.executionengine.ExecutionEngine;
import com.facebook.thrift.async.TAsyncClientManager;
import com.facebook.thrift.protocol.TCompactProtocol;
import com.facebook.thrift.protocol.THeaderProtocol;
import com.facebook.thrift.transport.THeaderTransport;
import com.facebook.thrift.transport.TNonblockingSocket;
import com.facebook.thrift.transport.TNonblockingTransport;
import com.facebook.thrift.transport.TSocket;
import com.facebook.thrift.transport.TTransportException;
import java.io.Closeable;
import java.io.IOException;

/** Thrift clients for the Thrift-based remote execution services. */
class ThriftRemoteExecutionClients implements Closeable {

  private static final int SOCKET_TIMEOUT_MILLIS = 1000 * 10; // 10 seconds
  private static final int CONNECTION_TIMEOUT_MILLIS = 1000 * 10; // 10 seconds

  private final THeaderTransport casTransport;
  private final THeaderTransport remoteExecutionTransport;
  private final TNonblockingTransport nonblockingCasTransport;
  private final TAsyncClientManager clientManager;

  private final ContentAddressableStorage.Client casClient;
  private final ContentAddressableStorage.AsyncClient asyncCasClient;
  private final ExecutionEngine.Client executionEngineClient;

  ThriftRemoteExecutionClients(
      String remoteExecutionEngineHost, int remoteExecutionEnginePort, String casHost, int casPort)
      throws TTransportException, IOException {

    // RE client
    remoteExecutionTransport =
        new THeaderTransport(
            new TSocket(
                remoteExecutionEngineHost,
                remoteExecutionEnginePort,
                SOCKET_TIMEOUT_MILLIS,
                CONNECTION_TIMEOUT_MILLIS));

    remoteExecutionTransport.setHeader(
        "request_timeout", Integer.toString((int) (CONNECTION_TIMEOUT_MILLIS * 0.8)));

    remoteExecutionTransport.open();
    executionEngineClient =
        new ExecutionEngine.Client(new THeaderProtocol(remoteExecutionTransport));

    // CAS Sync client
    casTransport = new THeaderTransport(new TSocket(casHost, casPort, SOCKET_TIMEOUT_MILLIS));
    casTransport.open();
    casClient = new ContentAddressableStorage.Client(new THeaderProtocol(casTransport));

    // CAS Async client
    clientManager = new TAsyncClientManager();
    nonblockingCasTransport = new TNonblockingSocket(casHost, casPort, SOCKET_TIMEOUT_MILLIS);
    asyncCasClient =
        new ContentAddressableStorage.AsyncClient(
            new TCompactProtocol.Factory(), clientManager, nonblockingCasTransport);
  }

  @Override
  public void close() throws IOException {
    casTransport.close();
    nonblockingCasTransport.close();
    remoteExecutionTransport.close();

    try {
      clientManager.stop();
    } catch (InterruptedException e) {
      // TODO(orr): How should we handle interrupted exception here? Probably swallow it.
      e.printStackTrace();
    }
  }

  public ContentAddressableStorage.Client getCasClient() {
    return casClient;
  }

  public ContentAddressableStorage.AsyncClient getAsyncCasClient() {
    return asyncCasClient;
  }

  public ExecutionEngine.Client getExecutionEngineClient() {
    return executionEngineClient;
  }
}
