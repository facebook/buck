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

package com.facebook.buck.parser;

import com.facebook.buck.counters.Counter;
import com.facebook.buck.counters.IntegerCounter;
import com.facebook.buck.counters.TagSetCounter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/** Counters of {@link DaemonicParserState}. */
class DaemonicParserStateCounters {
  private static final String COUNTER_CATEGORY = "buck_parser_state";
  private static final String INVALIDATED_BY_DEFAULT_INCLUDES_COUNTER_NAME =
      "invalidated_by_default_includes";
  private static final String INVALIDATED_BY_WATCH_OVERFLOW_COUNTER_NAME =
      "invalidated_by_watch_overflow";
  private static final String BUILD_FILES_INVALIDATED_BY_FILE_ADD_OR_REMOVE_COUNTER_NAME =
      "build_files_invalidated_by_add_or_remove";
  private static final String FILES_CHANGED_COUNTER_NAME = "files_changed";
  private static final String RULES_INVALIDATED_BY_WATCH_EVENTS_COUNTER_NAME =
      "rules_invalidated_by_watch_events";
  private static final String PATHS_ADDED_OR_REMOVED_INVALIDATING_BUILD_FILES =
      "paths_added_or_removed_invalidating_build_files";

  private final IntegerCounter cacheInvalidatedByDefaultIncludesChangeCounter;
  private final IntegerCounter cacheInvalidatedByWatchOverflowCounter;
  private final IntegerCounter buildFilesInvalidatedByFileAddOrRemoveCounter;
  private final IntegerCounter filesChangedCounter;
  private final IntegerCounter rulesInvalidatedByWatchEventsCounter;
  private final TagSetCounter pathsAddedOrRemovedInvalidatingBuildFiles;

  void recordCacheInvalidatedByDefaultIncludesChange() {
    this.cacheInvalidatedByDefaultIncludesChangeCounter.inc();
  }

  void recordCacheInvalidatedByWatchOverflow() {
    this.cacheInvalidatedByWatchOverflowCounter.inc();
  }

  void recordBuildFilesInvalidatedByFileAddOrRemove(int size) {
    this.buildFilesInvalidatedByFileAddOrRemoveCounter.inc(size);
  }

  void recordFilesChanged() {
    this.filesChangedCounter.inc();
  }

  void recordRulesInvalidatedByWatchEvents(int invalidatedNodes) {
    this.rulesInvalidatedByWatchEventsCounter.inc(invalidatedNodes);
  }

  void recordPathsAddedOrRemovedInvalidatingBuildFiles(String file) {
    this.pathsAddedOrRemovedInvalidatingBuildFiles.add(file);
  }

  public DaemonicParserStateCounters() {
    this.cacheInvalidatedByDefaultIncludesChangeCounter =
        new IntegerCounter(
            COUNTER_CATEGORY, INVALIDATED_BY_DEFAULT_INCLUDES_COUNTER_NAME, ImmutableMap.of());
    this.cacheInvalidatedByWatchOverflowCounter =
        new IntegerCounter(
            COUNTER_CATEGORY, INVALIDATED_BY_WATCH_OVERFLOW_COUNTER_NAME, ImmutableMap.of());
    this.buildFilesInvalidatedByFileAddOrRemoveCounter =
        new IntegerCounter(
            COUNTER_CATEGORY,
            BUILD_FILES_INVALIDATED_BY_FILE_ADD_OR_REMOVE_COUNTER_NAME,
            ImmutableMap.of());
    this.filesChangedCounter =
        new IntegerCounter(COUNTER_CATEGORY, FILES_CHANGED_COUNTER_NAME, ImmutableMap.of());
    this.rulesInvalidatedByWatchEventsCounter =
        new IntegerCounter(
            COUNTER_CATEGORY, RULES_INVALIDATED_BY_WATCH_EVENTS_COUNTER_NAME, ImmutableMap.of());
    this.pathsAddedOrRemovedInvalidatingBuildFiles =
        new TagSetCounter(
            COUNTER_CATEGORY, PATHS_ADDED_OR_REMOVED_INVALIDATING_BUILD_FILES, ImmutableMap.of());
  }

  /** Get all counters. */
  public ImmutableList<Counter> get() {
    return ImmutableList.of(
        cacheInvalidatedByDefaultIncludesChangeCounter,
        cacheInvalidatedByWatchOverflowCounter,
        buildFilesInvalidatedByFileAddOrRemoveCounter,
        filesChangedCounter,
        rulesInvalidatedByWatchEventsCounter,
        pathsAddedOrRemovedInvalidatingBuildFiles);
  }
}
