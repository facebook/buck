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

import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.grpc.GrpcRemoteExecutionClients;
import com.facebook.buck.remoteexecution.util.OutOfProcessIsolatedExecutionClients;
import com.facebook.buck.util.timing.FakeClock;
import java.io.IOException;
import java.util.Optional;
import org.junit.Test;

public class RemoteExecutionClientsFactoryTest {

  @Test
  public void grpcConfiguration() throws IOException {
    BuckConfig config =
        FakeBuckConfig.builder().setSections("[remoteexecution]", "type=grpc").build();

    try (RemoteExecutionClients remoteExecutionClients = createClients(config)) {
      assertTrue(remoteExecutionClients instanceof GrpcRemoteExecutionClients);
    }
  }

  @Test
  public void grpcLocalConfiguration() throws IOException {
    BuckConfig config =
        FakeBuckConfig.builder().setSections("[remoteexecution]", "type=debug_grpc_local").build();

    try (RemoteExecutionClients remoteExecutionClients = createClients(config)) {
      assertTrue(remoteExecutionClients instanceof GrpcRemoteExecutionClients);
    }
  }

  @Test
  public void grpcInProcessConfiguration() throws IOException {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections("[remoteexecution]", "type=debug_grpc_in_process")
            .build();

    try (RemoteExecutionClients remoteExecutionClients = createClients(config)) {
      assertTrue(remoteExecutionClients instanceof OutOfProcessIsolatedExecutionClients);
    }
  }

  @Test
  public void deprecatedGrpcConfiguration() throws IOException {
    BuckConfig config =
        FakeBuckConfig.builder().setSections("[modern_build_rule]", "strategy=grpc_remote").build();

    try (RemoteExecutionClients remoteExecutionClients = createClients(config)) {
      assertTrue(remoteExecutionClients instanceof GrpcRemoteExecutionClients);
    }
  }

  @Test
  public void deprecatedGrpcrpcLocalConfiguration() throws IOException {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections("[modern_build_rule]", "strategy=debug_grpc_service_in_process")
            .build();

    try (RemoteExecutionClients remoteExecutionClients = createClients(config)) {
      assertTrue(remoteExecutionClients instanceof GrpcRemoteExecutionClients);
    }
  }

  @Test
  public void deprecatedGrpcrpcInProcessConfiguration() throws IOException {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections("[modern_build_rule]", "strategy=debug_isolated_out_of_process_grpc")
            .build();

    try (RemoteExecutionClients remoteExecutionClients = createClients(config)) {
      assertTrue(remoteExecutionClients instanceof OutOfProcessIsolatedExecutionClients);
    }
  }

  private RemoteExecutionClients createClients(BuckConfig config) throws IOException {
    return new RemoteExecutionClientsFactory(config.getView(RemoteExecutionConfig.class))
        .create(new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId("")), Optional.empty());
  }
}
