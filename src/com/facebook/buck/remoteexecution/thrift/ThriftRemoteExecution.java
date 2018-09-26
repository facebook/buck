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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.ContentAddressedStorage;
import com.facebook.buck.remoteexecution.Protocol;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.RemoteExecutionService;
import com.facebook.buck.remoteexecution.thrift.cas.ThriftContentAddressedStorage;
import com.facebook.buck.remoteexecution.thrift.executionengine.ThriftExecutionEngine;
import java.io.IOException;
import java.util.Optional;

/** A RemoteExecution that sends jobs to a thrift-based remote execution service. */
public class ThriftRemoteExecution implements RemoteExecutionClients {
  private static final Protocol PROTOCOL = new ThriftProtocol();
  private final ThriftContentAddressedStorage storage;
  private final ThriftExecutionEngine remoteExecutionService;
  private final ThriftRemoteExecutionClientsFactory clients;

  public ThriftRemoteExecution(
      BuckEventBus eventBus,
      ThriftRemoteExecutionClientsFactory clients,
      Optional<String> traceId) {
    this.clients = clients;
    this.storage =
        new ThriftContentAddressedStorage(
            clients.createCasClient(), clients.createCasClient(), eventBus);
    this.remoteExecutionService =
        new ThriftExecutionEngine(
            clients.createExecutionEngineClient(), clients.createCasClient(), traceId);
  }

  @Override
  public RemoteExecutionService getRemoteExecutionService() {
    return remoteExecutionService;
  }

  @Override
  public ContentAddressedStorage getContentAddressedStorage() {
    return storage;
  }

  @Override
  public Protocol getProtocol() {
    return PROTOCOL;
  }

  @Override
  public void close() throws IOException {
    this.clients.close();
  }
}
