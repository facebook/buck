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
import com.facebook.buck.distributed.thrift.GetWorkRequest;
import com.facebook.buck.distributed.thrift.GetWorkResponse;
import com.facebook.buck.distributed.thrift.ReportMinionAliveRequest;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.log.Logger;
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

  private static final Logger LOG = Logger.get(ThriftCoordinatorClient.class);

  private final String remoteHost;
  private final StampedeId stampedeId;

  // NOTE(ruibm): Thrift Transport/Client is not thread safe so we can not interleave requests.
  //              All RPC calls from the client need to be synchronised.
  @Nullable private TFramedTransport transport;
  @Nullable private CoordinatorService.Client client;

  public ThriftCoordinatorClient(String remoteHost, StampedeId stampedeId) {
    this.remoteHost = Preconditions.checkNotNull(remoteHost);
    this.stampedeId = stampedeId;
  }

  /** Starts the thrift client. */
  public synchronized ThriftCoordinatorClient start(int remotePort) throws ThriftException {
    transport = new TFramedTransport(new TSocket(remoteHost, remotePort));

    try {
      transport.open();
    } catch (TTransportException e) {
      throw new ThriftException(e);
    }

    TProtocol protocol = new TBinaryProtocol(transport);
    client = new CoordinatorService.Client(protocol);
    return this;
  }

  /** Orderly stops the thrift client. */
  public synchronized ThriftCoordinatorClient stop() {
    Preconditions.checkNotNull(transport, "The client has already been stopped.");
    transport.close();
    transport = null;
    client = null;
    return this;
  }

  /** Requests for more work from the Coordinator to build locally. */
  public synchronized GetWorkResponse getWork(
      String minionId, int minionExitCode, List<String> finishedTargets, int maxWorkUnitsToFetch)
      throws IOException {
    LOG.debug(
        String.format(
            "Sending GetWorkRequest. Minion [%s] is reporting that it finished building [%s] items. Requesting [%s] items.",
            minionId, finishedTargets.size(), maxWorkUnitsToFetch));
    Preconditions.checkNotNull(client, "Client was not started.");

    GetWorkRequest request =
        new GetWorkRequest()
            .setStampedeId(stampedeId)
            .setMinionId(minionId)
            .setFinishedTargets(finishedTargets)
            .setMaxWorkUnitsToFetch(maxWorkUnitsToFetch)
            .setLastExitCode(minionExitCode);

    try {
      GetWorkResponse work = client.getWork(request);
      LOG.debug(String.format("Minion [%s] finished sending GetWorkRequest", minionId));
      return work;
    } catch (TException e) {
      throw new ThriftException(e);
    }
  }

  /** Reports back to the Coordinator that the current Minion is alive and healthy. */
  public synchronized void reportMinionAlive(String minionId) throws ThriftException {
    ReportMinionAliveRequest request =
        new ReportMinionAliveRequest().setMinionId(minionId).setStampedeId(stampedeId);
    try {
      LOG.debug(
          String.format(
              "Minion [%s] is sending still alive heartbeat to coordinator for stampedeId [%s]",
              minionId, stampedeId.toString()));
      client.reportMinionAlive(request);
      LOG.debug(
          String.format(
              "Minion [%s] finished sending still alive heartbeat to coordinator for stampedeId [%s]",
              minionId, stampedeId.toString()));
    } catch (TException e) {
      String msg =
          String.format(
              "Failed to report Minion [%s] is alive for stampedeId [%s].",
              minionId, stampedeId.toString());
      LOG.error(e, msg);
      throw new ThriftException(msg, e);
    }
  }

  @Override
  public synchronized void close() throws ThriftException {
    if (client != null) {
      stop();
    }
  }
}
