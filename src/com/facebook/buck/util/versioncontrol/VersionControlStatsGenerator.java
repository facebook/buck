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
import java.util.Optional;
import java.util.concurrent.ExecutorService;

public class VersionControlStatsGenerator {

  private static final Logger LOG = Logger.get(VersionControlStatsGenerator.class);

  /** Modes the generator can get stats in, in order from least comprehensive to most
   *  comprehensive. Each mode should include all the information present in the previous one.
   */
  public enum Mode {
    /** Generate a set of stats that is fast to generate but incomplete */
    FAST,
    /** Generate the full set of stats */
    FULL,
    ;
  }

  private static final String REMOTE_MASTER = "remote/master";
  private static final ImmutableSet<String> TRACKED_BOOKMARKS = ImmutableSet.of(
      REMOTE_MASTER);

  private final VersionControlCmdLineInterface versionControlCmdLineInterface;

  public VersionControlStatsGenerator(
      VersionControlCmdLineInterface versionControlCmdLineInterface) {
    this.versionControlCmdLineInterface = versionControlCmdLineInterface;
  }

  public void generateStatsAsync(
      Mode mode,
      ExecutorService executorService,
      BuckEventBus buckEventBus) {
    executorService.submit(
        () -> {
          try {
            Optional<VersionControlStats>  versionControlStats;
            try (SimplePerfEvent.Scope ignored = SimplePerfEvent.scope(
                buckEventBus,
                "gen_source_control_info")) {
              versionControlStats = generateStats(mode);
            }
            versionControlStats.ifPresent(x -> buckEventBus.post(new VersionControlStatsEvent(x)));
          } catch (InterruptedException e) {
            LOG.warn(e, "Failed to generate VC stats due to being interrupted. Skipping..");
            Thread.currentThread().interrupt(); // Re-set interrupt flag
          }
        });
  }

  public Optional<VersionControlStats> generateStats(Mode mode) throws InterruptedException {
    LOG.info("Starting generation of version control stats.");

    if (!versionControlCmdLineInterface.isSupportedVersionControlSystem()) {
      LOG.warn("Skipping generation of version control stats as unsupported repository type.");
      return Optional.empty();
    }

    VersionControlStats.Builder versionControlStats = VersionControlStats.builder();
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
                mode == Mode.FULL ?
                    versionControlCmdLineInterface.changedFiles(baseRevisionInfo.getFirst()) :
                    ImmutableSet.of())
            .setDiff(
                mode == Mode.FULL ?
                    versionControlCmdLineInterface.diffBetweenRevisionsOrAbsent(
                        baseRevisionInfo.getFirst(),
                        currentRevisionId) :
                    Optional.empty());
      }

      versionControlStats.setCurrentRevisionId(currentRevisionId);
    } catch (VersionControlCommandFailedException e) {
      LOG.warn("Failed to gather source control stats.");
    }

    LOG.info("Stats generated successfully. \n%s", versionControlStats);
    return Optional.of(versionControlStats.build());
  }
}
