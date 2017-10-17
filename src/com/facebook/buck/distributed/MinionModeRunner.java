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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.GetWorkResponse;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.log.Logger;
import com.facebook.buck.slb.ThriftException;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Random;

public class MinionModeRunner implements DistBuildModeRunner {

  private static final Logger LOG = Logger.get(MinionModeRunner.class);

  // TODO(alisdair): make this a .buckconfig setting
  private static final int IDLE_SLEEP_INTERVAL_MS = 10;

  private final String coordinatorAddress;
  private final int coordinatorPort;
  private final LocalBuilder builder;
  private final StampedeId stampedeId;
  private final BuildCompletionChecker buildCompletionChecker;
  private final int maxParallelWorkUnits;

  /** Callback when the build has completed. */
  public interface BuildCompletionChecker {

    boolean hasBuildFinished() throws IOException;
  }

  public MinionModeRunner(
      String coordinatorAddress,
      int coordinatorPort,
      LocalBuilder builder,
      StampedeId stampedeId,
      int maxParallelWorkUnits,
      BuildCompletionChecker buildCompletionChecker) {
    this.buildCompletionChecker = buildCompletionChecker;
    this.builder = builder;
    this.stampedeId = stampedeId;
    Preconditions.checkArgument(
        coordinatorPort > 0, "The coordinator's port needs to be a positive integer.");
    this.coordinatorAddress = coordinatorAddress;
    this.coordinatorPort = coordinatorPort;
    this.maxParallelWorkUnits = maxParallelWorkUnits;
  }

  @Override
  public int runAndReturnExitCode() throws IOException, InterruptedException {
    int lastExitCode = 0;
    try (ThriftCoordinatorClient client =
        new ThriftCoordinatorClient(coordinatorAddress, coordinatorPort, stampedeId)) {
      client.start();
      final String minionId = generateNewMinionId();
      List<String> targetsToBuild = Lists.newArrayList();
      while (true) {
        GetWorkResponse response =
            client.getWork(minionId, lastExitCode, targetsToBuild, maxParallelWorkUnits);

        if (!response.isContinueBuilding()) {
          LOG.info(String.format("Minion [%s] told to stop building.", minionId));
          builder.shutdown();
          return 0;
        }

        targetsToBuild = Lists.newArrayList();

        // If there was nothing to build, then go to sleep for a few milliseconds and try again.
        if (response.getWorkUnits().size() == 0) {
          Thread.sleep(IDLE_SLEEP_INTERVAL_MS);
          continue;
        }

        // Each work unit consists of one of more build targets. Aggregate them all together
        // and feed them to the build engine as a batch
        // TODO(alisdair): get rid of existing batch based approach and fetch/signal in real-time.
        for (WorkUnit workUnit : response.getWorkUnits()) {
          targetsToBuild.addAll(workUnit.getBuildTargets());
        }

        LOG.info(
            String.format(
                "Minion [%s] is about to build [%d] targets as part of [%d] work units",
                minionId, targetsToBuild.size(), response.getWorkUnits().size()));

        LOG.debug(String.format("Targets: [%s]", Joiner.on(", ").join(targetsToBuild)));

        lastExitCode = builder.buildLocallyAndReturnExitCode(targetsToBuild);

        LOG.info(
            String.format("Minion [%s] finished with exit code [%d].", minionId, lastExitCode));
      }
    } catch (ThriftException e) {
      if (buildCompletionChecker.hasBuildFinished()) {
        // If the build has finished and this minion was not doing anything and was just
        // waiting for work, just exit gracefully with return code 0.
        return lastExitCode;
      }

      throw e;
    }
  }

  public static String generateNewMinionId() {
    String hostname = "Unknown";
    try {
      InetAddress addr;
      addr = InetAddress.getLocalHost();
      hostname = addr.getHostName();
    } catch (UnknownHostException ex) {
      System.out.println("Hostname can not be resolved");
    }

    return String.format("minion:%s:%d", hostname, new Random().nextInt(Integer.MAX_VALUE));
  }
}
