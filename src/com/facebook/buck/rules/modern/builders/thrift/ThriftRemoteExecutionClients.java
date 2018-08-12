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

  private final TFramedTransport transport;
  private final TNonblockingTransport nonblockingTransport;
  private final TAsyncClientManager clientManager;

  private final ContentAddressableStorage.Client casClient;
  private final ContentAddressableStorage.AsyncClient asyncCasClient;
  private final ExecutionEngine.Client executionEngineClient;

  ThriftRemoteExecutionClients(String host, int port) throws TTransportException, IOException {
    // Sync clients:
    transport = new TFramedTransport(new TSocket(host, port, SOCKET_TIMEOUT_MILLIS));
    transport.open();
    TProtocol protocol = new TBinaryProtocol(transport);

    casClient = new ContentAddressableStorage.Client(protocol);
    executionEngineClient = new ExecutionEngine.Client(protocol);

    // Async clients:
    clientManager = new TAsyncClientManager();
    nonblockingTransport = new TNonblockingSocket(host, port, SOCKET_TIMEOUT_MILLIS);
    TProtocolFactory protocolFactory = new TBinaryProtocol.Factory();

    asyncCasClient =
        new ContentAddressableStorage.AsyncClient(
            protocolFactory, clientManager, nonblockingTransport);
  }

  @Override
  public void close() throws IOException {
    transport.close();
    nonblockingTransport.close();
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
