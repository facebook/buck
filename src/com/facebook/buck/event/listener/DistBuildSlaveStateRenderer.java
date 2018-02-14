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

package com.facebook.buck.event.listener;

import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.util.Ansi;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.stream.LongStream;

public class DistBuildSlaveStateRenderer implements MultiStateRenderer {
  private final Ansi ansi;
  private final long currentTimeMs;
  private final ImmutableList<BuildSlaveStatus> slaveStatuses;

  public DistBuildSlaveStateRenderer(
      Ansi ansi, long currentTimeMs, ImmutableList<BuildSlaveStatus> slaveStatuses) {
    this.ansi = ansi;
    this.currentTimeMs = currentTimeMs;
    this.slaveStatuses = slaveStatuses;
  }

  @Override
  public String getExecutorCollectionLabel() {
    return "Servers";
  }

  @Override
  public int getExecutorCount() {
    return slaveStatuses.size();
  }

  @Override
  public ImmutableList<Long> getSortedExecutorIds(boolean sortByTime) {
    // TODO(shivanker): Implement 'sort by busyness' for Stampede BuildSlaves.
    return LongStream.range(0, slaveStatuses.size())
        .boxed()
        .collect(ImmutableList.toImmutableList());
  }

  @Override
  public String renderStatusLine(long slaveID, StringBuilder lineBuilder) {
    Preconditions.checkArgument(slaveID >= 0 && slaveID < slaveStatuses.size());
    BuildSlaveStatus status = slaveStatuses.get((int) slaveID);
    lineBuilder.append(String.format(" Server %d: ", slaveID));

    if (status.getTotalRulesCount() == 0) {
      ImmutableList.Builder<String> columns = new ImmutableList.Builder<>();
      columns.add("creating action graph");

      if (status.getFilesMaterializedCount() > 0) {
        columns.add(
            String.format("materializing source files [%d]", status.getFilesMaterializedCount()));
      }

      lineBuilder.append(String.format("Preparing: %s ...", Joiner.on(", ").join(columns.build())));
    } else {
      String prefix = "Idle";
      if (status.getRulesBuildingCount() != 0) {
        prefix = String.format("Working on %d jobs", status.getRulesBuildingCount());
      }

      ImmutableList.Builder<String> columns = new ImmutableList.Builder<>();
      columns.add(
          String.format(
              "built %d/%d jobs", status.getRulesFinishedCount(), status.getTotalRulesCount()));

      if (status.getRulesFailureCount() != 0) {
        columns.add(String.format("%d jobs failed", status.getRulesFailureCount()));
      }

      if (status.isSetCacheRateStats()) {
        CacheRateStatsKeeper.CacheRateStatsUpdateEvent cacheStats =
            CacheRateStatsKeeper.getCacheRateStatsUpdateEventFromSerializedStats(
                status.getCacheRateStats());
        columns.add(String.format("%.1f%% cache miss", cacheStats.getCacheMissRate()));

        if (cacheStats.getCacheErrorCount() != 0) {
          columns.add(
              String.format(
                  "%d [%.1f%%] cache errors",
                  cacheStats.getCacheErrorCount(), cacheStats.getCacheErrorRate()));
        }
      }

      if (status.getHttpArtifactUploadsScheduledCount() > 0) {
        columns.add(
            String.format(
                "%d/%d uploaded",
                status.getHttpArtifactUploadsSuccessCount(),
                status.getHttpArtifactUploadsScheduledCount()));

        if (status.getHttpArtifactUploadsFailureCount() > 0) {
          columns.add(
              String.format("%d upload errors", status.getHttpArtifactUploadsFailureCount()));
        }
      }

      lineBuilder.append(String.format("%s... %s", prefix, Joiner.on(", ").join(columns.build())));
    }

    if (status.getRulesFailureCount() != 0) {
      return ansi.asErrorText(lineBuilder.toString());
    } else if (status.getTotalRulesCount() > 0 && status.getRulesBuildingCount() == 0) {
      return ansi.asWarningText(lineBuilder.toString());
    } else {
      return lineBuilder.toString();
    }
  }

  @Override
  public String renderShortStatus(long slaveID) {
    Preconditions.checkArgument(slaveID >= 0 && slaveID < slaveStatuses.size());
    BuildSlaveStatus status = slaveStatuses.get((int) slaveID);

    String animationFrames = ":':.";
    int offset = (int) ((currentTimeMs / 400) % animationFrames.length());
    String glyph = "[" + animationFrames.charAt(offset) + "]";

    if (status.getRulesBuildingCount() == 0) {
      if (status.getRulesFailureCount() != 0) {
        glyph = "[X]";
      } else {
        glyph = "[ ]";
      }
    }

    if (status.getRulesFailureCount() != 0) {
      return ansi.asErrorText(glyph);
    } else if (status.getTotalRulesCount() != 0
        && status.getRulesFinishedCount() == status.getTotalRulesCount()) {
      return ansi.asSuccessText(glyph);
    } else {
      return glyph;
    }
  }
}
