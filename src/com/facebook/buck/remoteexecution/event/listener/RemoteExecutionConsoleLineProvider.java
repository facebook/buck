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

package com.facebook.buck.remoteexecution.event.listener;

import com.facebook.buck.event.listener.interfaces.AdditionalConsoleLineProvider;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent.State;
import com.facebook.buck.remoteexecution.proto.RemoteExecutionMetadata;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.util.List;
import java.util.Locale;

/** Provides output lines to the console about the current state of Remote Execution. */
public class RemoteExecutionConsoleLineProvider implements AdditionalConsoleLineProvider {

  private final RemoteExecutionStatsProvider statsProvider;
  private final String formatIdVariableString;
  private final String formatDebugSessionIDString;
  private final String reSessionID;

  public RemoteExecutionConsoleLineProvider(
      RemoteExecutionStatsProvider statsProvider,
      RemoteExecutionConfig remoteExecutionConfig,
      RemoteExecutionMetadata remoteExecutionMetadata) {
    this.statsProvider = statsProvider;
    this.formatIdVariableString = RemoteExecutionConfig.FORMAT_SESSION_ID_VARIABLE_STRING;
    this.formatDebugSessionIDString = remoteExecutionConfig.getDebugURLFormatString();
    this.reSessionID = remoteExecutionMetadata.getReSessionId().getId();
  }

  @Override
  public ImmutableList<String> createConsoleLinesAtTime(long currentTimeMillis) {
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    ImmutableMap<RemoteExecutionActionEvent.State, Integer> actionsPerState =
        statsProvider.getActionsPerState();
    if (!hasFirstRemoteActionStarted(actionsPerState)) {
      return lines.build();
    }

    String sessionIdFormatted =
        formatDebugSessionIDString.replace(formatIdVariableString, reSessionID);
    String metadataLine = String.format("[RE] Metadata: Session ID=[%s]", sessionIdFormatted);
    lines.add(metadataLine);

    String actionsLine =
        String.format(
            "[RE] Actions: Local=%d Remote=[%s]",
            getLocallyBuiltRules(statsProvider.getTotalRulesBuilt(), actionsPerState),
            getStatesString(actionsPerState));
    lines.add(actionsLine);

    String casLine =
        String.format(
            "[RE] CAS: Upl=[Count:%d Size=%s] Dwl=[Count:%d Size=%s]",
            statsProvider.getCasUploads(),
            prettyPrintSize(statsProvider.getCasUploadSizeBytes()),
            statsProvider.getCasDownloads(),
            prettyPrintSize(statsProvider.getCasDownloadSizeBytes()));
    lines.add(casLine);

    return lines.build();
  }

  private boolean hasFirstRemoteActionStarted(ImmutableMap<State, Integer> actionsPerState) {
    return actionsPerState.values().stream().reduce((a, b) -> a + b).get() > 0;
  }

  private int getLocallyBuiltRules(
      int totalBuildRules, ImmutableMap<State, Integer> actionsPerState) {
    int remotelyExecutedBuildRules =
        actionsPerState.getOrDefault(State.ACTION_SUCCEEDED, 0)
            + actionsPerState.getOrDefault(State.ACTION_FAILED, 0);
    return Math.max(0, totalBuildRules - remotelyExecutedBuildRules);
  }

  private static String getStatesString(ImmutableMap<State, Integer> actionsPerState) {
    List<String> states = Lists.newArrayList();
    for (State state : RemoteExecutionActionEvent.State.values()) {
      String stateName = state.getAbbreviateName();
      Integer stateValue = actionsPerState.getOrDefault(state, 0);
      states.add(String.format("%s=%d", stateName, stateValue));
    }

    return Joiner.on(" ").join(states);
  }

  public static String prettyPrintSize(long sizeBytes) {
    return SizeUnit.toHumanReadableString(
        SizeUnit.getHumanReadableSize(sizeBytes, SizeUnit.BYTES), Locale.getDefault());
  }
}
