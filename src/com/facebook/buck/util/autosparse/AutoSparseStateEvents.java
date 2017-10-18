/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.util.autosparse;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.event.WorkAdvanceEvent;
import com.facebook.buck.log.views.JsonViews;
import com.facebook.buck.util.versioncontrol.SparseSummary;
import com.fasterxml.jackson.annotation.JsonView;

/** Events posted to mark AutoSparse progress. */
public abstract class AutoSparseStateEvents extends AbstractBuckEvent
    implements LeafEvent, WorkAdvanceEvent {
  // This class does nothing; it exists only to group AbstractBuckEvents.
  private AutoSparseStateEvents(EventKey eventKey) {
    super(eventKey);
  }

  @Override
  public String getCategory() {
    return "autosparse";
  }

  @Override
  public String getValueString() {
    return "";
  }

  /** Event posted immediately before refreshing the sparse profile */
  public static class SparseRefreshStarted extends AutoSparseStateEvents {
    public SparseRefreshStarted() {
      super(EventKey.unique());
    }

    @Override
    public String getEventName() {
      return "AutoSparseSparseRefreshStarted";
    }
  }

  /** Event posted immediately after refreshing the sparse profile */
  public static class SparseRefreshFinished extends AutoSparseStateEvents {
    @JsonView(JsonViews.MachineReadableLog.class)
    public final SparseSummary summary;

    public SparseRefreshFinished(
        AutoSparseStateEvents.SparseRefreshStarted started, SparseSummary summary) {
      super(started.getEventKey());
      this.summary = summary;
    }

    @Override
    public String getEventName() {
      return "AutoSparseSparseRefreshFinished";
    }
  }

  /** Event posted when the sparse profile refresh fails */
  public static class SparseRefreshFailed extends AutoSparseStateEvents {
    private static String PENDING_CHANGES = "cannot change sparseness due to pending changes";
    private static String NO_SUITABLE_RESPONSE = "no suitable response from remote hg";

    private static String STDOUT_HEADER = "stdout:\n";
    private static String STDERR_HEADER = "stderr:\n";
    private static String TRACEBACK_HEADER = "Traceback (most recent call last)";
    private static String ABORT_PREFIX = "abort: ";

    private static String SPARSE_ISSUE_INTRO =
        "hg sparse failed to refresh your working copy, due to the following problems:";

    @JsonView(JsonViews.MachineReadableLog.class)
    private String output;

    public SparseRefreshFailed(AutoSparseStateEvents.SparseRefreshStarted started, String output) {
      super(started.getEventKey());
      this.output = output;
    }

    @Override
    public String getEventName() {
      return "AutoSparseSparseRefreshFailed";
    }

    public String getFailureDetails() {
      // Look for failure information the end-user should know about
      if (output.contains(PENDING_CHANGES) || output.contains(NO_SUITABLE_RESPONSE)) {
        // Sparse profile can't be materialised
        String trimmed = output.trim();
        int outstart = trimmed.indexOf(STDOUT_HEADER) + STDOUT_HEADER.length();
        int errstart = trimmed.indexOf(STDERR_HEADER, outstart);
        int errend = trimmed.indexOf(TRACEBACK_HEADER, errstart);
        int abortstart = trimmed.lastIndexOf(ABORT_PREFIX);
        if (outstart > -1 && errstart > -1 && errend > -1 && abortstart > -1) {
          String stdout = trimmed.substring(outstart, errstart - 1).trim();
          String stderr = trimmed.substring(errstart + STDERR_HEADER.length(), errend).trim();
          String lastLine = trimmed.substring(abortstart + ABORT_PREFIX.length()).trim();
          return String.join(
              "\n",
              SPARSE_ISSUE_INTRO,
              "", // empty line separating intro from error
              stdout.isEmpty() ? stderr : stdout,
              lastLine,
              "" // extra empty line after
              );
        }
      }
      return "";
    }
  }
}
