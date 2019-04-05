/*
 * Copyright 2019-present Facebook, Inc.
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
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanCursor;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.io.watchman.WatchmanWatcher;
import com.facebook.buck.parser.DaemonicParserState;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyCache;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.WatchedFileHashCache;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.versions.VersionedTargetGraphCache;
import com.facebook.buck.worker.WorkerProcessPool;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.EventBus;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** Factory for {@link BuckGlobalState}. */
public class BuckGlobalStateFactory {

  private static final Logger LOG = Logger.get(BuckGlobalStateFactory.class);

  /** @return a new instance of {@link BuckGlobalState} for execution of buck */
  public static BuckGlobalState create(
      Cell rootCell,
      KnownRuleTypesProvider knownRuleTypesProvider,
      Watchman watchman,
      Optional<WebServer> webServerToReuse,
      UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer,
      Clock clock,
      Supplier<BackgroundTaskManager> bgTaskManagerFactory) {
    EventBus fileEventBus = new EventBus("file-change-events");

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
    ImmutableList<ProjectFileHashCache> hashCaches = hashCachesBuilder.build();

    // Setup file list cache and file tree cache from all cells
    LoadingCache<Path, DirectoryListCache> directoryListCachePerRoot =
        createDirectoryListCachePerCellMap(fileEventBus);
    LoadingCache<Path, FileTreeCache> fileTreeCachePerRoot =
        createFileTreeCachePerCellMap(fileEventBus);
    ActionGraphCache actionGraphCache =
        new ActionGraphCache(buildBuckConfig.getMaxActionGraphCacheEntries());
    VersionedTargetGraphCache versionedTargetGraphCache = new VersionedTargetGraphCache();

    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    ParserConfig parserConfig = rootCell.getBuckConfig().getView(ParserConfig.class);
    DaemonicParserState daemonicParserState =
        new DaemonicParserState(parserConfig.getNumParsingThreads());
    fileEventBus.register(daemonicParserState);

    // Build the the rule key cache recycler.
    RuleKeyCacheRecycler<RuleKey> defaultRuleKeyFactoryCacheRecycler =
        RuleKeyCacheRecycler.createAndRegister(
            fileEventBus,
            new DefaultRuleKeyCache<>(),
            RichStream.from(allCells).map(Cell::getFilesystem).toImmutableSet());

    Optional<WebServer> webServer;
    if (webServerToReuse.isPresent()) {
      webServer = webServerToReuse;
    } else {
      webServer = createWebServer(rootCell.getBuckConfig(), rootCell.getFilesystem());
    }
    if (webServer.isPresent()) {
      Optional<ArtifactCache> servedCache =
          ArtifactCaches.newServedCache(
              new ArtifactCacheBuckConfig(rootCell.getBuckConfig()),
              target ->
                  unconfiguredBuildTargetFactory.create(rootCell.getCellPathResolver(), target),
              targetConfigurationSerializer,
              rootCell.getFilesystem());
      if (!initWebServer(webServer, servedCache)) {
        LOG.warn("Can't start web server");
      }
    }
    ImmutableMap<Path, WatchmanCursor> cursor;
    if (rootCell.getBuckConfig().getView(ParserConfig.class).getWatchmanCursor()
            == WatchmanWatcher.CursorType.CLOCK_ID
        && !watchman.getClockIds().isEmpty()) {
      cursor = watchman.buildClockWatchmanCursorMap();
    } else {
      LOG.debug("Falling back to named cursors: %s", watchman.getProjectWatches());
      cursor = watchman.buildNamedWatchmanCursorMap();
    }
    LOG.debug("Using Watchman Cursor: %s", cursor);
    ConcurrentMap<String, WorkerProcessPool> persistentWorkerPools = new ConcurrentHashMap<>();

    return new BuckGlobalState(
        rootCell,
        typeCoercerFactory,
        daemonicParserState,
        hashCaches,
        directoryListCachePerRoot,
        fileTreeCachePerRoot,
        fileEventBus,
        webServer,
        persistentWorkerPools,
        versionedTargetGraphCache,
        actionGraphCache,
        defaultRuleKeyFactoryCacheRecycler,
        cursor,
        knownRuleTypesProvider,
        clock,
        bgTaskManagerFactory.get(),
        watchman != WatchmanFactory.NULL_WATCHMAN);
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

  /** @return true if the web server was started successfully. */
  private static boolean initWebServer(
      Optional<WebServer> webServer, Optional<ArtifactCache> servedCache) {
    if (webServer.isPresent()) {
      try {
        webServer.get().updateAndStartIfNeeded(servedCache);
        return true;
      } catch (WebServer.WebServerException e) {
        LOG.error(e);
      }
    }
    return false;
  }
}
