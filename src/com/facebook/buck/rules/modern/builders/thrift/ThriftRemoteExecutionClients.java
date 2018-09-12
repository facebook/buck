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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.function.ThrowingFunction;
import com.facebook.remoteexecution.cas.BatchReadBlobsRequest;
import com.facebook.remoteexecution.cas.BatchReadBlobsResponse;
import com.facebook.remoteexecution.cas.BatchUpdateBlobsRequest;
import com.facebook.remoteexecution.cas.BatchUpdateBlobsResponse;
import com.facebook.remoteexecution.cas.ContentAddressableStorage;
import com.facebook.remoteexecution.cas.ContentAddressableStorage.Client;
import com.facebook.remoteexecution.cas.ContentAddressableStorageException;
import com.facebook.remoteexecution.cas.FindMissingBlobsRequest;
import com.facebook.remoteexecution.cas.FindMissingBlobsResponse;
import com.facebook.remoteexecution.cas.GetTreeRequest;
import com.facebook.remoteexecution.cas.GetTreeResponse;
import com.facebook.remoteexecution.cas.ReadBlobRequest;
import com.facebook.remoteexecution.cas.ReadBlobResponse;
import com.facebook.remoteexecution.cas.UpdateBlobRequest;
import com.facebook.remoteexecution.cas.UpdateBlobResponse;
import com.facebook.remoteexecution.executionengine.ExecuteOperation;
import com.facebook.remoteexecution.executionengine.ExecuteRequest;
import com.facebook.remoteexecution.executionengine.ExecutionEngine;
import com.facebook.remoteexecution.executionengine.ExecutionEngineException;
import com.facebook.remoteexecution.executionengine.GetExecuteOperationRequest;
import com.facebook.thrift.TException;
import com.facebook.thrift.protocol.THeaderProtocol;
import com.facebook.thrift.transport.THeaderTransport;
import com.facebook.thrift.transport.TSocket;
import com.facebook.thrift.transport.TTransport;
import com.facebook.thrift.transport.TTransportException;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.annotation.concurrent.GuardedBy;

/** Thrift clients for the Thrift-based remote execution services. */
class ThriftRemoteExecutionClients implements Closeable {
  private static final Logger LOG = Logger.get(ThriftRemoteExecutionClients.class);

  private static final int SOCKET_TIMEOUT_MILLIS = 1000 * 30; // 30 seconds
  private static final int CONNECTION_TIMEOUT_MILLIS = 1000 * 10; // 10 seconds

  private final String remoteExecutionHost;
  private final int remoteExecutionPort;

  private final String casHost;
  private final int casPort;
  private final Object internalStateLock = new Object();

  @GuardedBy("internalStateLock")
  private final List<TTransport> transportsToClose;

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
  }

  @Override
  public void close() throws IOException {
    synchronized (internalStateLock) {
      for (TTransport transport : transportsToClose) {
        transport.close();
      }
    }
  }

  public ContentAddressableStorage.Iface createCasClient() {
    return new RetryingSynchronizedCASClient(
        () -> {
          THeaderTransport transport = null;
          try {
            transport = createBlockingTransport(casHost, casPort);
          } catch (TTransportException e) {
            throw new RuntimeException("Unable to create a connection to the CAS.", e);
          }
          return new Client(new THeaderProtocol(transport));
        });
  }

  public ExecutionEngine.Iface createExecutionEngineClient() throws TTransportException {
    THeaderTransport remoteExecutionTransport =
        createBlockingTransport(remoteExecutionHost, remoteExecutionPort);
    return new SynchronizedExecutionEngineClient(
        new ExecutionEngine.Client(new THeaderProtocol(remoteExecutionTransport)));
  }

  private THeaderTransport createBlockingTransport(String host, int port)
      throws TTransportException {
    THeaderTransport transport =
        new THeaderTransport(
            new TSocket(host, port, SOCKET_TIMEOUT_MILLIS, CONNECTION_TIMEOUT_MILLIS));

    transport.setHeader(
        "request_timeout", Integer.toString((int) (CONNECTION_TIMEOUT_MILLIS * 0.8)));

    transport.open();
    synchronized (internalStateLock) {
      transportsToClose.add(transport);
    }

    return transport;
  }

  /**
   * Implementation of the ContentAddressableStorage.Iface which is synchronized and tries to create
   * a new connection using the provided Supplier when it encounters a TTransportException.
   */
  public static final class RetryingSynchronizedCASClient
      implements ContentAddressableStorage.Iface {

    private final int BACKOFF_MILLIS = 100;
    private final int NUM_RETRIES = 2;

    @GuardedBy("this")
    private final Supplier<ContentAddressableStorage.Iface> clientSupplier;

    @GuardedBy("this")
    private ContentAddressableStorage.Iface currentClient;

    public RetryingSynchronizedCASClient(Supplier<ContentAddressableStorage.Iface> clientSupplier) {
      this.clientSupplier = clientSupplier;
      createNewClient();
    }

    private synchronized void createNewClient() {
      currentClient = clientSupplier.get();
    }

    private void backOffDueToFailure() {
      try {
        Thread.sleep(BACKOFF_MILLIS);
      } catch (InterruptedException interrupt) {
        Threads.interruptCurrentThread(); // Re-set the interrupt flag.
        throw new BuckUncheckedExecutionException(interrupt);
      }
    }

    private synchronized Object retryOnTransportException(
        ThrowingFunction<ContentAddressableStorage.Iface, Object, Exception> foo)
        throws ContentAddressableStorageException, TException, TTransportException {
      TTransportException capturedException = null;
      for (int numIter = 0; numIter < NUM_RETRIES; numIter++) {

        try {
          return foo.apply(currentClient);
        } catch (Exception e) {

          if (e instanceof TTransportException) {
            capturedException = (TTransportException) e;
            LOG.warn(
                "Creating a new connection to the CAS after getting a TTransportException. "
                    + "Details=[%s]",
                ThriftUtil.getExceptionDetails(e).orElse("NONE"));

            backOffDueToFailure();
            createNewClient();

          } else if (e instanceof TException) {
            throw (TException) e;
          } else if (e instanceof ContentAddressableStorageException) {
            throw (ContentAddressableStorageException) e;
          } else {
            throw new RuntimeException(e);
          }
        }
      }

      throw capturedException;
    }

    @Override
    public UpdateBlobResponse updateBlob(UpdateBlobRequest request)
        throws ContentAddressableStorageException, TException {
      return (UpdateBlobResponse) retryOnTransportException(client -> client.updateBlob(request));
    }

    @Override
    public BatchUpdateBlobsResponse batchUpdateBlobs(BatchUpdateBlobsRequest request)
        throws ContentAddressableStorageException, TException {
      return (BatchUpdateBlobsResponse)
          retryOnTransportException(client -> client.batchUpdateBlobs(request));
    }

    @Override
    public ReadBlobResponse readBlob(ReadBlobRequest request)
        throws ContentAddressableStorageException, TException {
      return (ReadBlobResponse) retryOnTransportException(client -> client.readBlob(request));
    }

    @Override
    public BatchReadBlobsResponse batchReadBlobs(BatchReadBlobsRequest request)
        throws ContentAddressableStorageException, TException {
      return (BatchReadBlobsResponse)
          retryOnTransportException(client -> client.batchReadBlobs(request));
    }

    @Override
    public FindMissingBlobsResponse findMissingBlobs(FindMissingBlobsRequest request)
        throws ContentAddressableStorageException, TException {
      return (FindMissingBlobsResponse)
          retryOnTransportException(client -> client.findMissingBlobs(request));
    }

    @Override
    public GetTreeResponse getTree(GetTreeRequest request)
        throws ContentAddressableStorageException, TException {
      return (GetTreeResponse) retryOnTransportException(client -> client.getTree(request));
    }
  }

  private static final class SynchronizedExecutionEngineClient implements ExecutionEngine.Iface {
    private final ExecutionEngine.Iface decorated;

    private SynchronizedExecutionEngineClient(ExecutionEngine.Iface decorated) {
      this.decorated = decorated;
    }

    @Override
    public synchronized ExecuteOperation execute(ExecuteRequest request)
        throws ExecutionEngineException, TException {
      return decorated.execute(request);
    }

    @Override
    public synchronized ExecuteOperation getExecuteOperation(GetExecuteOperationRequest request)
        throws ExecutionEngineException, TException {
      return decorated.getExecuteOperation(request);
    }
  }
}
