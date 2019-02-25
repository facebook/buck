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

package com.facebook.buck.remoteexecution.factory;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.config.RemoteExecutionType;
import com.facebook.buck.remoteexecution.grpc.GrpcExecutionFactory;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.remoteexecution.util.OutOfProcessIsolatedExecutionClients;
import java.io.IOException;

/**
 * Factory for creating all manner of different remote execution clients (grpc, in-process, etc).
 */
public class RemoteExecutionClientsFactory {
  private final RemoteExecutionConfig remoteExecutionConfig;

  public RemoteExecutionClientsFactory(RemoteExecutionConfig remoteExecutionConfig) {
    this.remoteExecutionConfig = remoteExecutionConfig;
  }

  /** Creates the RemoteExecutionClients based on the held configs. */
  public RemoteExecutionClients create(BuckEventBus eventBus, MetadataProvider metadataProvider)
      throws IOException {
    RemoteExecutionType type = remoteExecutionConfig.getType();

    switch (type) {
      case NONE:
        throw new HumanReadableException(
            "Remote execution implementation required but not configured. Please set an appropriate %s.type.",
            RemoteExecutionConfig.SECTION);
      case GRPC:
        return GrpcExecutionFactory.createRemote(
            remoteExecutionConfig.getRemoteHost(),
            remoteExecutionConfig.getRemotePort(),
            remoteExecutionConfig.getCasHost(),
            remoteExecutionConfig.getCasPort(),
            remoteExecutionConfig.getInsecure(),
            remoteExecutionConfig.getCasInsecure(),
            remoteExecutionConfig.getCertFile(),
            remoteExecutionConfig.getKeyFile(),
            remoteExecutionConfig.getCertificateAuthoritiesFile(),
            metadataProvider,
            eventBus);
      case DEBUG_GRPC_IN_PROCESS:
        return OutOfProcessIsolatedExecutionClients.create(new GrpcProtocol(), eventBus);
      case DEBUG_GRPC_LOCAL:
        return GrpcExecutionFactory.createInProcess(eventBus);
    }
    throw new IllegalStateException(String.format("Something went wrong (%s).", type));
  }
}
