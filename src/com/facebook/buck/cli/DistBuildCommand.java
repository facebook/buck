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

package com.facebook.buck.cli;

import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.slb.ClientSideSlb;
import com.facebook.buck.slb.LoadBalancedService;
import com.facebook.buck.slb.ThriftOverHttpService;
import com.facebook.buck.slb.ThriftOverHttpServiceConfig;
import com.facebook.buck.slb.ThriftService;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.SubCommand;
import org.kohsuke.args4j.spi.SubCommands;

import java.io.IOException;

import okhttp3.OkHttpClient;

public class DistBuildCommand extends AbstractContainerCommand {

  @Argument(handler = AdditionalOptionsSubCommandHandler.class)
  @SubCommands({
      @SubCommand(name = "status", impl = DistBuildStatusCommand.class),
  })
  @SuppressFieldNotInitialized
  private Command subcommand;

  @Option(
      name = "--help",
      usage = "Shows this screen and exits.")
  @SuppressWarnings("PMD.UnusedPrivateField")
  private boolean helpScreen;

  @Override
  public int run(CommandRunnerParams params) throws IOException, InterruptedException {
    if (subcommand == null) {
      printUsage(params.getConsole().getStdErr());
      return 1;
    }
    return subcommand.run(params);
  }

  @Override
  protected String getContainerCommandPrefix() {
    return "buck distbuild";
  }

  @Override
  public Optional<Command> getSubcommand() {
    return Optional.fromNullable(subcommand);
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  public static ThriftService<FrontendRequest, FrontendResponse> newFrontendService(
      CommandRunnerParams params) {
    DistBuildConfig config = new DistBuildConfig(params.getBuckConfig());
    ClientSideSlb slb = config.getFrontendConfig().createHttpClientSideSlb(
        params.getClock(),
        params.getBuckEventBus(),
        new CommandThreadFactory("BuildCommand.HttpLoadBalancer"));
    OkHttpClient client = config.createOkHttpClient();

    return new ThriftOverHttpService<>(
        ThriftOverHttpServiceConfig.of(new LoadBalancedService(
            slb,
            client,
            params.getBuckEventBus())));
  }

  @Override
  public String getShortDescription() {
    return "attaches to a distributed build (experimental)";
  }
}
