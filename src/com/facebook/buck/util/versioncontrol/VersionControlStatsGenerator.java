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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Map;
import java.util.concurrent.ExecutorService;

public class VersionControlStatsGenerator {

  private static final Logger LOG = Logger.get(VersionControlStatsGenerator.class);

  public static final ImmutableSet<String> TRACKED_BOOKMARKS = ImmutableSet.of(
      "remote/master");

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
        () -> {
          try {
            generateStats();
          } catch (InterruptedException e) {
            LOG.warn(e, "Failed to generate VC stats due to being interrupted. Skipping..");
            Thread.currentThread().interrupt(); // Re-set interrupt flag
          } catch (VersionControlCommandFailedException e) {
            LOG.warn(e, "Failed to generate VC stats due to exception. Skipping..");
          }
        });
  }

  private void generateStats() throws InterruptedException, VersionControlCommandFailedException {
    LOG.info("Starting generation of version control stats.");

    VersionControlCmdLineInterface vcCmdLineInterface =
        versionControlCmdLineInterfaceFactory.createCmdLineInterface();

    if (!vcCmdLineInterface.isSupportedVersionControlSystem()) {
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
            vcCmdLineInterface.bookmarksRevisionsId(TRACKED_BOOKMARKS);
        // Get the current revision id.
        String currentRevisionId = vcCmdLineInterface.currentRevisionId();
        // Get the common ancestor of master and current revision
        String branchedFromMasterRevisionId = vcCmdLineInterface.commonAncestor(
            currentRevisionId,
            bookmarksRevisionIds.get("remote/master"));
        // Get the list of tracked changes files.
        ImmutableSet<String> changedFiles = vcCmdLineInterface.changedFiles(".");

        ImmutableSet.Builder<String> baseBookmarks = ImmutableSet.builder();
        for (Map.Entry<String, String> bookmark : bookmarksRevisionIds.entrySet()) {
          if (bookmark.getValue().startsWith(currentRevisionId)) {
            baseBookmarks.add(bookmark.getKey());
          }
        }

        versionControlStats
            .setPathsChangedInWorkingDirectory(changedFiles)
            .setCurrentRevisionId(currentRevisionId)
            .setBranchedFromMasterRevisionId(branchedFromMasterRevisionId)
            .setBaseBookmarks(baseBookmarks.build())
            .build();
      } catch (VersionControlCommandFailedException e) {
        LOG.warn("Failed to gather source control stats.");
      }
    }

    LOG.info("Stats generated successfully. \n%s", versionControlStats);
    buckEventBus.post(new VersionControlStatsEvent(versionControlStats.build()));
  }
}
