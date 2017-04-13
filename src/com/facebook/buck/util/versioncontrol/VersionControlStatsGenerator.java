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
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Map;
import java.util.concurrent.ExecutorService;

public class VersionControlStatsGenerator {

  private static final Logger LOG = Logger.get(VersionControlStatsGenerator.class);

  public static final String REMOTE_MASTER = "remote/master";
  public static final ImmutableSet<String> TRACKED_BOOKMARKS = ImmutableSet.of(
      REMOTE_MASTER);

  private final ExecutorService executorService;
  private final VersionControlCmdLineInterface versionControlCmdLineInterface;
  private final BuckEventBus buckEventBus;

  public VersionControlStatsGenerator(
      ExecutorService executorService,
      VersionControlCmdLineInterface versionControlCmdLineInterface,
      BuckEventBus buckEventBus) {
    this.executorService = executorService;
    this.versionControlCmdLineInterface = versionControlCmdLineInterface;
    this.buckEventBus = buckEventBus;
  }

  public void generateStatsAsync() {
    executorService.submit(
        () -> {
          try {
            generateStats();
          } catch (InterruptedException e) {
            LOG.warn(e, "Failed to generate VC stats due to being interrupted. Skipping..");
            Thread.currentThread().interrupt(); // Re-set interrupt flag
          }
        });
  }

  private void generateStats() throws InterruptedException {
    LOG.info("Starting generation of version control stats.");

    if (!versionControlCmdLineInterface.isSupportedVersionControlSystem()) {
      LOG.warn("Skipping generation of version control stats as unsupported repository type.");
      return;
    }

    VersionControlStats.Builder versionControlStats = VersionControlStats.builder();
    try (SimplePerfEvent.Scope ignored = SimplePerfEvent.scope(
        buckEventBus,
        "gen_source_control_info")) {
      try {
        // Get a list of the revision ids of all the tracked bookmarks.
        ImmutableMap<String, String> bookmarksRevisionIds =
            versionControlCmdLineInterface.bookmarksRevisionsId(TRACKED_BOOKMARKS);
        // Get the current revision id.
        String currentRevisionId = versionControlCmdLineInterface.currentRevisionId();
        String masterRevisionId = bookmarksRevisionIds.get(REMOTE_MASTER);
        if (masterRevisionId != null) {
          // Get the common ancestor of current and master revision
          Pair<String, Long> baseRevisionInfo =
              versionControlCmdLineInterface.commonAncestorAndTS(
                  currentRevisionId,
                  masterRevisionId);
          versionControlStats
              .setBranchedFromMasterRevisionId(baseRevisionInfo.getFirst())
              .setBranchedFromMasterTS(baseRevisionInfo.getSecond())
              .setBaseBookmarks(
                  bookmarksRevisionIds.entrySet().stream()
                      .filter(e -> e.getValue().startsWith(baseRevisionInfo.getFirst()))
                      .map(Map.Entry::getKey)
                      .collect(MoreCollectors.toImmutableSet()))
              .setPathsChangedInWorkingDirectory(
                  versionControlCmdLineInterface.changedFiles(baseRevisionInfo.getFirst()));
        }

        versionControlStats.setCurrentRevisionId(currentRevisionId);
      } catch (VersionControlCommandFailedException e) {
        LOG.warn("Failed to gather source control stats.");
      }
    }

    LOG.info("Stats generated successfully. \n%s", versionControlStats);
    buckEventBus.post(new VersionControlStatsEvent(versionControlStats.build()));
  }
}
