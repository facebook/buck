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

package com.facebook.buck.io.watchman;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.io.watchman.WatchmanEvent.Type;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Unit;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
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
  }

  private static final Logger LOG = Logger.get(WatchmanWatcher.class);
  /**
   * The maximum number of watchman changes to process in each call to postEvents before giving up
   * and generating an overflow. The goal is to be able to process a reasonable number of human
   * generated changes quickly, but not spend a long time processing lots of changes after a branch
   * switch which will end up invalidating the entire cache anyway. If overflow is negative calls to
   * postEvents will just generate a single overflow event.
   */
  private static final int OVERFLOW_THRESHOLD = 10000;

  /** Attach changed files to the perf trace, if there aren't too many. */
  private static final int TRACE_CHANGES_THRESHOLD = 10;

  private static final long DEFAULT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);
  private static final long DEFAULT_WARN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(1);

  private final EventBus fileChangeEventBus;
  private final WatchmanClient watchmanClient;
  private final ImmutableMap<AbsPath, WatchmanWatcherQuery> queries;
  private final Map<AbsPath, WatchmanCursor> cursors;
  private final int numThreads;

  private final long timeoutMillis;

  public WatchmanWatcher(
      Watchman watchman,
      EventBus fileChangeEventBus,
      ImmutableSet<PathMatcher> ignorePaths,
      Map<AbsPath, WatchmanCursor> cursors,
      int numThreads) {
    this(
        fileChangeEventBus,
        watchman.getPooledClient(),
        DEFAULT_TIMEOUT_MILLIS,
        createQueries(watchman.getProjectWatches(), ignorePaths, watchman.getCapabilities()),
        cursors,
        numThreads);
  }

  @VisibleForTesting
  WatchmanWatcher(
      EventBus fileChangeEventBus,
      WatchmanClient watchmanClient,
      long timeoutMillis,
      ImmutableMap<AbsPath, WatchmanWatcherQuery> queries,
      Map<AbsPath, WatchmanCursor> cursors,
      int numThreads) {
    this.fileChangeEventBus = fileChangeEventBus;
    this.watchmanClient = watchmanClient;
    this.timeoutMillis = timeoutMillis;
    this.queries = queries;
    this.cursors = cursors;
    this.numThreads = numThreads;
  }

  @VisibleForTesting
  static ImmutableMap<AbsPath, WatchmanWatcherQuery> createQueries(
      ImmutableMap<AbsPath, ProjectWatch> projectWatches,
      ImmutableSet<PathMatcher> ignorePaths,
      Set<Capability> watchmanCapabilities) {
    ImmutableMap.Builder<AbsPath, WatchmanWatcherQuery> watchmanQueryBuilder =
        ImmutableMap.builder();
    for (Map.Entry<AbsPath, ProjectWatch> entry : projectWatches.entrySet()) {
      watchmanQueryBuilder.put(
          entry.getKey(), createQuery(entry.getValue(), ignorePaths, watchmanCapabilities));
    }
    return watchmanQueryBuilder.build();
  }

  @VisibleForTesting
  static WatchmanWatcherQuery createQuery(
      ProjectWatch projectWatch,
      ImmutableSet<PathMatcher> ignorePaths,
      Set<Capability> watchmanCapabilities) {
    String watchRoot = projectWatch.getWatchRoot();
    Optional<String> watchPrefix = projectWatch.getProjectPrefix();

    // Exclude any expressions added to this list.
    List<Object> excludeAnyOf = Lists.newArrayList("anyof");

    // Exclude all directories.
    excludeAnyOf.add(Lists.newArrayList("type", "d"));

    // Exclude all files under directories in project.ignorePaths.
    //
    // Note that it's OK to exclude .git in a query (event though it's
    // not currently OK to exclude .git in .watchmanconfig). This id
    // because watchman's .git cookie magic is done before the query
    // is applied.
    for (PathMatcher ignorePathOrGlob : ignorePaths) {
      excludeAnyOf.add(ignorePathOrGlob.toWatchmanMatchQuery(watchmanCapabilities));
    }

    return ImmutableWatchmanWatcherQuery.ofImpl(
        watchRoot,
        ImmutableList.of("not", excludeAnyOf),
        ImmutableList.of("name", "exists", "new", "type"),
        watchPrefix);
  }

  @VisibleForTesting
  Optional<WatchmanQuery.Query> getWatchmanQuery(AbsPath cellPath) {
    if (queries.containsKey(cellPath) && cursors.containsKey(cellPath)) {
      return Optional.of(queries.get(cellPath).toQuery(cursors.get(cellPath).get()));
    }
    return Optional.empty();
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
    ExecutorService executorService =
        MostExecutors.newMultiThreadExecutor(getClass().getName(), numThreads);
    buckEventBus.post(WatchmanStatusEvent.started());

    try {
      List<Callable<Unit>> watchmanQueries = new ArrayList<>();
      for (AbsPath cellPath : queries.keySet()) {
        watchmanQueries.add(
            () -> {
              WatchmanWatcherQuery query = queries.get(cellPath);
              WatchmanCursor cursor = cursors.get(cellPath);
              if (query != null && cursor != null) {
                try (SimplePerfEvent.Scope perfEvent =
                    SimplePerfEvent.scope(
                        buckEventBus.isolated(),
                        SimplePerfEvent.PerfEventTitle.of("check_watchman"),
                        "cell",
                        cellPath)) {
                  // Include the cellPath in the finished event so it can be matched with the begin
                  // event.
                  perfEvent.appendFinishedInfo("cell", cellPath);
                  postEvents(
                      buckEventBus,
                      freshInstanceAction,
                      cellPath,
                      watchmanClient,
                      query,
                      cursor,
                      filesHaveChanged,
                      perfEvent);
                }
              }
              return Unit.UNIT;
            });
      }

      // Run all of the Watchman queries in parallel. This can be significant if you have a lot of
      // cells.
      List<Future<Unit>> futures = executorService.invokeAll(watchmanQueries);
      for (Future<Unit> future : futures) {
        try {
          future.get();
        } catch (ExecutionException e) {
          Throwable cause = e.getCause();
          if (cause != null) {
            Throwables.throwIfUnchecked(cause);
            Throwables.propagateIfPossible(cause, IOException.class);
            Throwables.propagateIfPossible(cause, InterruptedException.class);
          }
          throw new RuntimeException(e);
        }
      }

      if (!filesHaveChanged.get()) {
        buckEventBus.post(WatchmanStatusEvent.zeroFileChanges());
      }
    } finally {
      buckEventBus.post(WatchmanStatusEvent.finished());
      executorService.shutdown();
    }
  }

  @SuppressWarnings("unchecked")
  private void postEvents(
      BuckEventBus buckEventBus,
      FreshInstanceAction freshInstanceAction,
      AbsPath cellPath,
      WatchmanClient client,
      WatchmanWatcherQuery query,
      WatchmanCursor cursor,
      AtomicBoolean filesHaveChanged,
      SimplePerfEvent.Scope perfEvent)
      throws IOException, InterruptedException {
    try {
      Either<Map<String, Object>, WatchmanClient.Timeout> queryResponse;
      try (SimplePerfEvent.Scope ignored =
          SimplePerfEvent.scope(buckEventBus.isolated(), "query")) {
        queryResponse =
            client.queryWithTimeout(
                TimeUnit.MILLISECONDS.toNanos(timeoutMillis),
                DEFAULT_WARN_TIMEOUT_NANOS,
                query.toQuery(cursor.get()));
      }

      try (SimplePerfEvent.Scope ignored =
          SimplePerfEvent.scope(buckEventBus.isolated(), "process_response")) {
        if (!queryResponse.isLeft()) {
          LOG.warn(
              "Could not get response from Watchman for query %s within %d ms",
              query, timeoutMillis);
          postWatchEvent(
              buckEventBus,
              ImmutableWatchmanOverflowEvent.ofImpl(
                  cellPath,
                  "Timed out after "
                      + TimeUnit.MILLISECONDS.toSeconds(timeoutMillis)
                      + " sec waiting for watchman query."));
          filesHaveChanged.set(true);
          return;
        }

        Map<String, ? extends Object> response = queryResponse.getLeft();
        String error = (String) response.get("error");
        if (error != null) {
          // This message is not de-duplicated via WatchmanDiagnostic.
          WatchmanWatcherException e = new WatchmanWatcherException(error);
          LOG.debug(e, "Error in Watchman output. Posting an overflow event to flush the caches");
          postWatchEvent(
              buckEventBus,
              ImmutableWatchmanOverflowEvent.ofImpl(
                  cellPath, "Watchman error occurred: " + e.getMessage()));
          throw e;
        }

        if (cursor.get().startsWith("c:")) {
          // Update the clockId
          String newCursor =
              Optional.ofNullable((String) response.get("clock"))
                  .orElse(WatchmanFactory.NULL_CLOCK);
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
              postWatchEvent(
                  buckEventBus,
                  ImmutableWatchmanOverflowEvent.ofImpl(
                      cellPath, "Watchman has been initialized recently."));
              break;
          }
          filesHaveChanged.set(true);
          return;
        }

        List<Map<String, Object>> files = (List<Map<String, Object>>) response.get("files");
        if (files == null) {
          if (freshInstanceAction == FreshInstanceAction.NONE) {
            filesHaveChanged.set(true);
          }
          return;
        }
        LOG.debug("Watchman indicated %d changes", files.size());
        if (files.size() > OVERFLOW_THRESHOLD) {
          LOG.warn(
              "Posting overflow event: too many files changed: %d > %d",
              files.size(), OVERFLOW_THRESHOLD);
          postWatchEvent(
              buckEventBus,
              ImmutableWatchmanOverflowEvent.ofImpl(cellPath, "Too many files changed."));
          filesHaveChanged.set(true);
          return;
        }
        if (files.size() < TRACE_CHANGES_THRESHOLD) {
          perfEvent.appendFinishedInfo("files", files);
        } else {
          perfEvent.appendFinishedInfo("files_sample", files.subList(0, TRACE_CHANGES_THRESHOLD));
        }

        List<WatchmanMultiplePathEvent.Change> changes = new ArrayList<>(files.size());
        for (Map<String, Object> file : files) {
          String fileName = (String) file.get("name");
          if (fileName == null) {
            LOG.warn("Filename missing from watchman file response %s", file);
            postWatchEvent(
                buckEventBus,
                ImmutableWatchmanOverflowEvent.ofImpl(
                    cellPath, "Filename missing from watchman response."));
            filesHaveChanged.set(true);
            return;
          }
          Boolean fileNew = (Boolean) file.get("new");
          WatchmanEvent.Kind kind = WatchmanEvent.Kind.MODIFY;
          if (fileNew != null && fileNew) {
            kind = WatchmanEvent.Kind.CREATE;
          }
          Boolean fileExists = (Boolean) file.get("exists");
          if (fileExists != null && !fileExists) {
            kind = WatchmanEvent.Kind.DELETE;
          }

          // Following legacy behavior, everything we get from Watchman is interpreted as file
          // changes unless explicitly specified with `type` field
          WatchmanEvent.Type type = Type.FILE;
          String stype = (String) file.get("type");
          if (stype != null) {
            switch (stype) {
              case "d":
                type = Type.DIRECTORY;
                break;
              case "l":
                type = Type.SYMLINK;
                break;
            }
          }

          ForwardRelativePath filePath = ForwardRelativePath.of(fileName);

          changes.add(ImmutableChange.ofImpl(type, filePath, kind));

          if (type != WatchmanEvent.Type.DIRECTORY) {
            // WatchmanPathEvent is sent for everything but directories - this is legacy
            // behavior and we want to keep it.
            // TODO(buck_team): switch everything to use WatchmanMultiplePathEvent and retire
            // WatchmanPathEvent
            postWatchEvent(
                buckEventBus, ImmutableWatchmanPathEvent.ofImpl(cellPath, kind, filePath));
          }
        }

        if (!changes.isEmpty()) {
          postWatchEvent(
              buckEventBus, ImmutableWatchmanMultiplePathEvent.ofImpl(cellPath, changes));
        }

        if (!files.isEmpty() || freshInstanceAction == FreshInstanceAction.NONE) {
          filesHaveChanged.set(true);
        }
      }
    } catch (InterruptedException e) {
      String message = "The communication with watchman daemon has been interrupted.";
      LOG.warn(e, message);
      // Events may have been lost, signal overflow.
      postWatchEvent(buckEventBus, ImmutableWatchmanOverflowEvent.ofImpl(cellPath, message));
      Threads.interruptCurrentThread();
      throw e;
    } catch (IOException e) {
      String message =
          "There was an error while communicating with the watchman daemon: " + e.getMessage();
      LOG.error(e, message);
      // Events may have been lost, signal overflow.
      postWatchEvent(buckEventBus, ImmutableWatchmanOverflowEvent.ofImpl(cellPath, message));
      throw e;
    }
  }

  private void postWatchEvent(BuckEventBus eventBus, WatchmanEvent event) {
    LOG.debug("Posting WatchEvent: %s", event);
    fileChangeEventBus.post(event);

    // Post analogous Status events for logging/status.
    if (event instanceof WatchmanOverflowEvent) {
      WatchmanOverflowEvent overflowEvent = (WatchmanOverflowEvent) event;

      eventBus.post(
          WatchmanStatusEvent.overflow(
              overflowEvent.getReason(), overflowEvent.getCellPath().getPath()));
    } else if (event instanceof WatchmanPathEvent) {
      WatchmanPathEvent pathEvent = (WatchmanPathEvent) event;
      switch (pathEvent.getKind()) {
        case CREATE:
          eventBus.post(WatchmanStatusEvent.fileCreation(pathEvent.toString()));
          return;
        case DELETE:
          eventBus.post(WatchmanStatusEvent.fileDeletion(pathEvent.toString()));
          return;
        case MODIFY:
          // No analog for this event.
          return;
      }
      throw new IllegalStateException("Unhandled case: " + pathEvent.getKind());
    }
  }
}
