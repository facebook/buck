/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.versioncontrol;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.Logger;

import java.util.concurrent.ExecutorService;

public class VersionControlStatsGenerator {
  private static final Logger LOG = Logger.get(VersionControlStatsGenerator.class);

  private final ExecutorService executorService;
  private final VersionControlCmdLineInterfaceFactory versionControlCmdLineInterfaceFactory;
  private final BuckEventBus buckEventBus;

  public VersionControlStatsGenerator(
      ExecutorService executorService,
      VersionControlCmdLineInterfaceFactory versionControlCmdLineInterfaceFactory,
      BuckEventBus buckEventBus) {
    this.executorService = executorService;
    this.versionControlCmdLineInterfaceFactory = versionControlCmdLineInterfaceFactory;
    this.buckEventBus = buckEventBus;
  }

  public void generateStatsAsync() {
    executorService.submit(
        new Runnable() {
          @Override
          public void run() {
            try {
              generateStats();
            } catch (InterruptedException e) {
              LOG.warn(e, "Failed to generate VC stats due to being interrupted. Skipping..");
              Thread.currentThread().interrupt(); // Re-set interrupt flag
            } catch (VersionControlCommandFailedException e) {
              LOG.warn(e, "Failed to generate VC stats due to exception. Skipping..");
            }
          }
        });
  }

  private void generateStats() throws InterruptedException, VersionControlCommandFailedException {
    LOG.info("Starting generation of version control stats.");

    VersionControlCmdLineInterface vcCmdLineInterface =
        versionControlCmdLineInterfaceFactory.createCmdLineInterface();

    boolean workingDirectoryChanges = vcCmdLineInterface.hasWorkingDirectoryChanges();

    String currentRevisionId = vcCmdLineInterface.currentRevisionId();
    String latestMasterRevisionId = vcCmdLineInterface.revisionId("master");

    // Find the master revision which the current revision was branched from.
    // (Not necessarily the same as the latest master above)
    String branchedFromMasterRevisionId =
        vcCmdLineInterface.commonAncestor(currentRevisionId, latestMasterRevisionId);

    long branchedFromMasterTsMillis = vcCmdLineInterface.timestampSeconds(
        branchedFromMasterRevisionId) * 1000;

    VersionControlStats versionControlStats = ImmutableVersionControlStats.builder()
        .workingDirectoryChanges(workingDirectoryChanges)
        .currentRevisionId(currentRevisionId)
        .branchedFromMasterRevisionId(branchedFromMasterRevisionId)
        .branchedFromMasterTsMillis(branchedFromMasterTsMillis)
        .build();

    LOG.info("Version control stats generated successfully. \n%s", versionControlStats);

    buckEventBus.post(new VersionControlStatsEvent(versionControlStats));
  }
}
