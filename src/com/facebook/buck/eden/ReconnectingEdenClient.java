/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.eden;

import com.facebook.buck.io.unixsocket.UnixDomainSocket;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.timing.Clock;
import com.facebook.eden.thrift.EdenError;
import com.facebook.eden.thrift.EdenService;
import com.facebook.eden.thrift.MountInfo;
import com.facebook.eden.thrift.SHA1Result;
import com.facebook.thrift.TException;
import com.facebook.thrift.protocol.TBinaryProtocol;
import com.facebook.thrift.protocol.TProtocol;
import com.facebook.thrift.transport.TSocket;
import com.facebook.thrift.transport.TTransport;
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
final class ReconnectingEdenClient implements EdenClient {

  @FunctionalInterface
  interface ThriftClientFactory {
    EdenService.Client createNewThriftClient() throws IOException, TTransportException;
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
  @Nullable private EdenService.Client thriftClient;
  private long lastSuccessfulRequest = 0;

  /** @param socketFile to connect to when creating a Thrift client. */
  ReconnectingEdenClient(final Path socketFile, Clock clock) {
    this(
        () -> {
          // Creates a new EdenService.Client by creating a new connection via the socketFile.
          UnixDomainSocket socket = UnixDomainSocket.createSocketWithPath(socketFile);
          TTransport transport = new TSocket(socket);
          // No need to invoke transport.open() because the UnixDomainSocket is already connected.
          TProtocol protocol = new TBinaryProtocol(transport);
          return new EdenService.Client(protocol);
        },
        clock);
  }

  @VisibleForTesting
  ReconnectingEdenClient(ThriftClientFactory thriftClientFactory, Clock clock) {
    this.thriftClientFactory = thriftClientFactory;
    this.clock = clock;
  }

  @Override
  public List<SHA1Result> getSHA1(String mountPoint, List<String> paths)
      throws EdenError, IOException, TException {
    try {
      return attemptGetSHA1(mountPoint, paths);
    } catch (TException e) {
      investigateAndPossiblyRethrowException(e);
    }

    return attemptGetSHA1(mountPoint, paths);
  }

  private List<SHA1Result> attemptGetSHA1(String mountPoint, List<String> paths)
      throws EdenError, IOException, TException {
    List<SHA1Result> sha1s = getConnectedClient().getSHA1(mountPoint, paths);
    lastSuccessfulRequest = clock.currentTimeMillis();
    return sha1s;
  }

  @Override
  public List<String> getBindMounts(String mountPoint) throws EdenError, IOException, TException {
    try {
      return attemptGetBindMounts(mountPoint);
    } catch (TException e) {
      investigateAndPossiblyRethrowException(e);
    }

    return attemptGetBindMounts(mountPoint);
  }

  private List<String> attemptGetBindMounts(String mountPoint)
      throws EdenError, IOException, TException {
    List<String> bindMounts = getConnectedClient().getBindMounts(mountPoint);
    lastSuccessfulRequest = clock.currentTimeMillis();
    return bindMounts;
  }

  @Override
  public List<MountInfo> listMounts() throws EdenError, IOException, TException {
    try {
      return attemptListMounts();
    } catch (TException e) {
      investigateAndPossiblyRethrowException(e);
    }

    return attemptListMounts();
  }

  private List<MountInfo> attemptListMounts() throws EdenError, IOException, TException {
    List<MountInfo> mountInfos = getConnectedClient().listMounts();
    lastSuccessfulRequest = clock.currentTimeMillis();
    return mountInfos;
  }

  /**
   * @return a client that we believe to have an active connection. After this client is used to
   *     make an RPC, {@link #lastSuccessfulRequest} should be updated to {@link
   *     System#currentTimeMillis()}.
   */
  private EdenService.Client getConnectedClient() throws IOException, TTransportException {
    boolean exceededTimeoutThreshold = false;
    if (thriftClient == null
        || (exceededTimeoutThreshold =
            clock.currentTimeMillis() - lastSuccessfulRequest >= IDLE_TIME_THRESHOLD_IN_MILLIS)) {
      if (exceededTimeoutThreshold) {
        LOG.info("Creating a new Thrift client because current client was idle for too long.");
      }
      thriftClient = thriftClientFactory.createNewThriftClient();
      lastSuccessfulRequest = 0;
    }
    return thriftClient;
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
}
