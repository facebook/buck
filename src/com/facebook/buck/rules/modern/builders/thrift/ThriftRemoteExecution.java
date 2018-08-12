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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.modern.builders.ContentAddressedStorage;
import com.facebook.buck.rules.modern.builders.Protocol;
import com.facebook.buck.rules.modern.builders.RemoteExecution;
import com.facebook.buck.rules.modern.builders.RemoteExecutionService;
import com.facebook.buck.rules.modern.builders.thrift.cas.ThriftContentAddressedStorage;
import com.facebook.buck.rules.modern.builders.thrift.executionengine.ThriftExecutionEngine;
import java.io.IOException;

/** A RemoteExecution that sends jobs to a thrift-based remote execution service. */
public class ThriftRemoteExecution extends RemoteExecution {

  private static final Protocol PROTOCOL = new ThriftProtocol();
  private final ThriftRemoteExecutionClients clients;

  ThriftRemoteExecution(BuckEventBus eventBus, ThriftRemoteExecutionClients clients)
      throws IOException {
    super(eventBus);
    this.clients = clients;
  }

  @Override
  protected Protocol getProtocol() {
    return PROTOCOL;
  }

  @Override
  protected ContentAddressedStorage getStorage() {
    return new ThriftContentAddressedStorage(clients.getCasClient(), clients.getAsyncCasClient());
  }

  @Override
  protected RemoteExecutionService getExecutionService() {
    return new ThriftExecutionEngine(clients.getExecutionEngineClient(), clients.getCasClient());
  }
}
