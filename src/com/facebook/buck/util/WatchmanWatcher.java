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
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.PathOrGlobMatcher;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.io.Watchman.Capability;
import com.facebook.buck.io.WatchmanClient;
import com.facebook.buck.io.WatchmanDiagnostic;
import com.facebook.buck.io.WatchmanDiagnosticCache;
import com.facebook.buck.io.WatchmanQuery;
import com.facebook.buck.log.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * Queries Watchman for changes to a path.
 */
public class WatchmanWatcher {

  // Action to take if Watchman indicates a fresh instance (which happens
  // both on the first buckd command as well as if Watchman needs to recrawl
  // for any reason).
  public enum FreshInstanceAction {
      NONE,
      POST_OVERFLOW_EVENT,
      ;
  };

  private static final Logger LOG = Logger.get(WatchmanWatcher.class);
  private static final int DEFAULT_OVERFLOW_THRESHOLD = 10000;
  private static final long DEFAULT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);

  private final EventBus fileChangeEventBus;
  private final WatchmanClient watchmanClient;
  private final WatchmanQuery query;
  private String mSinceCursor;

  /**
   * The maximum number of watchman changes to process in each call to postEvents before
   * giving up and generating an overflow. The goal is to be able to process a reasonable
   * number of human generated changes quickly, but not spend a long time processing lots
   * of changes after a branch switch which will end up invalidating the entire cache
   * anyway. If overflow is negative calls to postEvents will just generate a single
   * overflow event.
   */
  private final int overflow;

  private final long timeoutMillis;

  /* Legacy interface to help switch to clockId */
  public WatchmanWatcher(
      String watchRoot,
      EventBus fileChangeEventBus,
      ImmutableSet<PathOrGlobMatcher> ignorePaths,
      Watchman watchman,
      UUID queryUUID) {
    this(
        watchRoot,
        fileChangeEventBus,
        ignorePaths,
        watchman,
        new StringBuilder("n:buckd").append(queryUUID).toString());
  }

  public WatchmanWatcher(
      String watchRoot,
      EventBus fileChangeEventBus,
      ImmutableSet<PathOrGlobMatcher> ignorePaths,
      Watchman watchman,
      String sinceCursor) {
    this(
        fileChangeEventBus,
        watchman.getWatchmanClient().get(),
        DEFAULT_OVERFLOW_THRESHOLD,
        DEFAULT_TIMEOUT_MILLIS,
        createQuery(
            watchRoot,
            watchman.getProjectPrefix(),
            ignorePaths,
            watchman.getCapabilities()),
        sinceCursor);
  }

  @VisibleForTesting
  WatchmanWatcher(EventBus fileChangeEventBus,
                  WatchmanClient watchmanClient,
                  int overflow,
                  long timeoutMillis,
                  WatchmanQuery query,
                  String sinceCursor) {
    this.fileChangeEventBus = fileChangeEventBus;
    this.watchmanClient = watchmanClient;
    this.overflow = overflow;
    this.timeoutMillis = timeoutMillis;
    this.query = query;
    this.mSinceCursor = sinceCursor;
  }

  @VisibleForTesting
  static WatchmanQuery createQuery(
      String watchRoot,
      Optional<String> watchPrefix,
      ImmutableSet<PathOrGlobMatcher> ignorePaths,
      Set<Capability> watchmanCapabilities) {

    // Exclude any expressions added to this list.
    List<Object> excludeAnyOf = Lists.newArrayList("anyof");

    // Exclude all directories.
    excludeAnyOf.add(Lists.newArrayList("type", "d"));

    Path projectRoot = Paths.get(watchRoot);
    if (watchPrefix.isPresent()) {
      projectRoot = projectRoot.resolve(watchPrefix.get());
    }

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
            excludeAnyOf.add(
                Lists.newArrayList(
                    "dirname",
                    ignorePath.toString()));
          } else {
            excludeAnyOf.add(
                Lists.newArrayList(
                    "match",
                    ignorePath.toString() + File.separator + "*",
                    "wholename"));
          }
          break;
        case GLOB:
          String ignoreGlob = ignorePathOrGlob.getGlob();
          excludeAnyOf.add(
              Lists.newArrayList(
                  "match",
                  ignoreGlob,
                  "wholename",
                  ImmutableMap.of("includedotfiles", true)));
          break;
        default:
          throw new RuntimeException(
              String.format("Unsupported type: '%s'", ignorePathOrGlob.getType()));
      }
    }

    // Note that we use LinkedHashMap so insertion order is preserved. That
    // helps us write tests that don't depend on the undefined order of HashMap.
    Map<String, Object> sinceParams = new LinkedHashMap<>();
    sinceParams.put(
        "expression",
        Lists.newArrayList(
            "not",
            excludeAnyOf));
    sinceParams.put("empty_on_fresh_instance", true);
    sinceParams.put("fields", Lists.newArrayList("name", "exists", "new"));
    if (watchPrefix.isPresent()) {
      sinceParams.put("relative_root", watchPrefix.get());
    }
    return WatchmanQuery.of(
        watchRoot,
        sinceParams);
  }

  @VisibleForTesting
  ImmutableList<Object> getWatchmanQuery() {
    return query.toList(mSinceCursor);
  }

  /**
   * Query Watchman for file change events. If too many events are pending or an error occurs
   * an overflow event is posted to the EventBus signalling that events may have been lost
   * (and so typically caches must be cleared to avoid inconsistency). Interruptions and
   * IOExceptions are propagated to callers, but typically if overflow events are handled
   * conservatively by subscribers then no other remedial action is required.
   *
   * Any diagnostics posted by Watchman are added to watchmanDiagnosticCache.
   */
  @SuppressWarnings("unchecked")
  public void postEvents(
      BuckEventBus buckEventBus,
      WatchmanDiagnosticCache watchmanDiagnosticCache,
      FreshInstanceAction freshInstanceAction
  ) throws IOException, InterruptedException {
    try {
      Optional<? extends Map<String, ? extends Object>> queryResponse =
          watchmanClient.queryWithTimeout(
              TimeUnit.MILLISECONDS.toNanos(timeoutMillis),
              getWatchmanQuery().toArray());
      if (!queryResponse.isPresent()) {
        LOG.warn(
            "Could not get response from Watchman for query %s %s within %d ms",
            query,
            timeoutMillis);
        postWatchEvent(
            createOverflowEvent("Query to Watchman timed out after " + timeoutMillis + "ms"));
        return;
      }

      Map<String, ? extends Object> response = queryResponse.get();
      String error = (String) response.get("error");
      if (error != null) {
        watchmanDiagnosticCache.addDiagnostic(
            WatchmanDiagnostic.of(WatchmanDiagnostic.Level.ERROR, error));
        WatchmanWatcherException e = new WatchmanWatcherException(error);
        LOG.error(
            e,
            "Error in Watchman output. Posting an overflow event to flush the caches");
        postWatchEvent(createOverflowEvent("Watchman Error occurred - " + e.getMessage()));
        throw e;
      }

      String warning = (String) response.get("warning");
      if (warning != null) {
        switch (watchmanDiagnosticCache.addDiagnostic(
                    WatchmanDiagnostic.of(WatchmanDiagnostic.Level.WARNING, warning))) {
          case NEW_DIAGNOSTIC:
            buckEventBus.post(
                ConsoleEvent.warning("Watchman has produced a warning: %s", warning));
            LOG.warn("Watchman has produced a warning: %s", warning);
            break;
          case DUPLICATE_DIAGNOSTIC:
            LOG.verbose("Watchman has produced a duplicate warning: %s", warning);
            break;
        }
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
            postWatchEvent(createOverflowEvent("New Buck instance"));
            break;
        }
        return;
      }

      List<Map<String, Object>> files = (List<Map<String, Object>>) response.get("files");
      if (files != null) {
        if (files.size() > overflow) {
          String message = "Too many changed files (" + files.size() + " > " + overflow + ")";
          LOG.warn(message + ", posting overflow event");
          postWatchEvent(createOverflowEvent(message));
          return;
        }

        for (Map<String, Object> file : files) {
          String fileName = (String) file.get("name");
          if (fileName == null) {
            LOG.warn("Filename missing from Watchman file response %s", file);
            postWatchEvent(createOverflowEvent("Filename missing from Watchman response"));
            return;
          }
          PathEventBuilder builder = new PathEventBuilder();
          builder.setPath(Paths.get(fileName));
          Boolean fileNew = (Boolean) file.get("new");
          if (fileNew != null && fileNew) {
            builder.setCreationEvent();
          }
          Boolean fileExists = (Boolean) file.get("exists");
          if (fileExists != null && !fileExists) {
            builder.setDeletionEvent();
          }
          postWatchEvent(builder.build());
        }

        LOG.debug("Posted %d Watchman events.", files.size());
      }
    } catch (InterruptedException e) {
      String message = "Watchman communication interrupted";
      LOG.warn(e, message);
      // Events may have been lost, signal overflow.
      postWatchEvent(createOverflowEvent(message));
      Thread.currentThread().interrupt();
      throw e;
    } catch (IOException e) {
      String message = "I/O error talking to Watchman";
      LOG.error(e, message);
      // Events may have been lost, signal overflow.
      postWatchEvent(createOverflowEvent(message + " - " + e.getMessage()));
      throw e;
    }
  }

  private void postWatchEvent(WatchEvent<?> event) {
    LOG.warn("Posting WatchEvent: %s", event);
    fileChangeEventBus.post(event);
  }

  @VisibleForTesting
  public static WatchEvent<Object> createOverflowEvent(final String reason) {
    return new WatchEvent<Object>() {

      @Override
      public Kind<Object> kind() {
        return StandardWatchEventKinds.OVERFLOW;
      }

      @Override
      public int count() {
        return 1;
      }

      @Override
      @Nullable
      public Object context() {
        return reason;
      }

      @Override
      public String toString() {
        return "Watchman Overflow WatchEvent " + kind();
      }
    };
  }

  private static class PathEventBuilder {

    private WatchEvent.Kind<Path> kind;
    @Nullable private Path path;

    PathEventBuilder() {
      this.kind = StandardWatchEventKinds.ENTRY_MODIFY;
    }

    public void setCreationEvent() {
      if (kind != StandardWatchEventKinds.ENTRY_DELETE) {
        kind = StandardWatchEventKinds.ENTRY_CREATE;
      }
    }

    public void setDeletionEvent() {
      kind = StandardWatchEventKinds.ENTRY_DELETE;
    }

    public void setPath(Path path) {
      this.path = path;
    }

    public WatchEvent<Path> build() {
      Preconditions.checkNotNull(path);
      return new WatchEvent<Path>() {
        @Override
        public Kind<Path> kind() {
          return kind;
        }

        @Override
        public int count() {
          return 1;
        }

        @Override
        @Nullable
        public Path context() {
          return path;
        }

        @Override
        public String toString() {
          return "Watchman Path WatchEvent " + kind + " " + path;
        }
      };
    }
  }
}
