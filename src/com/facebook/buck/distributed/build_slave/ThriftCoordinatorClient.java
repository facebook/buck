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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.distributed.thrift.CoordinatorService;
import com.facebook.buck.distributed.thrift.CoordinatorService.Client;
import com.facebook.buck.distributed.thrift.GetWorkRequest;
import com.facebook.buck.distributed.thrift.GetWorkResponse;
import com.facebook.buck.distributed.thrift.ReportMinionAliveRequest;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.log.Logger;
import com.facebook.buck.log.TimedLogger;
import com.facebook.buck.slb.ThriftException;
import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransportException;

/** This class is ThreadSafe. No more than one RPC request is allowed at a time. */
@ThreadSafe
public class ThriftCoordinatorClient implements Closeable {
  private static final TimedLogger LOG = new TimedLogger(Logger.get(ThriftCoordinatorClient.class));
  private final String remoteHost;
  private final StampedeId stampedeId;
  private final int connectionTimeoutMillis;

  // NOTE(ruibm): Thrift Transport/Client is not thread safe so we can not interleave requests.
  //              All RPC calls from the client need to be synchronised.
  @Nullable private TFramedTransport transport;
  @Nullable private CoordinatorService.Client client;

  public ThriftCoordinatorClient(
      String remoteHost, StampedeId stampedeId, int connectionTimeoutMillis) {
    this.remoteHost = Preconditions.checkNotNull(remoteHost);
    this.stampedeId = stampedeId;
    this.connectionTimeoutMillis = connectionTimeoutMillis;
  }

  /** Starts the thrift client. */
  public synchronized ThriftCoordinatorClient start(int remotePort) throws ThriftException {
    LOG.info("Starting ThriftCoordinatorClient (for MinionModeRunner)...");
    transport = new TFramedTransport(new TSocket(remoteHost, remotePort, connectionTimeoutMillis));

    try {
      transport.open();
    } catch (TTransportException e) {
      throw new ThriftException(e);
    }

    TProtocol protocol = new TBinaryProtocol(transport);
    client = new CoordinatorService.Client(protocol);
    LOG.info("Started ThriftCoordinatorClient.");
    return this;
  }

  /** Orderly stops the thrift client. */
  public synchronized ThriftCoordinatorClient stop() {
    Preconditions.checkNotNull(transport, "The client has already been stopped.");
    LOG.info("Stopping ThriftCoordinatorClient (for MinionModeRunner)...");
    transport.close();
    LOG.info("Stopped ThriftCoordinatorClient.");
    transport = null;
    client = null;
    return this;
  }

  /** Requests for more work from the Coordinator to build locally. */
  public synchronized GetWorkResponse getWork(
      String minionId, int minionExitCode, List<String> finishedTargets, int maxWorkUnitsToFetch)
      throws IOException {
    LOG.info(
        String.format(
            "Sending GetWorkRequest. Minion [%s] is reporting that it finished building [%s] items. Requesting [%s] items.",
            minionId, finishedTargets.size(), maxWorkUnitsToFetch));
    Client checkedClient = checkThriftClientRunning();

    GetWorkRequest request =
        new GetWorkRequest()
            .setStampedeId(stampedeId)
            .setMinionId(minionId)
            .setFinishedTargets(finishedTargets)
            .setMaxWorkUnitsToFetch(maxWorkUnitsToFetch)
            .setLastExitCode(minionExitCode);

    try {
      GetWorkResponse work = checkedClient.getWork(request);
      LOG.info(String.format("Finished sending GetWorkRequest. MinionId: %s.", minionId));
      return work;
    } catch (TException ex) {
      throw handleTException(ex, "GetWorkRequest");
    } catch (RuntimeException ex) {
      throw handleRuntimeException(ex, "GetWorkRequest");
    }
  }

  /** Reports back to the Coordinator that the current Minion is alive and healthy. */
  public synchronized void reportMinionAlive(String minionId) throws ThriftException {
    LOG.info("Sending ReportMinionAliveRequest.");
    Client checkedClient = checkThriftClientRunning();

    ReportMinionAliveRequest request =
        new ReportMinionAliveRequest().setMinionId(minionId).setStampedeId(stampedeId);
    try {
      checkedClient.reportMinionAlive(request);
      LOG.info("Finished sending ReportMinionAliveRequest.");
    } catch (TException ex) {
      throw handleTException(ex, "ReportMinionAliveRequest");
    } catch (RuntimeException ex) {
      throw handleRuntimeException(ex, "ReportMinionAliveRequest");
    }
  }

  @Override
  public synchronized void close() throws ThriftException {
    if (client != null) {
      LOG.info("Closing ThriftCoordinatorClient.");
      stop();
      LOG.info("Closed ThriftCoordinatorClient.");
    }
  }

  private ThriftException handleTException(TException ex, String requestType) {
    String msg = requestType + " failed with TException.";
    LOG.error(ex, msg);
    return new ThriftException(msg, ex);
  }

  private RuntimeException handleRuntimeException(RuntimeException ex, String requestType) {
    LOG.error(ex, requestType + " failed with RuntimeException. Shutting down client..");
    stop();
    return ex;
  }

  private CoordinatorService.Client checkThriftClientRunning() {
    if (client == null) {
      // Immediately log the error, with stack trace. Otherwise might not appear
      // until Buck has finished shutting down if it crashed the application.
      try {
        throw new RuntimeException(
            "Request received, but client was not started, or has already stopped.");
      } catch (RuntimeException ex) {
        LOG.error(ex);
        throw ex;
      }
    }

    return client;
  }
}
