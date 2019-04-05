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

package com.facebook.buck.support.state;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.files.DirectoryListCache;
import com.facebook.buck.core.files.FileTreeCache;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphCache;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.FileHashCacheEvent;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanCursor;
import com.facebook.buck.io.watchman.WatchmanWatcher;
import com.facebook.buck.parser.DaemonicParserState;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.impl.WatchedFileHashCache;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.versions.VersionedTargetGraphCache;
import com.facebook.buck.worker.WorkerProcessPool;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.EventBus;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link BuckGlobalState} contains all the global state of Buck which is kept between invocations
 * of Buck commands, like caches, global per-process objects, etc. Individual caches here can be
 * hooked to {@link Watchman} for proper invalidation.
 *
 * <p>This state is uniformly invalidated on major changes, like configuration changes, so it is
 * essential all in-proc caches to contain in this class for proper invalidation. If caches need to
 * be kept between configuration changes, a custom logic should reside in {@link
 * BuckGlobalStateLifecycleManager}
 *
 * <p>All Graph Engine caches are required to be kept here.
 */
public final class BuckGlobalState implements Closeable {
  private static final Logger LOG = Logger.get(BuckGlobalState.class);

  private final Cell rootCell;
  private final TypeCoercerFactory typeCoercerFactory;
  private final DaemonicParserState daemonicParserState;
  private final ImmutableList<ProjectFileHashCache> hashCaches;
  private final LoadingCache<Path, DirectoryListCache> directoryListCachePerRoot;
  private final LoadingCache<Path, FileTreeCache> fileTreeCachePerRoot;
  private final EventBus fileEventBus;
  private final Optional<WebServer> webServer;
  private final ConcurrentMap<String, WorkerProcessPool> persistentWorkerPools;
  private final VersionedTargetGraphCache versionedTargetGraphCache;
  private final ActionGraphCache actionGraphCache;
  private final RuleKeyCacheRecycler<RuleKey> defaultRuleKeyFactoryCacheRecycler;
  private final ImmutableMap<Path, WatchmanCursor> cursor;
  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final Clock clock;
  private final long startTime;
  private final boolean usesWatchman;

  private final BackgroundTaskManager bgTaskManager;

  BuckGlobalState(
      Cell rootCell,
      TypeCoercerFactory typeCoercerFactory,
      DaemonicParserState daemonicParserState,
      ImmutableList<ProjectFileHashCache> hashCaches,
      LoadingCache<Path, DirectoryListCache> directoryListCachePerRoot,
      LoadingCache<Path, FileTreeCache> fileTreeCachePerRoot,
      EventBus fileEventBus,
      Optional<WebServer> webServer,
      ConcurrentMap<String, WorkerProcessPool> persistentWorkerPools,
      VersionedTargetGraphCache versionedTargetGraphCache,
      ActionGraphCache actionGraphCache,
      RuleKeyCacheRecycler<RuleKey> defaultRuleKeyFactoryCacheRecycler,
      ImmutableMap<Path, WatchmanCursor> cursor,
      KnownRuleTypesProvider knownRuleTypesProvider,
      Clock clock,
      BackgroundTaskManager bgTaskManager,
      boolean usesWatchman) {
    this.rootCell = rootCell;
    this.typeCoercerFactory = typeCoercerFactory;
    this.daemonicParserState = daemonicParserState;
    this.hashCaches = hashCaches;
    this.directoryListCachePerRoot = directoryListCachePerRoot;
    this.fileTreeCachePerRoot = fileTreeCachePerRoot;
    this.fileEventBus = fileEventBus;
    this.webServer = webServer;
    this.persistentWorkerPools = persistentWorkerPools;
    this.versionedTargetGraphCache = versionedTargetGraphCache;
    this.actionGraphCache = actionGraphCache;
    this.defaultRuleKeyFactoryCacheRecycler = defaultRuleKeyFactoryCacheRecycler;
    this.cursor = cursor;
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.clock = clock;
    this.bgTaskManager = bgTaskManager;
    this.usesWatchman = usesWatchman;

    this.startTime = clock.currentTimeMillis();
  }

  Cell getRootCell() {
    return rootCell;
  }

  public BackgroundTaskManager getBgTaskManager() {
    return bgTaskManager;
  }

  public Optional<WebServer> getWebServer() {
    return webServer;
  }

  public TypeCoercerFactory getTypeCoercerFactory() {
    return typeCoercerFactory;
  }

  public VersionedTargetGraphCache getVersionedTargetGraphCache() {
    return versionedTargetGraphCache;
  }

  public ActionGraphCache getActionGraphCache() {
    return actionGraphCache;
  }

  public ImmutableList<ProjectFileHashCache> getFileHashCaches() {
    return hashCaches;
  }

  /**
   * Return a map of all directory list caches for each cell which is a key. For every cell, we
   * cache directory structure (i.e. list of files and folders) for all subfolders that exist under
   * that cell root folder
   */
  public LoadingCache<Path, DirectoryListCache> getDirectoryListCaches() {
    return directoryListCachePerRoot;
  }

  /**
   * Return a map of file tree caches for each cell which is a key. For every cell and each
   * subfolder under cell root path, we cache the whole subtree of folders and files recursively
   */
  public LoadingCache<Path, FileTreeCache> getFileTreeCaches() {
    return fileTreeCachePerRoot;
  }

  public KnownRuleTypesProvider getKnownRuleTypesProvider() {
    return knownRuleTypesProvider;
  }

  public ConcurrentMap<String, WorkerProcessPool> getPersistentWorkerPools() {
    return persistentWorkerPools;
  }

  public RuleKeyCacheRecycler<RuleKey> getDefaultRuleKeyFactoryCacheRecycler() {
    return defaultRuleKeyFactoryCacheRecycler;
  }

  public DaemonicParserState getDaemonicParserState() {
    return daemonicParserState;
  }

  public void interruptOnClientExit(Thread threadToInterrupt) {
    // Synchronize on parser object so that the main command processing thread is not
    // interrupted mid way through a Parser cache update by the Thread.interrupt() call
    // triggered by System.exit(). The Parser cache will be reused by subsequent commands
    // so needs to be left in a consistent state even if the current command is interrupted
    // due to a client disconnection.
    synchronized (daemonicParserState) {
      // signal to the main thread that we want to exit
      threadToInterrupt.interrupt();
    }
  }

  public void watchFileSystem(
      BuckEventBus eventBus,
      WatchmanWatcher watchmanWatcher,
      WatchmanWatcher.FreshInstanceAction watchmanFreshInstanceAction)
      throws IOException, InterruptedException {

    // Synchronize on parser object so that all outstanding watch events are processed
    // as a single, atomic Parser cache update and are not interleaved with Parser cache
    // invalidations triggered by requests to parse build files or interrupted by client
    // disconnections.
    synchronized (daemonicParserState) {
      // Track the file hash cache invalidation run time.
      FileHashCacheEvent.InvalidationStarted started = FileHashCacheEvent.invalidationStarted();
      eventBus.post(started);
      try {
        watchmanWatcher.postEvents(eventBus, watchmanFreshInstanceAction);
      } finally {
        eventBus.post(FileHashCacheEvent.invalidationFinished(started));
        for (ProjectFileHashCache hashCache : hashCaches) {
          if (hashCache instanceof WatchedFileHashCache) {
            WatchedFileHashCache cache = (WatchedFileHashCache) hashCache;
            cache.getStatsEvents().forEach(eventBus::post);
          }
        }
      }
    }
  }

  /** Global event bus used to process file invalidation events. This event bus is synchronous. */
  public EventBus getFileEventBus() {
    return fileEventBus;
  }

  public ImmutableMap<Path, WatchmanCursor> getWatchmanCursor() {
    return cursor;
  }

  /** @return true if state was created with Watchman service initialized */
  public boolean getUsesWatchman() {
    return usesWatchman;
  }

  @Override
  public void close() {
    bgTaskManager.shutdownNow();
    shutdownPersistentWorkerPools();
    shutdownWebServer();
  }

  private void shutdownPersistentWorkerPools() {
    for (WorkerProcessPool pool : persistentWorkerPools.values()) {
      try {
        pool.close();
      } catch (Exception e) {
        LOG.error(e);
      }
    }
  }

  private void shutdownWebServer() {
    if (webServer.isPresent()) {
      try {
        webServer.get().stop();
      } catch (WebServer.WebServerException e) {
        LOG.error(e);
      }
    }
  }

  /** @return the length of time in millis since this daemon was started */
  public long getUptime() {
    return clock.currentTimeMillis() - startTime;
  }
}
