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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.targetgraph.impl.Package;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.parser.api.PackageFileManifest;
import com.facebook.buck.parser.api.PackageMetadata;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Converts {@link PackageMetadata}s in a {@link PackageFileManifest} into {@link Package}s and
 * caches them for reuse.
 */
class PackagePipeline implements AutoCloseable {
  private static final String PACKAGE_FILE_NAME = "PACKAGE";
  private static final Logger LOG = Logger.get(UnconfiguredTargetNodePipeline.class);

  private final ListeningExecutorService executorService;
  private final BuckEventBus eventBus;
  private final PackageFileParsePipeline packageFileParsePipeline;

  private final SimplePerfEvent.Scope perfEventScope;
  private final SimplePerfEvent.PerfEventId perfEventId;
  /**
   * minimum duration time for performance events to be logged (for use with {@link
   * SimplePerfEvent}s). This is on the base class to make it simpler to enable verbose tracing for
   * all of the parsing pipelines.
   */
  private final long minimumPerfEventTimeMs;

  private final PipelineNodeCache<AbsPath, Package> cache;

  private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

  PackagePipeline(
      ListeningExecutorService executorService,
      BuckEventBus eventBus,
      PackageFileParsePipeline packageFileParsePipeline,
      PerBuildStateCache.PackageCache packageCache) {
    this.executorService = executorService;
    this.eventBus = eventBus;
    this.packageFileParsePipeline = packageFileParsePipeline;

    this.minimumPerfEventTimeMs = LOG.isVerboseEnabled() ? 0 : 10;
    this.perfEventId = SimplePerfEvent.PerfEventId.of("GetPackage");
    this.perfEventScope =
        SimplePerfEvent.scope(eventBus, SimplePerfEvent.PerfEventId.of("package_pipeline"));

    this.cache = new PipelineNodeCache<>(packageCache, n -> false);
  }

  /**
   * Given a build file at a particular path, returns the path of the package file in the same
   * directory.
   */
  static AbsPath getPackageFileFromBuildFile(Cell cell, AbsPath buildFile) {
    Preconditions.checkArgument(
        buildFile.endsWith(cell.getBuckConfigView(ParserConfig.class).getBuildFileName()),
        "Invalid build file: %s",
        buildFile);
    AbsPath parent =
        Preconditions.checkNotNull(
            buildFile.getParent(), "The build file path must have a parent: %s", buildFile);
    return parent.resolve(PACKAGE_FILE_NAME);
  }

  static boolean isPackageFile(Path path) {
    return path.getFileName().toString().equals(PACKAGE_FILE_NAME) && Files.isRegularFile(path);
  }

  /**
   * @return the path of the parent package file for the given {@param packageFile} package file, if
   *     it exists, else an empty optional.
   */
  static Optional<AbsPath> getParentPackageFile(Cell cell, AbsPath packageFile) {
    AbsPath currentDir =
        Preconditions.checkNotNull(
            packageFile.getParent(), "The package file path must have a parent: %s", packageFile);
    AbsPath cellRoot = cell.getRoot();
    if (currentDir.equals(cellRoot)) {
      return Optional.empty();
    }
    AbsPath parentPackageFile = currentDir.getParent().resolve(PACKAGE_FILE_NAME);
    return Optional.of(parentPackageFile);
  }

  /**
   * @return all the parent package files for the given {@param packageFile}, all the way to the
   *     root of the passed {@param cell}.
   */
  static ImmutableSet<AbsPath> getAllParentPackageFiles(Cell cell, AbsPath packageFile) {
    ImmutableSet.Builder<AbsPath> paths = ImmutableSet.builder();
    Optional<AbsPath> parentPackageFile = getParentPackageFile(cell, packageFile);
    while (parentPackageFile.isPresent()) {
      paths.add(parentPackageFile.get());
      parentPackageFile = getParentPackageFile(cell, parentPackageFile.get());
    }
    return paths.build();
  }

  /**
   * @return a future (potentially immediate) for the {@link Package} in the package file
   *     corresponding to the {@param buildFile} provided.
   */
  public ListenableFuture<Package> getPackageJob(Cell cell, AbsPath buildFile) {
    AbsPath packageFile = getPackageFileFromBuildFile(cell, buildFile);

    if (!cell.getBuckConfig().getView(ParserConfig.class).getEnablePackageFiles()) {
      Package pkg =
          PackageFactory.create(
              cell, packageFile.getPath(), PackageMetadata.EMPTY_SINGLETON, Optional.empty());
      return Futures.immediateFuture(pkg);
    }

    return getPackageJobInternal(cell, packageFile);
  }

  private ListenableFuture<Package> getPackageJobInternal(Cell cell, AbsPath packageFile)
      throws BuildTargetException {
    if (shuttingDown()) {
      return Futures.immediateCancelledFuture();
    }

    return cache.getJobWithCacheLookup(
        cell,
        packageFile,
        () ->
            Futures.transformAsync(
                getParentPackageJob(cell, packageFile),
                parentPkg ->
                    Futures.transformAsync(
                        getPackageFileManifest(cell, packageFile),
                        packageFileManifest ->
                            computePackage(
                                cell, packageFile, packageFileManifest.getPackage(), parentPkg),
                        executorService),
                executorService),
        eventBus);
  }

  /**
   * @return a future for the {@link PackageFileManifest} at the provided {@param packageFile} if
   *     the file exists on disk, or an immediate future with a default manifest.
   */
  private ListenableFuture<PackageFileManifest> getPackageFileManifest(
      Cell cell, AbsPath packageFile) {
    if (cell.getFilesystem().isFile(packageFile)) {
      return packageFileParsePipeline.getFileJob(cell, packageFile);
    }
    return Futures.immediateFuture(PackageFileManifest.EMPTY_SINGLETON);
  }

  private ListenableFuture<Optional<Package>> getParentPackageJob(
      Cell cell, AbsPath childBuildFile) {
    if (shuttingDown()) {
      return Futures.immediateCancelledFuture();
    }

    Optional<AbsPath> parentBuildFile = getParentPackageFile(cell, childBuildFile);
    if (!parentBuildFile.isPresent()) {
      // childBuildFile is at the cell root and we have no more parents
      return Futures.immediateFuture(Optional.empty());
    }

    if (cell.getFilesystem().isFile(parentBuildFile.get())) {
      // We have a build file and should cache this package node
      return Futures.transformAsync(
          getPackageJobInternal(cell, parentBuildFile.get()),
          cachedPackageNode -> Futures.immediateFuture(Optional.of(cachedPackageNode)),
          executorService);
    } else {
      return getParentPackageJob(cell, parentBuildFile.get());
    }
  }

  private ListenableFuture<Package> computePackage(
      Cell cell, AbsPath packageFile, PackageMetadata pkg, Optional<Package> parentPkg)
      throws BuildTargetException {
    if (shuttingDown()) {
      return Futures.immediateCancelledFuture();
    }
    Package result;

    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scopeIgnoringShortEvents(
            eventBus,
            perfEventId,
            "packageFile",
            packageFile,
            perfEventScope,
            minimumPerfEventTimeMs,
            TimeUnit.MILLISECONDS)) {

      result = PackageFactory.create(cell, packageFile.getPath(), pkg, parentPkg);
    }
    return Futures.immediateFuture(result);
  }

  private boolean shuttingDown() {
    return shuttingDown.get();
  }

  @Override
  public void close() {
    perfEventScope.close();
    shuttingDown.set(true);

    // At this point external callers should not schedule more work, internally job creation
    // should also stop. Any scheduled futures should eventually cancel themselves (all of the
    // AsyncFunctions that interact with the Cache are wired to early-out if `shuttingDown` is
    // true).
    // We could block here waiting for all ongoing work to complete, however the user has already
    // gotten everything they want out of the pipeline, so the only interesting thing that could
    // happen here are exceptions thrown by the ProjectBuildFileParser as its shutting down. These
    // aren't critical enough to warrant bringing down the entire process, as they don't affect the
    // state that has already been extracted from the parser.
  }
}
