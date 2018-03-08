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
package com.facebook.buck.distributed.build_client;

import com.facebook.buck.distributed.StampedeLocalBuildStatusEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.RemoteBuildRuleSynchronizer;

/** Util class that contains orchestration code for synchronized build phase. */
public class SynchronizedBuildPhase {
  private static final Logger LOG = Logger.get(SynchronizedBuildPhase.class);

  /**
   * Performs a local build that waits for build nodes to finish remotely before proceeding locally.
   * Deals with cleanup if local or distributed build fails.
   *
   * @throws InterruptedException
   */
  public static void run(
      DistBuildRunner distBuildRunner,
      LocalBuildRunner synchronizedBuildRunner,
      RemoteBuildRuleSynchronizer remoteBuildRuleSynchronizer,
      boolean localBuildFallbackEnabled,
      BuckEventBus eventBus)
      throws InterruptedException {
    LOG.info("Starting synchronized build phase.");
    eventBus.post(new StampedeLocalBuildStatusEvent("waiting"));

    // Ensure build engine blocks when it hits a build rule that hasn't finished remotely.
    remoteBuildRuleSynchronizer.switchToAlwaysWaitingMode();

    synchronizedBuildRunner.runLocalBuildAsync();

    LOG.info("Waiting for local synchronized build or distributed build to finish.");
    synchronizedBuildRunner.getBuildPhaseLatch().await();

    if (distBuildRunner.finishedSuccessfully()) {
      LOG.info("Distributed build finished before local build. Waiting for local build..");
      synchronizedBuildRunner.waitUntilFinished();
    } else if (synchronizedBuildRunner.isFinished()) {
      LOG.info("Local synchronized build finished before distributed build.");
      distBuildRunner.cancelAsLocalBuildFinished(
          synchronizedBuildRunner.finishedSuccessfully(), synchronizedBuildRunner.getExitCode());
    } else {
      LOG.info("Distributed build failed before local build.");
      if (localBuildFallbackEnabled) {
        LOG.info("Falling back to local synchronized build. Waiting for it to finish..");
        eventBus.post(new StampedeLocalBuildStatusEvent("fallback", "Local Build"));
        synchronizedBuildRunner.waitUntilFinished();
      } else {
        LOG.info("Killing local synchronized build as local fallback not enabled.");
        synchronizedBuildRunner.cancelAndWait(
            "Distributed build failed, and fallback not enabled.");
      }
    }

    LOG.info("Local synchronized build phase has finished.");
  }
}
