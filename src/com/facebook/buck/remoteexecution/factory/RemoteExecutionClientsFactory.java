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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.grpc.GrpcExecutionFactory;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol;
import com.facebook.buck.remoteexecution.thrift.ThriftProtocol;
import com.facebook.buck.remoteexecution.thrift.ThriftRemoteExecutionFactory;
import com.facebook.buck.remoteexecution.util.OutOfProcessIsolatedExecutionClients;
import com.facebook.buck.rules.modern.config.ModernBuildRuleBuildStrategy;
import java.io.IOException;

/**
 * Factory for creating all manner of different remote execution clients (grpc, thrift, in-process,
 * etc).
 */
public class RemoteExecutionClientsFactory {

  private final ModernBuildRuleBuildStrategy fallbackStrategy;
  private final RemoteExecutionConfig remoteExecutionConfig;

  public RemoteExecutionClientsFactory(
      RemoteExecutionConfig remoteExecutionConfig, ModernBuildRuleBuildStrategy buildStrategy) {
    this.remoteExecutionConfig = remoteExecutionConfig;
    this.fallbackStrategy = buildStrategy;
  }

  /** Creates the RemoteExecutionClients based on the held configs. */
  public RemoteExecutionClients create(BuckEventBus eventBus) throws IOException {
    switch (fallbackStrategy) {
      case DEBUG_RECONSTRUCT:
      case DEBUG_PASSTHROUGH:
      case DEBUG_ISOLATED_IN_PROCESS:
      case NONE:
        break;

      case GRPC_REMOTE:
        return GrpcExecutionFactory.createRemote(
            remoteExecutionConfig.getRemoteHost(),
            remoteExecutionConfig.getRemotePort(),
            remoteExecutionConfig.getCasHost(),
            remoteExecutionConfig.getCasPort());
      case THRIFT_REMOTE:
        return ThriftRemoteExecutionFactory.createRemote(remoteExecutionConfig, eventBus);
      case DEBUG_ISOLATED_OUT_OF_PROCESS:
        return OutOfProcessIsolatedExecutionClients.create(new ThriftProtocol(), eventBus);
      case DEBUG_ISOLATED_OUT_OF_PROCESS_GRPC:
        return OutOfProcessIsolatedExecutionClients.create(new GrpcProtocol(), eventBus);
      case DEBUG_GRPC_SERVICE_IN_PROCESS:
        return GrpcExecutionFactory.createInProcess();
    }
    throw new UnsupportedOperationException(
        String.format("Can't create remote execution clients for %s.", fallbackStrategy));
  }
}
