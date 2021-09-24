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
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.io.file.PathMatcher;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Unit;
import com.facebook.buck.util.versioncontrol.HgCmdLineInterface;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WatchmanRecordChecker check ths last {@link WatchmanCursorRecorder} to inspect changes since last
 * build
 */
public class WatchmanRecordChecker {
  private static final Logger LOG = Logger.get(WatchmanRecordChecker.class);
  /**
   * The maximum number of watchman changes to process in each call to postEvents before giving up
   * and generating an overflow.
   */
  private static final int OVERFLOW_THRESHOLD = 10_000;

  private final WatchmanClient watchmanClient;
  private final WatchmanCursorRecorder watchmanCursorRecorder;
  private final ExecutorService executorService;
  private final HgCmdLineInterface hgCmd;
  private final long timeoutNanos;
  private final long queryWarnTimeoutNanos;
  private final ImmutableMap<AbsPath, WatchmanWatcherQuery> queries;

  public WatchmanRecordChecker(
      Watchman watchman,
      ImmutableSet<PathMatcher> ignorePaths,
      Path buckOutPath,
      HgCmdLineInterface hgCmd) {
    this(
        watchman.getPooledClient(),
        watchman.getQueryPollTimeoutNanos(),
        watchman.getQueryWarnTimeoutNanos(),
        filterQueryForWatchRoot(
            watchman.getProjectWatches(), ignorePaths, watchman.getCapabilities()),
        buckOutPath,
        hgCmd);
  }

  @VisibleForTesting
  public WatchmanRecordChecker(
      WatchmanClient watchmanClient,
      long timeoutNanos,
      long queryWarnTimeoutNanos,
      ImmutableMap<AbsPath, WatchmanWatcherQuery> queries,
      Path buckOutPath,
      HgCmdLineInterface hgCmd) {
    this.watchmanClient = watchmanClient;
    this.timeoutNanos = timeoutNanos;
    this.queryWarnTimeoutNanos = queryWarnTimeoutNanos;
    this.watchmanCursorRecorder = new WatchmanCursorRecorder(buckOutPath);
    this.queries = queries;
    this.hgCmd = hgCmd;
    this.executorService = MostExecutors.newSingleThreadExecutor(getClass().getName());
  }

  @VisibleForTesting
  static ImmutableMap<AbsPath, WatchmanWatcherQuery> filterQueryForWatchRoot(
      ImmutableMap<AbsPath, ProjectWatch> projectWatches,
      ImmutableSet<PathMatcher> ignorePaths,
      Set<Capability> watchmanCapabilities) {
    return projectWatches.entrySet().stream()
        .filter(projectWatch -> projectWatch.getValue().getProjectPrefix().isEmpty())
        .collect(
            ImmutableMap.toImmutableMap(
                Map.Entry::getKey,
                entry -> createQuery(entry.getValue(), ignorePaths, watchmanCapabilities)));
  }

  @VisibleForTesting
  static WatchmanWatcherQuery createQuery(
      ProjectWatch projectWatch,
      ImmutableSet<PathMatcher> ignorePaths,
      Set<Capability> watchmanCapabilities) {
    WatchRoot watchRoot = projectWatch.getWatchRoot();
    ForwardRelPath watchPrefix = projectWatch.getProjectPrefix();

    // Exclude any expressions added to this list.
    List<Object> excludeAnyOf = Lists.newArrayList("anyof");

    // Exclude all directories.
    excludeAnyOf.add(ImmutableList.of("type", "d"));

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

  /** This method tries to identify the file changes specific to this build */
  public void checkFileChanges(BuckEventBus eventBus) {
    Optional<WatchmanCursorRecord> recordFromLastBuildOptional =
        watchmanCursorRecorder.readWatchmanCursorRecord();
    try {
      String currentCommitId = hgCmd.currentRevisionId();
      LOG.debug(
          "Getting the current CommitId[%s] while checking local file changes", currentCommitId);

      // If there is no watchmanCursorRecord present
      if (!recordFromLastBuildOptional.isPresent()) {
        LOG.debug("Writing the watchmanCursorRecord for the first time");
        updateWatchmanRecordWithNewBaseCommit(currentCommitId);
        postFileChangeEventsFromHg(currentCommitId, eventBus);
        return;
      }

      WatchmanCursorRecord recordFromLastBuild = recordFromLastBuildOptional.get();
      String lastCommitBase = recordFromLastBuild.getCommitBase();
      String cursor = recordFromLastBuild.getWatchmanCursor();

      // If we change the base commit, we should use a new clock
      if (!lastCommitBase.equals(currentCommitId)) {
        LOG.debug("Update the watchmanCursorRecord as we are at a new base commit");
        updateWatchmanRecordWithNewBaseCommit(currentCommitId);
        postFileChangeEventsFromHg(currentCommitId, eventBus);
        return;
      }
      queryWatchmanForFileChanges(eventBus, cursor, currentCommitId);
    } catch (Exception e) {
      LOG.error(e, "Error while checking the local file changes");
    }
  }

  private void updateWatchmanRecordWithNewBaseCommit(String newCommitBaseId) {
    // get a new watchman clockID
    Optional<String> cursorOptional = queryWatchmanClock();
    cursorOptional.ifPresent(
        cursor ->
            watchmanCursorRecorder.recordWatchmanCursor(
                new WatchmanCursorRecord(cursor, newCommitBaseId)));
  }

  private void postFileChangeEventsFromHg(String fromCommitBase, BuckEventBus eventBus) {
    AbsPath path = queries.keySet().iterator().next();
    try {
      Set<String> fileChanges = hgCmd.changedFiles(fromCommitBase);
      if (fileChanges.size() > OVERFLOW_THRESHOLD) {
        postFileChangesEvent(eventBus, new ArrayList<>(), true);
        return;
      }
      ImmutableList.Builder<WatchmanStatusEvent.FileChangesSinceLastBuild.FileChange>
          fileChangesBuilder = ImmutableList.builder();
      fileChanges.forEach(
          file -> {
            String[] parts = file.split(" ", 2);
            fileChangesBuilder.add(
                new WatchmanStatusEvent.FileChangesSinceLastBuild.FileChange(
                    path.toString(), parts[1], parts[0]));
          });
      postFileChangesEvent(eventBus, fileChangesBuilder.build(), false);
    } catch (Exception e) {
      LOG.error(
          e, "Error getting the local file changes from Hg from commit base %s", fromCommitBase);
    }
  }

  private Optional<String> queryWatchmanClock() {
    Optional<WatchmanWatcherQuery> query = queries.values().stream().findFirst();
    if (!query.isPresent()) {
      return Optional.empty();
    }
    Either<WatchmanQueryResp.Generic, WatchmanClient.Timeout> queryResponse;
    WatchRoot watchRoot = query.get().getQueryPath();
    try {
      queryResponse =
          watchmanClient.queryWithTimeout(
              timeoutNanos,
              queryWarnTimeoutNanos,
              WatchmanQuery.clock(watchRoot, Optional.empty()));
    } catch (Exception e) {
      LOG.debug(e, "Error while querying watchman clock with query");
      return Optional.empty();
    }
    if (!queryResponse.isLeft()) {
      LOG.warn(
          "Could not get response from Watchman for query clock within %d ms",
          TimeUnit.NANOSECONDS.toMillis(timeoutNanos));
      return Optional.empty();
    }
    WatchmanQueryResp.Generic response = queryResponse.getLeft();
    Optional<String> clockIdOptional =
        Optional.ofNullable((String) response.getResp().get("clock"));
    if (clockIdOptional.isPresent()) {
      String clockId = clockIdOptional.get();
      LOG.info("Getting the clockId[%s] from watchman for watchRoot:%s", clockId, watchRoot);
      return Optional.of(clockId);
    }
    return Optional.empty();
  }

  private void queryWatchmanForFileChanges(
      BuckEventBus buckEventBus, String cursor, String currentCommitId) {
    try {
      List<Callable<Unit>> watchmanQueries = new ArrayList<>();
      queries.forEach(
          (key, value) ->
              watchmanQueries.add(
                  () -> {
                    queryWatchmanToPostEvents(
                        buckEventBus, key, watchmanClient, value, cursor, currentCommitId);
                    return Unit.UNIT;
                  }));
      watchmanQueries.forEach(executorService::submit);
    } finally {
      executorService.shutdown();
    }
  }

  @SuppressWarnings("unchecked")
  private void queryWatchmanToPostEvents(
      BuckEventBus buckEventBus,
      AbsPath cellPath,
      WatchmanClient client,
      WatchmanWatcherQuery query,
      String cursor,
      String currentCommitId)
      throws IOException, InterruptedException {
    try {
      Either<WatchmanQueryResp.Generic, WatchmanClient.Timeout> queryResponse;
      try {
        queryResponse =
            client.queryWithTimeout(timeoutNanos, queryWarnTimeoutNanos, query.toQuery(cursor));
      } catch (WatchmanQueryFailedException e) {
        LOG.debug(e, "Error while querying watchman with query %s.", query);
        return;
      }

      if (!queryResponse.isLeft()) {
        LOG.warn(
            "Could not get response from Watchman for query %s within %d ms",
            query, TimeUnit.NANOSECONDS.toMillis(timeoutNanos));
        return;
      }

      WatchmanQueryResp.Generic response = queryResponse.getLeft();
      if (!cursor.startsWith("c:")) {
        LOG.warn("Watchman was not using a valid cursor %s", cursor);
      }
      // Update the clockId
      String newCursor =
          Optional.ofNullable((String) response.getResp().get("clock"))
              .orElse(WatchmanFactory.NULL_CLOCK);
      LOG.debug("Updating Watchman Cursor from %s to %s", cursor, newCursor);
      // Record the latest query clock using the WatchmanWatcher
      LOG.debug("Record the watchman cursor[%s] for this build", newCursor);
      watchmanCursorRecorder.recordWatchmanCursor(
          new WatchmanCursorRecord(newCursor, currentCommitId));

      List<Map<String, Object>> files = (List<Map<String, Object>>) response.getResp().get("files");
      if (files == null) {
        LOG.debug("Watchman indicated 0 file changes with query %s", query);
        postFileChangesEvent(buckEventBus, new ArrayList<>(), false);
        return;
      }
      LOG.debug("Watchman indicated %d changes", files.size());
      if (files.size() > OVERFLOW_THRESHOLD) {
        // Consider to send this information to scuba as well
        LOG.debug(
            "Posting overflow event: too many files changed: %d > %d",
            files.size(), OVERFLOW_THRESHOLD);
        postFileChangesEvent(buckEventBus, new ArrayList<>(), true);
        return;
      }

      ImmutableList.Builder<WatchmanStatusEvent.FileChangesSinceLastBuild.FileChange>
          fileChangesBuilder = ImmutableList.builder();
      for (Map<String, Object> file : files) {
        String fileName = (String) file.get("name");
        if (fileName == null) {
          LOG.warn("Filename missing from watchman file response %s", file);
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
        WatchmanEvent.Type type = WatchmanEvent.Type.FILE;
        String stype = (String) file.get("type");
        if (stype != null) {
          switch (stype) {
            case "d":
              type = WatchmanEvent.Type.DIRECTORY;
              break;
            case "l":
              type = WatchmanEvent.Type.SYMLINK;
              break;
          }
        }
        ForwardRelPath filePath = ForwardRelPath.of(fileName);

        if (type != WatchmanEvent.Type.DIRECTORY) {
          WatchmanStatusEvent.FileChangesSinceLastBuild.FileChange fileChange =
              new WatchmanStatusEvent.FileChangesSinceLastBuild.FileChange(
                  cellPath.toString(), filePath.toString(), kind.toString());
          fileChangesBuilder.add(fileChange);
        }
      }
      postFileChangesEvent(buckEventBus, fileChangesBuilder.build(), false);
    } catch (Exception e) {
      String message = "The communication with watchman daemon has an error.";
      LOG.warn(e, message);
      throw e;
    }
  }

  private void postFileChangesEvent(
      BuckEventBus eventBus,
      List<WatchmanStatusEvent.FileChangesSinceLastBuild.FileChange> fileChangeList,
      boolean tooManyFileChanges) {
    WatchmanStatusEvent.FileChangesSinceLastBuild fileChangeEvent =
        WatchmanStatusEvent.fileChangesSinceLastBuild(fileChangeList, tooManyFileChanges);
    eventBus.post(fileChangeEvent);
    LOG.debug(
        "Posting FileChangesSinceLastBuild Event: %s, with %d file changes",
        fileChangeEvent, fileChangeList.size());
  }
}
