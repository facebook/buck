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

package com.facebook.buck.distributed;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.PathWithUnixSeparators;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.cache.FileHashCacheVerificationResult;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Note that this class may or may not return the real HashCode of a file/dir. The primary purpose
 * of this class is to trigger async materialization of files in a distributed build.
 */
class MaterializerDummyFileHashCache implements ProjectFileHashCache {

  private static final Logger LOG = Logger.get(MaterializerDummyFileHashCache.class);
  private static final long DEFAULT_PRELOAD_FILE_MATERIALIZATION_TIMEOUT_SECONDS = 300;

  private final ImmutableMap<Path, BuildJobStateFileHashEntry> remoteFileHashesByAbsPath;
  private final Set<Path> materializedPaths;
  private final FileContentsProvider provider;
  private final ProjectFilesystem projectFilesystem;
  private final ProjectFileHashCache delegate;
  private final Map<BuildJobStateFileHashEntry, ListenableFuture<?>>
      fileMaterializationFuturesByFileHashEntry;
  private final ListeningExecutorService executorService;

  public MaterializerDummyFileHashCache(
      ProjectFileHashCache delegate,
      BuildJobStateFileHashes remoteFileHashes,
      FileContentsProvider provider,
      ListeningExecutorService executorService) {
    this.delegate = delegate;
    this.remoteFileHashesByAbsPath =
        DistBuildFileHashes.indexEntriesByPath(delegate.getFilesystem(), remoteFileHashes);
    this.materializedPaths = Collections.newSetFromMap(new ConcurrentHashMap<Path, Boolean>());
    this.provider = provider;
    this.projectFilesystem = delegate.getFilesystem();
    this.fileMaterializationFuturesByFileHashEntry = new ConcurrentHashMap<>();
    this.executorService = executorService;
  }

  /**
   * This method creates all symlinks and touches all regular files so that any file existence
   * checks during action graph transformation go through (for instance,
   * PrebuiltCxxLibraryDescription::requireSharedLibrary). Note: THIS IS A HACK. And this needs to
   * be here until the misbehaving rules are fixed. TODO(alisdair): remove this once action graph
   * doesn't read from file system.
   */
  public void preloadAllFiles(boolean materializeAllFiles) throws IOException {
    for (Path absPath : remoteFileHashesByAbsPath.keySet()) {
      LOG.info("Preloading: [%s]", absPath.toString());
      BuildJobStateFileHashEntry fileHashEntry = remoteFileHashesByAbsPath.get(absPath);
      if (fileHashEntry == null || fileHashEntry.isPathIsAbsolute()) {
        continue;
      }

      if (fileHashEntry.isSetRootSymLink()) {
        Path rootSymlinkAbsPath =
            projectFilesystem.resolve(fileHashEntry.getRootSymLink().getPath());
        Optional<Path> rootSymlinkRelPath =
            projectFilesystem.getPathRelativeToProjectRoot(rootSymlinkAbsPath);
        if (!rootSymlinkRelPath.isPresent()) {
          // RecordingProjectFileHashCache stored an absolute path (which was also a sym link).
          throw new RuntimeException("Root symlink is not in project root: " + absPath);
        }

        materializeSymlink(
            projectFilesystem.getPathRelativeToProjectRoot(absPath).get(), fileHashEntry);
        continue;
      }

      if (fileHashEntry.isDirectory) {
        // Create directory
        // No need to materialize sub-dirs/files here, as there will be separate entries for those.
        projectFilesystem.mkdirs(absPath);
        continue;
      }

      if (materializeAllFiles
          || fileHashEntry.isSetMaterializeDuringPreloading()
              && fileHashEntry.isMaterializeDuringPreloading()) {
        Path relPath = projectFilesystem.getPathRelativeToProjectRoot(absPath).get();
        materializeFileAsync(relPath, fileHashEntry);
        continue;
      }

      // Touch file
      projectFilesystem.createParentDirs(absPath);
      projectFilesystem.touch(absPath);
    }

    try {
      getMaterializationFuturesAsList()
          .get(DEFAULT_PRELOAD_FILE_MATERIALIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      String msg =
          String.format(
              "Preload file materialization failed with exception: [%s].", e.getMessage());
      LOG.error(e, msg);
      throw new RuntimeException(msg);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      String msg = "Preload file materialization was interrupted.";
      LOG.error(e, msg);
      throw new RuntimeException(msg);
    } catch (TimeoutException e) {
      printMissingFiles();
      String msg =
          String.format(
              "Preload materialization timed out after [%d] seconds.",
              DEFAULT_PRELOAD_FILE_MATERIALIZATION_TIMEOUT_SECONDS);
      LOG.error(e, msg);
      throw new RuntimeException(msg);
    }
  }

  private void printMissingFiles() {
    for (BuildJobStateFileHashEntry fileEntry :
        fileMaterializationFuturesByFileHashEntry.keySet()) {
      ListenableFuture<?> materializationFuture =
          Preconditions.checkNotNull(fileMaterializationFuturesByFileHashEntry.get(fileEntry));
      if (!materializationFuture.isDone()) {
        LOG.warn(
            String.format(
                "Materialization missing for: [%s] [sha1: %s]",
                fileEntry.getPath().getPath(), fileEntry.getSha1()));
      }
    }
  }

  private void materializeIfNeededAsync(Path relPath) throws IOException {
    Stack<Path> remainingPaths = new Stack<>();
    remainingPaths.add(relPath);

    while (!remainingPaths.isEmpty()) {
      relPath = remainingPaths.pop();
      if (materializedPaths.contains(relPath)) {
        LOG.verbose("Path already materialized: [%s].", relPath.toString());
        continue;
      }

      Path absPath = projectFilesystem.resolve(relPath).toAbsolutePath();
      BuildJobStateFileHashEntry fileHashEntry = remoteFileHashesByAbsPath.get(absPath);

      if (fileHashEntry == null || fileHashEntry.isPathIsAbsolute()) {
        LOG.info(
            "Insufficient information or no need for materializing path: [%s].",
            relPath.toString());
        recordMaterializedPath(relPath);
        continue;
      }

      if (fileHashEntry.isSetRootSymLink()) {
        materializeSymlink(relPath, fileHashEntry);
        continue;
      }

      if (fileHashEntry.isIsDirectory()) {
        materializeDirectory(relPath, fileHashEntry, remainingPaths);
        continue;
      }

      materializeFileAsync(relPath, fileHashEntry);
    }
  }

  private void postFileMaterializationHelper(
      boolean success, Path relPath, BuildJobStateFileHashEntry fileHashEntry) throws IOException {
    Path absPath = projectFilesystem.resolve(relPath);
    Preconditions.checkState(
        success,
        "Failed to materialize source file [%s] for FileHashEntry=[%s].",
        absPath,
        fileHashEntry);

    absPath.toFile().setExecutable(fileHashEntry.isExecutable);
    recordMaterializedPath(relPath);
  }

  private ListenableFuture<?> materializeFileAsync(
      Path relPath, BuildJobStateFileHashEntry fileHashEntry) throws IOException {
    // We need to maintain synchronization a per-file basis. Trying to materialize a file again
    // makes the file unavailable for a small window when we're writing it again.
    synchronized (fileHashEntry) {
      // Check if we've previously tried to materialize this file.
      if (fileMaterializationFuturesByFileHashEntry.containsKey(fileHashEntry)) {
        LOG.verbose("File already materialized: [%s].", relPath.toString());
        return fileMaterializationFuturesByFileHashEntry.get(fileHashEntry);
      }

      LOG.info("Materializing file: [%s]", relPath.toString());
      Path absPath = projectFilesystem.resolve(relPath);
      projectFilesystem.createParentDirs(absPath);
      projectFilesystem.touch(absPath);

      ListenableFuture<?> materializationFuture =
          Futures.transform(
              provider.materializeFileContentsAsync(fileHashEntry, absPath),
              (success) -> {
                try {
                  postFileMaterializationHelper(success, relPath, fileHashEntry);
                  return success;
                } catch (IOException e) {
                  throw new RuntimeException(
                      String.format(
                          "Failed to materialize source file [%s] for FileHashEntry=[%s].",
                          absPath, fileHashEntry),
                      e);
                }
              },
              executorService);

      ListenableFuture<?> prevValue =
          fileMaterializationFuturesByFileHashEntry.put(fileHashEntry, materializationFuture);

      Preconditions.checkState(prevValue == null, "must not override prev value");

      return materializationFuture;
    }
  }

  private void materializeDirectory(
      Path relPath, BuildJobStateFileHashEntry fileHashEntry, Stack<Path> remainingPaths)
      throws IOException {
    synchronized (fileHashEntry) {
      // Check if someone materialized the dir while we were waiting for synchronization.
      if (materializedPaths.contains(relPath)) {
        LOG.verbose(
            "Directory already materialized (may still be waiting for some files asynchronously): [%s].",
            relPath.toString());
        return;
      }

      projectFilesystem.mkdirs(relPath);
      remainingPaths.push(relPath);

      for (PathWithUnixSeparators unixPath : fileHashEntry.getChildren()) {
        Path absPathToChild = projectFilesystem.resolve(Paths.get(unixPath.getPath()));
        Path relPathToChild = projectFilesystem.getPathRelativeToProjectRoot(absPathToChild).get();
        if (!materializedPaths.contains(relPathToChild)) {
          remainingPaths.push(relPathToChild);
        }
      }

      if (remainingPaths.peek().equals(relPath)) {
        // This means all children were already materialized.
        LOG.info(
            "Materialized directory (may still be waiting for some files asynchronously): [%s].",
            relPath.toString());
        remainingPaths.pop();
        recordMaterializedPath(relPath);
      }
    }
  }

  private synchronized void materializeSymlink(
      Path relPath, BuildJobStateFileHashEntry fileHashEntry) throws IOException {
    synchronized (fileHashEntry) {
      Path targetAbsPath =
          projectFilesystem.resolve(fileHashEntry.getRootSymLinkTarget().getPath());

      // Check if someone materialized the symlink while we were waiting for synchronization.
      if (materializedPaths.contains(relPath)) {
        LOG.verbose(
            "Symlink already materialized: [%s] -> [%s].",
            relPath.toString(), targetAbsPath.toString());
        return;
      }

      Path rootSymlinkAbsPath = projectFilesystem.resolve(fileHashEntry.getRootSymLink().getPath());
      Path rootSymlinkRelPath =
          projectFilesystem.getPathRelativeToProjectRoot(rootSymlinkAbsPath).get();

      if (materializedPaths.contains(rootSymlinkRelPath)) {
        LOG.verbose(
            "Symlink already materialized: [%s] -> [%s].",
            relPath.toString(), targetAbsPath.toString());
        recordMaterializedPath(relPath);
        return;
      }

      LOG.info(
          "Materializing symlink [%s] -> [%s].",
          rootSymlinkAbsPath.toString(), targetAbsPath.toString());

      try {
        projectFilesystem.createParentDirs(rootSymlinkAbsPath);
        projectFilesystem.createSymLink(rootSymlinkAbsPath, targetAbsPath, true);
      } catch (IOException e) {
        LOG.error(e);
        throw new RuntimeException(e);
      }
      recordMaterializedPath(relPath);
      recordMaterializedPath(rootSymlinkRelPath);
    }
  }

  /**
   * Verification of FileHashes is important because we anyways use the remote file hashes to
   * compute the RuleKeys (see {@link DistBuildCachingEngineDelegate}). We don't want to upload
   * corrupt cache artifacts in case we end up materializing corrupt source files.
   */
  private void integrityCheck(Path relPath) throws IOException {
    Path absPath = projectFilesystem.resolve(relPath).toAbsolutePath();
    BuildJobStateFileHashEntry fileHashEntry = remoteFileHashesByAbsPath.get(absPath);
    if (fileHashEntry == null || !fileHashEntry.isSetSha1()) {
      return;
    }

    HashCode computedHash =
        Preconditions.checkNotNull(
            delegate.get(relPath),
            "File materialization failed. Delegate FileHashCache returned null HashCode for [%s].",
            relPath);
    if (!computedHash.toString().equals(fileHashEntry.getSha1())) {
      throw new HumanReadableException(
          "SHA1 of materialized file (at [%s]) does not match the SHA1 sent by buck client.\n"
              + "Computed SHA1: %s\n"
              + "Expected SHA1: %s",
          relPath, computedHash.toString(), fileHashEntry.getSha1());
    }
  }

  private void recordMaterializedPath(Path relPath) throws IOException {
    integrityCheck(relPath);
    materializedPaths.add(relPath);
  }

  public ListenableFuture<?> getMaterializationFuturesAsList() {
    return Futures.allAsList(fileMaterializationFuturesByFileHashEntry.values());
  }

  @Override
  public HashCode get(Path relPath) throws IOException {
    materializeIfNeededAsync(relPath);
    if (getMaterializationFuturesAsList().isDone()) {
      return delegate.get(relPath);
    } else {
      // Return a fake. This class is not meant for actually computing HashCodes.
      return HashCode.fromInt(0);
    }
  }

  @Override
  public long getSize(Path relPath) throws IOException {
    return delegate.getSize(relPath);
  }

  @Override
  public Optional<HashCode> getIfPresent(Path path) {
    throw new UnsupportedOperationException();
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberRelPath) throws IOException {
    materializeIfNeededAsync(archiveMemberRelPath.getArchivePath());
    if (getMaterializationFuturesAsList().isDone()) {
      return delegate.get(archiveMemberRelPath);
    } else {
      // Return a fake. This class is not meant for actually computing HashCodes.
      return HashCode.fromInt(0);
    }
  }

  @Override
  public ProjectFilesystem getFilesystem() {
    return projectFilesystem;
  }

  @Override
  public boolean willGet(Path relPath) {
    // DistBuildCellIndex makes sure only relative paths to the materializer's filesystem are
    // passed here so we can safely accept all paths here.
    return true;
  }

  @Override
  public boolean willGet(ArchiveMemberPath archiveMemberRelPath) {
    // DistBuildCellIndex makes sure only relative paths to the materializer's filesystem are
    // passed here so we can safely accept all paths here.
    return true;
  }

  @Override
  public void invalidate(Path relPath) {
    delegate.invalidate(relPath);
  }

  @Override
  public void invalidateAll() {
    delegate.invalidateAll();
  }

  @Override
  public void set(Path relPath, HashCode hashCode) throws IOException {
    delegate.set(relPath, hashCode);
  }

  @Override
  public FileHashCacheVerificationResult verify() throws IOException {
    return delegate.verify();
  }

  @Override
  public boolean isIgnored(Path path) {
    return delegate.isIgnored(path);
  }
}
