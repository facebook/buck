/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.event.listener;

import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent;
import com.facebook.buck.util.Ansi;
import com.google.common.base.Preconditions;
import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Range;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A renderer for creating remote execution related lines on the console.
 *
 * <p>Maintains an internal mapping of build targets and their associated {@link
 * RemoteExecutionActionEvent.Started} at a particular instant in time defined by {@link
 * #currentTimeMillis}. The mapping is ordered and immutable, and each map entry's index in the
 * total ordering is treated as an ID. Targets that have not been executing long enough are ignored.
 */
public class RemoteExecutionStateRenderer implements MultiStateRenderer {

  private static final String EXECUTOR_COLLECTION_LABEL = "RE";
  private static final String REMOTE_EXECUTION_PREFIX = "[RE] ";

  private final long currentTimeMillis;
  private final int maxConcurrentExecutions;
  private final CommonThreadStateRenderer commonThreadStateRenderer;
  private final ImmutableList<RemoteExecutionActionEvent.Started> remotelyBuildingTargets;

  public RemoteExecutionStateRenderer(
      Ansi ansi,
      Function<Long, String> formatTimeFunction,
      long currentTimeMillis,
      int outputMaxColumns,
      long minimumDurationMillis,
      int maxConcurrentExecutions,
      ImmutableList<RemoteExecutionActionEvent.Started> remotelyBuildingTargets) {
    this.currentTimeMillis = currentTimeMillis;
    this.maxConcurrentExecutions = maxConcurrentExecutions;
    this.commonThreadStateRenderer =
        new CommonThreadStateRenderer(
            ansi,
            formatTimeFunction,
            currentTimeMillis,
            outputMaxColumns,
            /* threadInformationMap= */ ImmutableMap.of());
    this.remotelyBuildingTargets =
        filterByElapsedTime(remotelyBuildingTargets, minimumDurationMillis);
  }

  @Override
  public String getExecutorCollectionLabel() {
    return EXECUTOR_COLLECTION_LABEL;
  }

  @Override
  public int getExecutorCount() {
    return maxConcurrentExecutions;
  }

  @Override
  public ImmutableList<Long> getSortedIds(boolean unused) {
    return ImmutableList.copyOf(
        ContiguousSet.create(
                Range.open(-1, remotelyBuildingTargets.size()), DiscreteDomain.integers())
            .stream()
            .map(i -> Long.valueOf(i))
            .collect(Collectors.toList()));
  }

  @Override
  public String renderStatusLine(long targetId) {
    RemoteExecutionActionEvent.Started event = getEventByTargetId(targetId);
    return REMOTE_EXECUTION_PREFIX
        + commonThreadStateRenderer.renderLine(
            Optional.of(event.getBuildTarget()),
            Optional.of(event),
            /* runningStep= */ Optional.empty(),
            /* stepCategory= */ Optional.empty(),
            /* placeholderStepInformation= */ Optional.empty(),
            getElapsedTimeMsForEvent(event));
  }

  @Override
  public String renderShortStatus(long targetId) {
    return REMOTE_EXECUTION_PREFIX
        + commonThreadStateRenderer.renderShortStatus(
            /* isActive= */ true,
            /* renderSubtle= */ true,
            getElapsedTimeMsForEvent(getEventByTargetId(targetId)));
  }

  private ImmutableList<RemoteExecutionActionEvent.Started> filterByElapsedTime(
      ImmutableList<RemoteExecutionActionEvent.Started> unfiltered, long minimumDurationMillis) {
    return ImmutableList.copyOf(
        unfiltered.stream()
            .filter(event -> getElapsedTimeMsForEvent(event) >= minimumDurationMillis)
            .collect(Collectors.toList()));
  }

  private long getElapsedTimeMsForEvent(RemoteExecutionActionEvent.Started event) {
    return currentTimeMillis - event.getTimestampMillis();
  }

  private RemoteExecutionActionEvent.Started getEventByTargetId(long targetId) {
    Preconditions.checkArgument(
        targetId >= 0 && targetId < remotelyBuildingTargets.size(), "Received invalid targetId.");
    return Preconditions.checkNotNull(
        remotelyBuildingTargets.get(Math.toIntExact(targetId)), "Build target unexpectedly null.");
  }
}
