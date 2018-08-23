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
import com.facebook.remoteexecution.cas.ContentAddressableStorage.AsyncClient;
import com.facebook.remoteexecution.executionengine.ExecutionEngine;
import com.facebook.thrift.async.TAsyncClientManager;
import com.facebook.thrift.protocol.TCompactProtocol;
import com.facebook.thrift.protocol.THeaderProtocol;
import com.facebook.thrift.transport.THeaderTransport;
import com.facebook.thrift.transport.TNonblockingSocket;
import com.facebook.thrift.transport.TNonblockingTransport;
import com.facebook.thrift.transport.TSocket;
import com.facebook.thrift.transport.TTransport;
import com.facebook.thrift.transport.TTransportException;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.concurrent.GuardedBy;

/** Thrift clients for the Thrift-based remote execution services. */
class ThriftRemoteExecutionClients implements Closeable {

  private static final int SOCKET_TIMEOUT_MILLIS = 1000 * 10; // 10 seconds
  private static final int CONNECTION_TIMEOUT_MILLIS = 1000 * 10; // 10 seconds

  private final String remoteExecutionHost;
  private final int remoteExecutionPort;

  private final String casHost;
  private final int casPort;

  private final Object internalStateLock = new Object();

  @GuardedBy("internalStateLock")
  private final List<TTransport> transportsToClose;

  @GuardedBy("internalStateLock")
  private final List<TAsyncClientManager> clientManagersToClose;

  ThriftRemoteExecutionClients(
      String remoteExecutionEngineHost,
      int remoteExecutionEnginePort,
      String casHost,
      int casPort) {
    this.remoteExecutionHost = remoteExecutionEngineHost;
    this.remoteExecutionPort = remoteExecutionEnginePort;
    this.casHost = casHost;
    this.casPort = casPort;
    transportsToClose = new ArrayList<>();
    clientManagersToClose = new ArrayList<>();
  }

  @Override
  public void close() throws IOException {
    synchronized (internalStateLock) {
      for (TTransport transport : transportsToClose) {
        transport.close();
      }

      for (TAsyncClientManager clientManager : clientManagersToClose) {
        try {
          clientManager.stop();
        } catch (InterruptedException e) {
          // TODO(orr): How should we handle interrupted exception here? Probably swallow it.
          e.printStackTrace();
        }
      }
    }
  }

  public ContentAddressableStorage.Client createCasClient() throws TTransportException {
    THeaderTransport casTransport =
        new THeaderTransport(new TSocket(casHost, casPort, SOCKET_TIMEOUT_MILLIS));
    casTransport.open();
    synchronized (internalStateLock) {
      transportsToClose.add(casTransport);
    }
    return new ContentAddressableStorage.Client(new THeaderProtocol(casTransport));
  }

  public ThriftAsyncClientFactory<ContentAddressableStorage.AsyncClient>
      createAsyncCasClientFactory() throws IOException {
    TAsyncClientManager clientManager = new TAsyncClientManager();
    synchronized (internalStateLock) {
      clientManagersToClose.add(clientManager);
    }

    ContentAddressableStorage.AsyncClient.Factory internalFactory =
        new ContentAddressableStorage.AsyncClient.Factory(
            clientManager, new TCompactProtocol.Factory());

    return new ThriftAsyncClientFactory<AsyncClient>(internalFactory) {
      @Override
      protected TNonblockingTransport createTransport() throws IOException, TTransportException {
        TNonblockingSocket nonblockingCasTransport =
            new TNonblockingSocket(casHost, casPort, SOCKET_TIMEOUT_MILLIS);
        synchronized (internalStateLock) {
          transportsToClose.add(nonblockingCasTransport);
        }
        return nonblockingCasTransport;
      }
    };
  }

  public ExecutionEngine.Client createExecutionEngineClient() throws TTransportException {
    THeaderTransport remoteExecutionTransport =
        new THeaderTransport(
            new TSocket(
                remoteExecutionHost,
                remoteExecutionPort,
                SOCKET_TIMEOUT_MILLIS,
                CONNECTION_TIMEOUT_MILLIS));

    remoteExecutionTransport.setHeader(
        "request_timeout", Integer.toString((int) (CONNECTION_TIMEOUT_MILLIS * 0.8)));

    remoteExecutionTransport.open();
    synchronized (internalStateLock) {
      transportsToClose.add(remoteExecutionTransport);
    }
    return new ExecutionEngine.Client(new THeaderProtocol(remoteExecutionTransport));
  }
}
