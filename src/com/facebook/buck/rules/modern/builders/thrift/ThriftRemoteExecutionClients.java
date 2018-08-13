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
import java.io.Closeable;
import java.io.IOException;
import org.apache.thrift.async.TAsyncClientManager;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TNonblockingSocket;
import org.apache.thrift.transport.TNonblockingTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransportException;

/** Thrift clients for the Thrift-based remote execution services. */
class ThriftRemoteExecutionClients implements Closeable {

  private static final int SOCKET_TIMEOUT_MILLIS = 1000 * 10; // 10 seconds

  private final TFramedTransport casTransport;
  private final TFramedTransport remoteExecutionTransport;
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
        new TFramedTransport(
            new TSocket(
                remoteExecutionEngineHost, remoteExecutionEnginePort, SOCKET_TIMEOUT_MILLIS));
    remoteExecutionTransport.open();
    TProtocol protocol = new TBinaryProtocol(remoteExecutionTransport);
    executionEngineClient = new ExecutionEngine.Client(protocol);

    // CAS Sync client
    casTransport = new TFramedTransport(new TSocket(casHost, casPort, SOCKET_TIMEOUT_MILLIS));
    casTransport.open();
    protocol = new TBinaryProtocol(casTransport);
    casClient = new ContentAddressableStorage.Client(protocol);

    // CAS Async client
    clientManager = new TAsyncClientManager();
    nonblockingCasTransport = new TNonblockingSocket(casHost, casPort, SOCKET_TIMEOUT_MILLIS);
    TProtocolFactory protocolFactory = new TBinaryProtocol.Factory();
    asyncCasClient =
        new ContentAddressableStorage.AsyncClient(
            protocolFactory, clientManager, nonblockingCasTransport);
  }

  @Override
  public void close() throws IOException {
    casTransport.close();
    nonblockingCasTransport.close();
    remoteExecutionTransport.close();
    clientManager.stop();
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
