/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.edenfs;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.timing.Clock;
import com.facebook.eden.thrift.EdenError;
import com.facebook.eden.thrift.MountInfo;
import com.facebook.eden.thrift.SHA1Result;
import com.facebook.thrift.TException;
import com.facebook.thrift.transport.TTransportException;
import com.google.common.annotations.VisibleForTesting;
import com.sun.jna.LastErrorException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;

/**
 * {@link EdenClient} that will retry the RPC if it appears that the failure was due to a stale
 * Thrift client. Note that this is safe because the RPCs exposed by {@link EdenClient} are
 * idempotent.
 *
 * <p>This class is not thread-safe. It is designed to be used with a {@link ThreadLocal}.
 */
final class ReconnectingEdenClient implements EdenClientResource {

  @FunctionalInterface
  interface ThriftClientFactory {
    EdenClientResource createNewThriftClient() throws IOException, TTransportException;
  }

  private static final Logger LOG = Logger.get(ReconnectingEdenClient.class);

  /**
   * Eden closes the connection to a Thrift client if it has not made a request in the last 60
   * seconds. To be conservative, we do not try to reuse a Thrift connection if it has not been used
   * in the last 50 seconds.
   */
  @VisibleForTesting static final long IDLE_TIME_THRESHOLD_IN_MILLIS = 50 * 1000;

  private final ThriftClientFactory thriftClientFactory;
  private final Clock clock;
  private volatile boolean closed = false;
  @Nullable private volatile EdenClientResource thriftClient;
  private long lastSuccessfulRequest = 0;

  /** @param socketFile to connect to when creating a Thrift client. */
  ReconnectingEdenClient(Path socketFile, Clock clock) {
    this(() -> EdenClientResourceImpl.connect(socketFile), clock);
  }

  @VisibleForTesting
  ReconnectingEdenClient(ThriftClientFactory thriftClientFactory, Clock clock) {
    this.thriftClientFactory = thriftClientFactory;
    this.clock = clock;
  }

  private interface RetriableOperation<R> {
    R invoke() throws IOException, TException, EdenError;
  }

  private class ClientImpl implements EdenClient {
    private <R> R withRetry(RetriableOperation<R> op) throws IOException, TException, EdenError {
      try {
        R r = op.invoke();
        lastSuccessfulRequest = clock.currentTimeMillis();
        return r;
      } catch (TException e) {
        investigateAndPossiblyRethrowException(e);
      }

      R r = op.invoke();
      lastSuccessfulRequest = clock.currentTimeMillis();
      return r;
    }

    @Override
    public List<SHA1Result> getSHA1(byte[] mountPoint, List<byte[]> paths)
        throws IOException, TException, EdenError {
      return withRetry(() -> getConnectedClient().getSHA1(mountPoint, paths));
    }

    @Override
    public List<MountInfo> listMounts() throws EdenError, IOException, TException {
      return withRetry(() -> getConnectedClient().listMounts());
    }
  }

  /**
   * @return a client that we believe to have an active connection. After this client is used to
   *     make an RPC, {@link #lastSuccessfulRequest} should be updated to {@link
   *     System#currentTimeMillis()}.
   */
  private EdenClient getConnectedClient() throws IOException, TTransportException {
    if (closed) {
      throw new IOException("client closed");
    }

    boolean exceededTimeoutThreshold = false;
    EdenClientResource thriftClient = this.thriftClient;
    if (thriftClient == null
        || (exceededTimeoutThreshold =
            clock.currentTimeMillis() - lastSuccessfulRequest >= IDLE_TIME_THRESHOLD_IN_MILLIS)) {
      if (exceededTimeoutThreshold) {
        LOG.info("Creating a new Thrift client because current client was idle for too long.");
      }

      if (thriftClient != null) {
        try {
          thriftClient.close();
        } catch (Exception e) {
          LOG.warn(e, "Failed to close thrift client");
        }
      }
      this.thriftClient = null;

      thriftClient = this.thriftClient = thriftClientFactory.createNewThriftClient();
      lastSuccessfulRequest = 0;
    }

    // Make sure it stays closed if another thread closed it while we initialized new client.
    if (closed) {
      close();
      throw new IOException("client closed");
    }

    return thriftClient.getEdenClient();
  }

  /**
   * Logic that decides how to proceed after a failed Thrift RPC. If it appears to be a stale
   * connection, this method will not throw as a signal to retry the RPC. Otherwise, it rethrows the
   * exception that was passed in.
   */
  private void investigateAndPossiblyRethrowException(TException originalException)
      throws TException {
    // Because we got a TException when making a Thrift call, we will dereference the current Thrift
    // client such that getConnectedClient() will create a new one the next time it is called.
    thriftClient = null;

    Throwable e = originalException;
    while (e != null && !(e instanceof LastErrorException)) {
      e = e.getCause();
    }

    if (e != null) {
      // e is a LastErrorException, so it's likely that it was a "Broken pipe" exception, which
      // happens when the Eden server decides the Thrift client has been idle for too long and
      // closes the connection.
      LOG.info(e, "Suspected closed Thrift connection: will create a new one.");
    } else {
      throw originalException;
    }
  }

  private final ClientImpl client = new ClientImpl();

  @Override
  public EdenClient getEdenClient() {
    return client;
  }

  @Override
  public void close() throws IOException {
    closed = true;
    EdenClientResource thriftClient = this.thriftClient;
    if (thriftClient != null) {
      thriftClient.close();
    }
    this.thriftClient = null;
  }
}
