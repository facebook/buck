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

package com.facebook.buck.cli;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.ArtifactCaches;
import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.files.DirectoryListCache;
import com.facebook.buck.core.files.FileTreeCache;
import com.facebook.buck.core.model.TargetConfigurationSerializer;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphCache;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.FileHashCacheEvent;
import com.facebook.buck.event.listener.devspeed.DevspeedBuildListenerFactory;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanCursor;
import com.facebook.buck.io.watchman.WatchmanWatcher;
import com.facebook.buck.parser.DaemonicParserState;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyCache;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.support.bgtasks.AsyncBackgroundTaskManager;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.WatchedFileHashCache;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.versions.VersionedTargetGraphCache;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.nailgun.NGContext;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.EventBus;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * {@link BuckGlobalState} contains all the global state of Buck which is kept between invocations
 * of Buck commands, like caches, global per-process objects, etc. Individual caches here can be
 * hooked to {@link Watchman} for proper invalidation.
 *
 * <p>This state is uniformly invalidated on major changes, like configuration changes, so it is
 * essential all in-proc caches to contain in this class for proper invalidation. If caches need to
 * be kept between configuration changes, a custom logic should reside in {@link
 * DaemonLifecycleManager}
 *
 * <p>All Graph Engine caches are required to be kept here.
 */
final class BuckGlobalState implements Closeable {
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
  private final UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory;
  private final TargetConfigurationSerializer targetConfigurationSerializer;
  private final Clock clock;
  private final long startTime;
  private final Optional<DevspeedBuildListenerFactory> devspeedBuildListenerFactory;

  private final BackgroundTaskManager bgTaskManager;

  BuckGlobalState(
      Cell rootCell,
      KnownRuleTypesProvider knownRuleTypesProvider,
      Watchman watchman,
      Optional<WebServer> webServerToReuse,
      UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer,
      Clock clock,
      Supplier<Optional<DevspeedBuildListenerFactory>> devspeedBuildListenerFactorySupplier,
      Optional<NGContext> context) {
    this.rootCell = rootCell;
    this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
    this.targetConfigurationSerializer = targetConfigurationSerializer;
    this.fileEventBus = new EventBus("file-change-events");

    ImmutableList<Cell> allCells = rootCell.getAllCells();
    BuildBuckConfig buildBuckConfig = rootCell.getBuckConfig().getView(BuildBuckConfig.class);

    // Setup the stacked file hash cache from all cells.
    ImmutableList.Builder<ProjectFileHashCache> hashCachesBuilder =
        ImmutableList.builderWithExpectedSize(allCells.size() + 1);
    for (Cell subCell : allCells) {
      WatchedFileHashCache watchedCache =
          new WatchedFileHashCache(subCell.getFilesystem(), buildBuckConfig.getFileHashCacheMode());
      fileEventBus.register(watchedCache);
      hashCachesBuilder.add(watchedCache);
    }
    hashCachesBuilder.add(
        DefaultFileHashCache.createBuckOutFileHashCache(
            rootCell.getFilesystem(), buildBuckConfig.getFileHashCacheMode()));
    this.hashCaches = hashCachesBuilder.build();

    // Setup file list cache and file tree cache from all cells
    directoryListCachePerRoot = createDirectoryListCachePerCellMap(fileEventBus);
    fileTreeCachePerRoot = createFileTreeCachePerCellMap(fileEventBus);
    this.actionGraphCache = new ActionGraphCache(buildBuckConfig.getMaxActionGraphCacheEntries());
    this.versionedTargetGraphCache = new VersionedTargetGraphCache();
    this.knownRuleTypesProvider = knownRuleTypesProvider;

    typeCoercerFactory = new DefaultTypeCoercerFactory();
    ParserConfig parserConfig = rootCell.getBuckConfig().getView(ParserConfig.class);
    this.daemonicParserState = new DaemonicParserState(parserConfig.getNumParsingThreads());

    // Build the the rule key cache recycler.
    this.defaultRuleKeyFactoryCacheRecycler =
        RuleKeyCacheRecycler.createAndRegister(
            fileEventBus,
            new DefaultRuleKeyCache<>(),
            RichStream.from(allCells).map(Cell::getFilesystem).toImmutableSet());

    if (webServerToReuse.isPresent()) {
      webServer = webServerToReuse;
    } else {
      webServer = createWebServer(rootCell.getBuckConfig(), rootCell.getFilesystem());
    }
    if (!initWebServer()) {
      LOG.warn("Can't start web server");
    }
    if (rootCell.getBuckConfig().getView(ParserConfig.class).getWatchmanCursor()
            == WatchmanWatcher.CursorType.CLOCK_ID
        && !watchman.getClockIds().isEmpty()) {
      cursor = watchman.buildClockWatchmanCursorMap();
    } else {
      LOG.debug("Falling back to named cursors: %s", watchman.getProjectWatches());
      cursor = watchman.buildNamedWatchmanCursorMap();
    }
    LOG.debug("Using Watchman Cursor: %s", cursor);
    persistentWorkerPools = new ConcurrentHashMap<>();

    // When Nailgun context is not present it means the process will be finished immediately after
    // the command. So, override task manager to be blocking one, i.e. execute background
    // clean up tasks synchronously
    this.bgTaskManager =
        new AsyncBackgroundTaskManager(
            !context.isPresent()
                || rootCell.getBuckConfig().getView(CliConfig.class).getFlushEventsBeforeExit());
    this.clock = clock;
    this.startTime = clock.currentTimeMillis();

    // Create this last so that it won't leak if something else throws in the constructor
    this.devspeedBuildListenerFactory = devspeedBuildListenerFactorySupplier.get();
  }

  /** Create a number of instances of {@link DirectoryListCache}, one per each cell */
  private static LoadingCache<Path, DirectoryListCache> createDirectoryListCachePerCellMap(
      EventBus fileEventBus) {
    return CacheBuilder.newBuilder()
        .build(
            new CacheLoader<Path, DirectoryListCache>() {
              @Override
              public DirectoryListCache load(Path path) {
                DirectoryListCache cache = DirectoryListCache.of(path);
                fileEventBus.register(cache.getInvalidator());
                return cache;
              }
            });
  }

  /** Create a number of instances of {@link DirectoryListCache}, one per each cell */
  private static LoadingCache<Path, FileTreeCache> createFileTreeCachePerCellMap(
      EventBus fileEventBus) {
    return CacheBuilder.newBuilder()
        .build(
            new CacheLoader<Path, FileTreeCache>() {
              @Override
              public FileTreeCache load(Path path) {
                FileTreeCache cache = FileTreeCache.of(path);
                fileEventBus.register(cache.getInvalidator());
                return cache;
              }
            });
  }

  Cell getRootCell() {
    return rootCell;
  }

  Optional<BuckEventListener> getDevspeedDaemonListener() {
    return devspeedBuildListenerFactory.map(DevspeedBuildListenerFactory::newBuildListener);
  }

  private static Optional<WebServer> createWebServer(
      BuckConfig config, ProjectFilesystem filesystem) {
    OptionalInt port = getValidWebServerPort(config);
    if (!port.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(new WebServer(port.getAsInt(), filesystem));
  }

  /**
   * If the return value is not absent, then the port is a nonnegative integer. This means that
   * specifying a port of -1 effectively disables the WebServer.
   */
  static OptionalInt getValidWebServerPort(BuckConfig config) {
    // Enable the web httpserver if it is given by command line parameter or specified in
    // .buckconfig. The presence of a nonnegative port number is sufficient.
    Optional<String> serverPort = Optional.ofNullable(System.getProperty("buck.httpserver.port"));
    if (!serverPort.isPresent()) {
      serverPort = config.getValue("httpserver", "port");
    }

    if (!serverPort.isPresent() || serverPort.get().isEmpty()) {
      return OptionalInt.empty();
    }

    String rawPort = serverPort.get();
    int port;
    try {
      port = Integer.parseInt(rawPort, 10);
    } catch (NumberFormatException e) {
      LOG.error("Could not parse port for httpserver: %s.", rawPort);
      return OptionalInt.empty();
    }

    return port >= 0 ? OptionalInt.of(port) : OptionalInt.empty();
  }

  BackgroundTaskManager getBgTaskManager() {
    return bgTaskManager;
  }

  Optional<WebServer> getWebServer() {
    return webServer;
  }

  TypeCoercerFactory getTypeCoercerFactory() {
    return typeCoercerFactory;
  }

  VersionedTargetGraphCache getVersionedTargetGraphCache() {
    return versionedTargetGraphCache;
  }

  ActionGraphCache getActionGraphCache() {
    return actionGraphCache;
  }

  ImmutableList<ProjectFileHashCache> getFileHashCaches() {
    return hashCaches;
  }

  /**
   * Return a map of all directory list caches for each cell which is a key. For every cell, we
   * cache directory structure (i.e. list of files and folders) for all subfolders that exist under
   * that cell root folder
   */
  LoadingCache<Path, DirectoryListCache> getDirectoryListCaches() {
    return directoryListCachePerRoot;
  }

  /**
   * Return a map of file tree caches for each cell which is a key. For every cell and each
   * subfolder under cell root path, we cache the whole subtree of folders and files recursively
   */
  LoadingCache<Path, FileTreeCache> getFileTreeCaches() {
    return fileTreeCachePerRoot;
  }

  public KnownRuleTypesProvider getKnownRuleTypesProvider() {
    return knownRuleTypesProvider;
  }

  ConcurrentMap<String, WorkerProcessPool> getPersistentWorkerPools() {
    return persistentWorkerPools;
  }

  RuleKeyCacheRecycler<RuleKey> getDefaultRuleKeyFactoryCacheRecycler() {
    return defaultRuleKeyFactoryCacheRecycler;
  }

  DaemonicParserState getDaemonicParserState() {
    return daemonicParserState;
  }

  void interruptOnClientExit(Thread threadToInterrupt) {
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

  void watchFileSystem(
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

  /** @return true if the web server was started successfully. */
  private boolean initWebServer() {
    if (webServer.isPresent()) {
      Optional<ArtifactCache> servedCache =
          ArtifactCaches.newServedCache(
              new ArtifactCacheBuckConfig(rootCell.getBuckConfig()),
              target ->
                  unconfiguredBuildTargetFactory.create(rootCell.getCellPathResolver(), target),
              targetConfigurationSerializer,
              rootCell.getFilesystem());
      try {
        webServer.get().updateAndStartIfNeeded(servedCache);
        return true;
      } catch (WebServer.WebServerException e) {
        LOG.error(e);
      }
    }
    return false;
  }

  public EventBus getFileEventBus() {
    return fileEventBus;
  }

  public ImmutableMap<Path, WatchmanCursor> getWatchmanCursor() {
    return cursor;
  }

  @Override
  public void close() {
    bgTaskManager.shutdownNow();
    shutdownPersistentWorkerPools();
    shutdownWebServer();
    devspeedBuildListenerFactory.ifPresent(DevspeedBuildListenerFactory::close);
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
