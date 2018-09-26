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

package com.facebook.buck.remoteexecution.thrift;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.remoteexecution.cas.BatchReadBlobsRequest;
import com.facebook.remoteexecution.cas.BatchReadBlobsResponse;
import com.facebook.remoteexecution.cas.BatchUpdateBlobsRequest;
import com.facebook.remoteexecution.cas.BatchUpdateBlobsResponse;
import com.facebook.remoteexecution.cas.ContentAddressableStorage;
import com.facebook.remoteexecution.cas.ContentAddressableStorage.Iface;
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
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import javax.annotation.concurrent.GuardedBy;

/** Thrift clients for the Thrift-based remote execution services. */
public class ThriftRemoteExecutionClientsFactory implements Closeable {
  private static final int SOCKET_TIMEOUT_MILLIS = 1000 * 30; // 30 seconds
  private static final int CONNECTION_TIMEOUT_MILLIS = 1000 * 10; // 10 seconds

  private final int[] kRetryBackoffMillis = {0, 75, 150};

  private final String remoteExecutionHost;
  private final int remoteExecutionPort;
  private final String casHost;
  private final int casPort;

  private final Object internalStateLock = new Object();

  @GuardedBy("internalStateLock")
  private final List<TTransport> transportsToClose;

  public ThriftRemoteExecutionClientsFactory(RemoteExecutionConfig config) {
    this(config.getRemoteHost(), config.getRemotePort(), config.getCasHost(), config.getCasPort());
  }

  ThriftRemoteExecutionClientsFactory(
      String remoteExecutionHost, int remoteExecutionPort, String casHost, int casPort) {
    this.remoteExecutionHost = remoteExecutionHost;
    this.remoteExecutionPort = remoteExecutionPort;
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
            throw new BuckUncheckedExecutionException(
                e, "Unable to create a connection to the CAS.");
          }
          return new ContentAddressableStorage.Client(new THeaderProtocol(transport));
        },
        kRetryBackoffMillis);
  }

  public ExecutionEngine.Iface createExecutionEngineClient() {
    return new RetryingSynchronizedEngineClient(
        () -> {
          THeaderTransport transport = null;
          try {
            transport = createBlockingTransport(remoteExecutionHost, remoteExecutionPort);
          } catch (TTransportException e) {
            throw new BuckUncheckedExecutionException(
                e, "Unable to create a connection to the RemoteExecutionEngine.");
          }
          return new ExecutionEngine.Client(new THeaderProtocol(transport));
        },
        kRetryBackoffMillis);
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
    private static final Logger LOG = Logger.get(RetryingSynchronizedCASClient.class);

    RetryingSynchronizedThriftHelper<Iface> networkHelper;

    public RetryingSynchronizedCASClient(
        Supplier<ContentAddressableStorage.Iface> clientSupplier, int[] retryBackoffMillis) {
      networkHelper = new RetryingSynchronizedThriftHelper<>(clientSupplier, retryBackoffMillis);
    }

    private <ReturnT> ReturnT handleExceptions(Callable<ReturnT> foo)
        throws ContentAddressableStorageException, TException {
      try {
        return foo.call();
      } catch (TException | ContentAddressableStorageException e) {
        LOG.error(e);
        throw e;
      } catch (Exception e) {
        LOG.error(e);
        if (e.getCause() != null && e.getCause() instanceof ContentAddressableStorageException) {
          throw (ContentAddressableStorageException) e.getCause();
        }

        throw new BuckUncheckedExecutionException(e);
      }
    }

    @Override
    public UpdateBlobResponse updateBlob(UpdateBlobRequest request)
        throws ContentAddressableStorageException, TException {
      return handleExceptions(
          () -> networkHelper.retryOnNetworkException(client -> client.updateBlob(request)));
    }

    @Override
    public BatchUpdateBlobsResponse batchUpdateBlobs(BatchUpdateBlobsRequest request)
        throws ContentAddressableStorageException, TException {
      return handleExceptions(
          () -> networkHelper.retryOnNetworkException(client -> client.batchUpdateBlobs(request)));
    }

    @Override
    public ReadBlobResponse readBlob(ReadBlobRequest request)
        throws ContentAddressableStorageException, TException {
      return handleExceptions(
          () -> networkHelper.retryOnNetworkException(client -> client.readBlob(request)));
    }

    @Override
    public BatchReadBlobsResponse batchReadBlobs(BatchReadBlobsRequest request)
        throws ContentAddressableStorageException, TException {
      return handleExceptions(
          () -> networkHelper.retryOnNetworkException(client -> client.batchReadBlobs(request)));
    }

    @Override
    public FindMissingBlobsResponse findMissingBlobs(FindMissingBlobsRequest request)
        throws ContentAddressableStorageException, TException {
      return handleExceptions(
          () -> networkHelper.retryOnNetworkException(client -> client.findMissingBlobs(request)));
    }

    @Override
    public GetTreeResponse getTree(GetTreeRequest request)
        throws ContentAddressableStorageException, TException {
      return handleExceptions(
          () -> networkHelper.retryOnNetworkException(client -> client.getTree(request)));
    }
  }

  private static final class RetryingSynchronizedEngineClient implements ExecutionEngine.Iface {
    private static final Logger LOG = Logger.get(RetryingSynchronizedEngineClient.class);

    RetryingSynchronizedThriftHelper<ExecutionEngine.Iface> networkHelper;

    private RetryingSynchronizedEngineClient(
        Supplier<ExecutionEngine.Iface> clientSupplier, int[] retryBackoffMillis) {
      this.networkHelper =
          new RetryingSynchronizedThriftHelper<>(clientSupplier, retryBackoffMillis);
    }

    private <ReturnT> ReturnT handleExceptions(Callable<ReturnT> foo)
        throws ExecutionEngineException, TException {
      try {
        return foo.call();
      } catch (ExecutionEngineException | TException e) {
        LOG.error(e);
        throw e;
      } catch (Exception e) {
        LOG.error(e);
        if (e.getCause() != null && e.getCause() instanceof ExecutionEngineException) {
          throw (ExecutionEngineException) e.getCause();
        }

        throw new BuckUncheckedExecutionException(e);
      }
    }

    @Override
    public synchronized ExecuteOperation execute(ExecuteRequest request)
        throws ExecutionEngineException, TException {
      return handleExceptions(
          () -> networkHelper.retryOnNetworkException(client -> client.execute(request)));
    }

    @Override
    public synchronized ExecuteOperation getExecuteOperation(GetExecuteOperationRequest request)
        throws ExecutionEngineException, TException {
      return handleExceptions(
          () ->
              networkHelper.retryOnNetworkException(client -> client.getExecuteOperation(request)));
    }
  }
}
