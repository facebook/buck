/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.PathOrGlobMatcher;
import com.facebook.buck.io.ProjectWatch;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.io.Watchman.Capability;
import com.facebook.buck.io.WatchmanClient;
import com.facebook.buck.io.WatchmanCursor;
import com.facebook.buck.io.WatchmanDiagnostic;
import com.facebook.buck.io.WatchmanDiagnosticEvent;
import com.facebook.buck.io.WatchmanQuery;
import com.facebook.buck.log.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Queries Watchman for changes to a path. */
public class WatchmanWatcher {

  // Action to take if Watchman indicates a fresh instance (which happens
  // both on the first buckd command as well as if Watchman needs to recrawl
  // for any reason).
  public enum FreshInstanceAction {
    NONE,
    POST_OVERFLOW_EVENT,
    ;
  };

  // The type of cursor used to communicate with Watchman
  public enum CursorType {
    NAMED,
    CLOCK_ID,
  };

  private static final Logger LOG = Logger.get(WatchmanWatcher.class);
  /**
   * The maximum number of watchman changes to process in each call to postEvents before giving up
   * and generating an overflow. The goal is to be able to process a reasonable number of human
   * generated changes quickly, but not spend a long time processing lots of changes after a branch
   * switch which will end up invalidating the entire cache anyway. If overflow is negative calls to
   * postEvents will just generate a single overflow event.
   */
  private static final int OVERFLOW_THRESHOLD = 10000;

  private static final long DEFAULT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);

  private final EventBus fileChangeEventBus;
  private final WatchmanClient watchmanClient;
  private final ImmutableMap<Path, WatchmanQuery> queries;
  private Map<Path, WatchmanCursor> cursors;

  private final long timeoutMillis;

  public WatchmanWatcher(
      ImmutableMap<Path, ProjectWatch> projectWatch,
      EventBus fileChangeEventBus,
      ImmutableSet<PathOrGlobMatcher> ignorePaths,
      Watchman watchman,
      Map<Path, WatchmanCursor> cursors) {
    this(
        fileChangeEventBus,
        watchman.getWatchmanClient().get(),
        DEFAULT_TIMEOUT_MILLIS,
        createQueries(projectWatch, ignorePaths, watchman.getCapabilities()),
        cursors);
  }

  @VisibleForTesting
  WatchmanWatcher(
      EventBus fileChangeEventBus,
      WatchmanClient watchmanClient,
      long timeoutMillis,
      ImmutableMap<Path, WatchmanQuery> queries,
      Map<Path, WatchmanCursor> cursors) {
    this.fileChangeEventBus = fileChangeEventBus;
    this.watchmanClient = watchmanClient;
    this.timeoutMillis = timeoutMillis;
    this.queries = queries;
    this.cursors = cursors;
  }

  @VisibleForTesting
  static ImmutableMap<Path, WatchmanQuery> createQueries(
      ImmutableMap<Path, ProjectWatch> projectWatches,
      ImmutableSet<PathOrGlobMatcher> ignorePaths,
      Set<Capability> watchmanCapabilities) {
    ImmutableMap.Builder<Path, WatchmanQuery> watchmanQueryBuilder = ImmutableMap.builder();
    for (Map.Entry<Path, ProjectWatch> entry : projectWatches.entrySet()) {
      watchmanQueryBuilder.put(
          entry.getKey(), createQuery(entry.getValue(), ignorePaths, watchmanCapabilities));
    }
    return watchmanQueryBuilder.build();
  }

  @VisibleForTesting
  static WatchmanQuery createQuery(
      ProjectWatch projectWatch,
      ImmutableSet<PathOrGlobMatcher> ignorePaths,
      Set<Capability> watchmanCapabilities) {
    String watchRoot = projectWatch.getWatchRoot();
    Optional<String> watchPrefix = projectWatch.getProjectPrefix();

    // Exclude any expressions added to this list.
    List<Object> excludeAnyOf = Lists.newArrayList("anyof");

    // Exclude all directories.
    excludeAnyOf.add(Lists.newArrayList("type", "d"));

    Path projectRoot = Paths.get(watchRoot);
    projectRoot = projectRoot.resolve(watchPrefix.orElse(""));

    // Exclude all files under directories in project.ignorePaths.
    //
    // Note that it's OK to exclude .git in a query (event though it's
    // not currently OK to exclude .git in .watchmanconfig). This id
    // because watchman's .git cookie magic is done before the query
    // is applied.
    for (PathOrGlobMatcher ignorePathOrGlob : ignorePaths) {
      switch (ignorePathOrGlob.getType()) {
        case PATH:
          Path ignorePath = ignorePathOrGlob.getPath();
          if (ignorePath.isAbsolute()) {
            ignorePath = MorePaths.relativize(projectRoot, ignorePath);
          }
          if (watchmanCapabilities.contains(Capability.DIRNAME)) {
            excludeAnyOf.add(Lists.newArrayList("dirname", ignorePath.toString()));
          } else {
            excludeAnyOf.add(
                Lists.newArrayList(
                    "match", ignorePath.toString() + File.separator + "*", "wholename"));
          }
          break;
        case GLOB:
          String ignoreGlob = ignorePathOrGlob.getGlob();
          excludeAnyOf.add(
              Lists.newArrayList(
                  "match", ignoreGlob, "wholename", ImmutableMap.of("includedotfiles", true)));
          break;
        default:
          throw new RuntimeException(
              String.format("Unsupported type: '%s'", ignorePathOrGlob.getType()));
      }
    }

    // Note that we use LinkedHashMap so insertion order is preserved. That
    // helps us write tests that don't depend on the undefined order of HashMap.
    Map<String, Object> sinceParams = new LinkedHashMap<>();
    sinceParams.put("expression", Lists.newArrayList("not", excludeAnyOf));
    sinceParams.put("empty_on_fresh_instance", true);
    sinceParams.put("fields", Lists.newArrayList("name", "exists", "new"));
    if (watchPrefix.isPresent()) {
      sinceParams.put("relative_root", watchPrefix.get());
    }
    return WatchmanQuery.of(watchRoot, sinceParams);
  }

  @VisibleForTesting
  ImmutableList<Object> getWatchmanQuery(Path cellPath) {
    if (queries.containsKey(cellPath) && cursors.containsKey(cellPath)) {
      return queries.get(cellPath).toList(cursors.get(cellPath).get());
    }
    return ImmutableList.of();
  }

  /**
   * Query Watchman for file change events. If too many events are pending or an error occurs an
   * overflow event is posted to the EventBus signalling that events may have been lost (and so
   * typically caches must be cleared to avoid inconsistency). Interruptions and IOExceptions are
   * propagated to callers, but typically if overflow events are handled conservatively by
   * subscribers then no other remedial action is required.
   *
   * <p>Any diagnostics posted by Watchman are added to watchmanDiagnosticCache.
   */
  public void postEvents(BuckEventBus buckEventBus, FreshInstanceAction freshInstanceAction)
      throws IOException, InterruptedException {
    // Speculatively set to false
    AtomicBoolean filesHaveChanged = new AtomicBoolean(false);
    for (Path cellPath : queries.keySet()) {
      WatchmanQuery query = queries.get(cellPath);
      WatchmanCursor cursor = cursors.get(cellPath);
      if (query != null && cursor != null) {
        try (SimplePerfEvent.Scope ignored =
            SimplePerfEvent.scope(
                buckEventBus, PerfEventId.of("check_watchman"), "cell", cellPath)) {
          postEvents(buckEventBus, freshInstanceAction, cellPath, query, cursor, filesHaveChanged);
        }
      }
    }
    if (!filesHaveChanged.get()) {
      buckEventBus.post(WatchmanStatusEvent.zeroFileChanges());
    }
  }

  @SuppressWarnings("unchecked")
  private void postEvents(
      BuckEventBus buckEventBus,
      FreshInstanceAction freshInstanceAction,
      Path cellPath,
      WatchmanQuery query,
      WatchmanCursor cursor,
      AtomicBoolean filesHaveChanged)
      throws IOException, InterruptedException {
    try {
      Optional<? extends Map<String, ? extends Object>> queryResponse;
      try (SimplePerfEvent.Scope ignored = SimplePerfEvent.scope(buckEventBus, "query")) {
        queryResponse =
            watchmanClient.queryWithTimeout(
                TimeUnit.MILLISECONDS.toNanos(timeoutMillis), query.toList(cursor.get()).toArray());
      }

      try (SimplePerfEvent.Scope ignored =
          SimplePerfEvent.scope(buckEventBus, "process_response")) {
        if (!queryResponse.isPresent()) {
          LOG.warn(
              "Could not get response from Watchman for query %s within %d ms",
              query, timeoutMillis);
          postWatchEvent(
              WatchmanOverflowEvent.of(
                  cellPath, "Query to Watchman timed out after " + timeoutMillis + "ms"));
          filesHaveChanged.set(true);
          return;
        }

        Map<String, ? extends Object> response = queryResponse.get();
        String error = (String) response.get("error");
        if (error != null) {
          // This message is not de-duplicated via WatchmanDiagnostic.
          WatchmanWatcherException e = new WatchmanWatcherException(error);
          LOG.error(e, "Error in Watchman output. Posting an overflow event to flush the caches");
          postWatchEvent(
              WatchmanOverflowEvent.of(cellPath, "Watchman Error occurred - " + e.getMessage()));
          throw e;
        }

        if (cursor.get().startsWith("c:")) {
          // Update the clockId
          String newCursor =
              Optional.ofNullable((String) response.get("clock")).orElse(Watchman.NULL_CLOCK);
          LOG.debug("Updating Watchman Cursor from %s to %s", cursor.get(), newCursor);
          cursor.set(newCursor);
        }

        String warning = (String) response.get("warning");
        if (warning != null) {
          buckEventBus.post(
              new WatchmanDiagnosticEvent(
                  WatchmanDiagnostic.of(WatchmanDiagnostic.Level.WARNING, warning)));
        }

        Boolean isFreshInstance = (Boolean) response.get("is_fresh_instance");
        if (isFreshInstance != null && isFreshInstance) {
          LOG.debug(
              "Watchman indicated a fresh instance (fresh instance action %s)",
              freshInstanceAction);
          switch (freshInstanceAction) {
            case NONE:
              break;
            case POST_OVERFLOW_EVENT:
              postWatchEvent(WatchmanOverflowEvent.of(cellPath, "New Buck instance"));
              break;
          }
          filesHaveChanged.set(true);
          return;
        }

        List<Map<String, Object>> files = (List<Map<String, Object>>) response.get("files");
        if (files != null) {
          if (files.size() > OVERFLOW_THRESHOLD) {
            String message =
                "Too many changed files (" + files.size() + " > " + OVERFLOW_THRESHOLD + ")";
            LOG.warn("%s, posting overflow event", message);
            postWatchEvent(WatchmanOverflowEvent.of(cellPath, message));
            filesHaveChanged.set(true);
            return;
          }

          for (Map<String, Object> file : files) {
            String fileName = (String) file.get("name");
            if (fileName == null) {
              LOG.warn("Filename missing from Watchman file response %s", file);
              postWatchEvent(
                  WatchmanOverflowEvent.of(cellPath, "Filename missing from Watchman response"));
              filesHaveChanged.set(true);
              return;
            }
            Boolean fileNew = (Boolean) file.get("new");
            WatchmanPathEvent.Kind kind = WatchmanPathEvent.Kind.MODIFY;
            if (fileNew != null && fileNew) {
              kind = WatchmanPathEvent.Kind.CREATE;
            }
            Boolean fileExists = (Boolean) file.get("exists");
            if (fileExists != null && !fileExists) {
              kind = WatchmanPathEvent.Kind.DELETE;
            }
            postWatchEvent(WatchmanPathEvent.of(cellPath, kind, Paths.get(fileName)));
          }

          if (!files.isEmpty() || freshInstanceAction == FreshInstanceAction.NONE) {
            filesHaveChanged.set(true);
          }

          LOG.debug("Posted %d Watchman events.", files.size());
        } else {
          if (freshInstanceAction == FreshInstanceAction.NONE) {
            filesHaveChanged.set(true);
          }
        }
      }
    } catch (InterruptedException e) {
      String message = "Watchman communication interrupted";
      LOG.warn(e, message);
      // Events may have been lost, signal overflow.
      postWatchEvent(WatchmanOverflowEvent.of(cellPath, message));
      Thread.currentThread().interrupt();
      throw e;
    } catch (IOException e) {
      String message = "I/O error talking to Watchman";
      LOG.error(e, message);
      // Events may have been lost, signal overflow.
      postWatchEvent(WatchmanOverflowEvent.of(cellPath, message + " - " + e.getMessage()));
      throw e;
    }
  }

  private void postWatchEvent(WatchmanEvent event) {
    LOG.warn("Posting WatchEvent: %s", event);
    fileChangeEventBus.post(event);
  }
}
