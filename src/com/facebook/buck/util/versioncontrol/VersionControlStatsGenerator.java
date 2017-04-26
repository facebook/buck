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

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

public class VersionControlStatsGenerator {

  private static final Logger LOG = Logger.get(VersionControlStatsGenerator.class);

  /** Modes the generator can get stats in, in order from least comprehensive to most
   *  comprehensive. Each mode should include all the information present in the previous one.
   */
  public enum Mode {
    /** Do not generate new information, but return whatever is already generated */
    PREGENERATED,
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

  private final Optional<PregeneratedVersionControlStats> pregeneratedVersionControlStats;
  @GuardedBy("this")
  @Nullable
  private String currentRevisionId;
  @GuardedBy("this")
  @Nullable
  private ImmutableSet<String> baseBookmarks;
  @GuardedBy("this")
  @Nullable
  private String baseRevisionId;
  @GuardedBy("this")
  @Nullable
  private Long baseRevisionTimestamp;
  @GuardedBy("this")
  @Nullable
  private ImmutableSet<String> changedFiles;
  @GuardedBy("this")
  @Nullable
  private Optional<String> diff;

  public VersionControlStatsGenerator(
      VersionControlCmdLineInterface versionControlCmdLineInterface,
      Optional<PregeneratedVersionControlStats> pregeneratedVersionControlStats) {
    this.versionControlCmdLineInterface = versionControlCmdLineInterface;
    this.pregeneratedVersionControlStats = pregeneratedVersionControlStats;
    if (pregeneratedVersionControlStats.isPresent()) {
      this.currentRevisionId = pregeneratedVersionControlStats.get().getCurrentRevisionId();
      this.baseBookmarks = pregeneratedVersionControlStats.get().getBaseBookmarks();
      this.baseRevisionId =
          pregeneratedVersionControlStats.get().getBranchedFromMasterRevisionId();
      this.baseRevisionTimestamp = pregeneratedVersionControlStats.get().getBranchedFromMasterTS();
    }
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

  public synchronized Optional<VersionControlStats> generateStats(Mode mode)
      throws InterruptedException {
    if (mode == Mode.PREGENERATED) {
      return pregeneratedVersionControlStats.map(
          x -> VersionControlStats.builder().from(x).build());
    }

    VersionControlStats versionControlStats = null;
    LOG.info("Starting generation of version control stats.");
    if (!versionControlCmdLineInterface.isSupportedVersionControlSystem()) {
      LOG.warn("Skipping generation of version control stats as unsupported repository type.");
    } else {
      VersionControlStats.Builder versionControlStatsBuilder = VersionControlStats.builder();
      // Prepopulate as much as possible before trying to query the VCS: this way if it fails we
      // still have this information.
      if (currentRevisionId != null) {
        versionControlStatsBuilder.setCurrentRevisionId(currentRevisionId);
      }
      if (baseBookmarks != null) {
        versionControlStatsBuilder.setBaseBookmarks(baseBookmarks);
      }
      if (baseRevisionId != null) {
        versionControlStatsBuilder.setBranchedFromMasterRevisionId(baseRevisionId);
      }
      if (baseRevisionTimestamp != null) {
        versionControlStatsBuilder.setBranchedFromMasterTS(baseRevisionTimestamp);
      }

      try {
        // Get the current revision id.
        if (currentRevisionId == null) {
          currentRevisionId = versionControlCmdLineInterface.currentRevisionId();
          versionControlStatsBuilder.setCurrentRevisionId(currentRevisionId);
        }
        versionControlStatsBuilder.setCurrentRevisionId(currentRevisionId);
        if (baseBookmarks == null || baseRevisionId == null || baseRevisionTimestamp == null) {
          // Get a list of the revision ids of all the tracked bookmarks.
          ImmutableMap<String, String> bookmarksRevisionIds =
              versionControlCmdLineInterface.bookmarksRevisionsId(TRACKED_BOOKMARKS);
          String masterRevisionId = bookmarksRevisionIds.get(REMOTE_MASTER);
          if (masterRevisionId != null) {
            // Get the common ancestor of current and master revision
            Pair<String, Long> baseRevisionInfo =
                versionControlCmdLineInterface.commonAncestorAndTS(
                    currentRevisionId,
                    masterRevisionId);
            if (baseBookmarks == null) {
              baseBookmarks = bookmarksRevisionIds.entrySet().stream()
                  .filter(e -> e.getValue().startsWith(baseRevisionInfo.getFirst()))
                  .map(Map.Entry::getKey)
                  .collect(MoreCollectors.toImmutableSet());
              versionControlStatsBuilder.setBaseBookmarks(baseBookmarks);
            }
            if (baseRevisionId == null) {
              baseRevisionId = baseRevisionInfo.getFirst();
              versionControlStatsBuilder
                  .setBranchedFromMasterRevisionId(baseRevisionInfo.getFirst());
            }
            if (baseRevisionTimestamp == null) {
              baseRevisionTimestamp = baseRevisionInfo.getSecond();
              versionControlStatsBuilder
                  .setBranchedFromMasterTS(baseRevisionInfo.getSecond());
            }
          }
        }
        if (mode == Mode.FULL) {
          // Same as above: prepopulate as much as possible.
          if (changedFiles != null) {
            versionControlStatsBuilder.setPathsChangedInWorkingDirectory(changedFiles);
          }
          if (diff != null) {
            versionControlStatsBuilder.setDiff(diff);
          }
          // We can only query these if we have a base revision id.
          if (baseRevisionId != null) {
            if (changedFiles == null) {
              changedFiles =
                  versionControlCmdLineInterface.changedFiles(baseRevisionId);
              versionControlStatsBuilder.setPathsChangedInWorkingDirectory(changedFiles);
            }
            if (diff == null) {
              diff = versionControlCmdLineInterface.diffBetweenRevisionsOrAbsent(
                  baseRevisionId,
                  currentRevisionId);
              versionControlStatsBuilder.setDiff(diff);
            }
          }
        }
      } catch (VersionControlCommandFailedException e) {
        LOG.warn("Failed to gather some source control stats.");
      }
      versionControlStats = versionControlStatsBuilder.build();
      LOG.info("Stats generated successfully. \n%s", versionControlStats);
    }
    return Optional.ofNullable(versionControlStats);
  }
}
