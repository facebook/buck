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
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.log.Logger;
import com.facebook.buck.slb.ThriftException;
import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransportException;

public class ThriftCoordinatorClient implements Closeable {
  private static final Logger LOG = Logger.get(ThriftCoordinatorClient.class);

  private final String remoteHost;
  private final StampedeId stampedeId;

  @Nullable private TFramedTransport transport;
  @Nullable private CoordinatorService.Client client;

  public ThriftCoordinatorClient(String remoteHost, StampedeId stampedeId) {
    this.remoteHost = Preconditions.checkNotNull(remoteHost);
    this.stampedeId = stampedeId;
  }

  /** Starts the thrift client. */
  public ThriftCoordinatorClient start(int remotePort) throws ThriftException {
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

  public ThriftCoordinatorClient stop() {
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
            "Minion [%s] is reporting that it finished building [%s] items. Requesting [%s] items.",
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
      return client.getWork(request);
    } catch (TException e) {
      throw new ThriftException(e);
    }
  }

  @Override
  public void close() throws ThriftException {
    if (client != null) {
      stop();
    }
  }
}
