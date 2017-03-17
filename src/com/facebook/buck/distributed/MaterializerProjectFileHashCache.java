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

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.PathWithUnixSeparators;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheVerificationResult;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

class MaterializerProjectFileHashCache implements ProjectFileHashCache {

  private static final Logger LOG = Logger.get(MaterializerProjectFileHashCache.class);
  private static final String UNSUPPORTED_EXCEPTION_MSG =
      "MaterializerProjectFileHashCache should only be used as a " +
          "ProjectFileHashCache and ProjectFileHashCache.willGet(..). " +
          "It has to implement ProjectFileHashCache so a StackedFileHashCache can be used.";

  private final Map<Path, BuildJobStateFileHashEntry> remoteFileHashesByAbsPath;
  private final Set<Path> symlinkedPaths;
  private final Set<Path> materializedPaths;
  private final FileContentsProvider provider;
  private final ProjectFilesystem projectFilesystem;
  private final FileHashCache directFileHashCacheDelegate;

  public MaterializerProjectFileHashCache(
      ProjectFilesystem projectFilesystem,
      BuildJobStateFileHashes remoteFileHashes,
      FileContentsProvider provider,
      FileHashCache directFileHashCacheDelegate) {
    this.directFileHashCacheDelegate = directFileHashCacheDelegate;
    this.remoteFileHashesByAbsPath = DistBuildFileHashes.indexEntriesByPath(
        projectFilesystem,
        remoteFileHashes);
    this.symlinkedPaths = Collections.newSetFromMap(new ConcurrentHashMap<Path, Boolean>());
    this.materializedPaths = Collections.newSetFromMap(new ConcurrentHashMap<Path, Boolean>());
    this.provider = provider;
    this.projectFilesystem = projectFilesystem;
  }

  public void preloadAllFiles() throws IOException {
    for (Path absPath : remoteFileHashesByAbsPath.keySet()) {
      LOG.info("Preloading: [%s]", absPath.toString());
      BuildJobStateFileHashEntry fileHashEntry = remoteFileHashesByAbsPath.get(absPath);
      if (fileHashEntry == null || fileHashEntry.isPathIsAbsolute()) {
        continue;
      } else if (fileHashEntry.isSetMaterializeDuringPreloading() &&
          fileHashEntry.isMaterializeDuringPreloading()) {
        get(absPath);
      } else if (fileHashEntry.isSetRootSymLink()) {
        materializeSymlink(fileHashEntry, symlinkedPaths);
        symlinkedPaths.add(absPath);
      } else if (!fileHashEntry.isDirectory) {
        // Touch file
        projectFilesystem.createParentDirs(absPath);
        projectFilesystem.touch(absPath);
      } else {
        // Create directory
        // No need to materialize sub-dirs/files here, as there will be separate entries for those.
        projectFilesystem.mkdirs(absPath);
      }
    }
  }

  private void materializeIfNeeded(Path relPath, Queue<Path> remainingRelPaths) throws IOException {
    if (materializedPaths.contains(relPath)) {
      return;
    }

    LOG.info("Materializing: [%s]", relPath.toString());

    Path absPath = projectFilesystem.resolve(relPath).toAbsolutePath();
    BuildJobStateFileHashEntry fileHashEntry = remoteFileHashesByAbsPath.get(absPath);
    if (fileHashEntry == null || fileHashEntry.isPathIsAbsolute()) {
      materializedPaths.add(relPath);
      return;
    }

    if (fileHashEntry.isSetRootSymLink()) {
      if (!symlinkedPaths.contains(relPath)) {
        materializeSymlink(fileHashEntry, materializedPaths);
      }
      symlinkIntegrityCheck(fileHashEntry);
      materializedPaths.add(relPath);
      return;
    }

    // TODO(alisdair04,ruibm,shivanker): materialize directories
    if (fileHashEntry.isIsDirectory()) {
      materializeDirectory(relPath, fileHashEntry, remainingRelPaths);
      materializedPaths.add(relPath);
      return;
    }

    projectFilesystem.createParentDirs(projectFilesystem.resolve(relPath));

    // Download contents outside of sync block, so that fetches happen in parallel.
    // For a few cases we might get duplicate fetches, but this is much better than single
    // threaded fetches.
    Preconditions.checkState(
        provider.materializeFileContents(fileHashEntry, absPath),
        "[Stampede] Missing source file [%s] for FileHashEntry=[%s]",
        absPath,
        fileHashEntry);
    absPath.toFile().setExecutable(fileHashEntry.isExecutable);
    synchronized (this) {
      // Double check this path hasn't been materialized,
      // as previous check wasn't inside sync block.
      if (materializedPaths.contains(relPath)) {
        return;
      }

      materializedPaths.add(relPath);
    }
  }

  private synchronized void materializeDirectory(
      Path path,
      BuildJobStateFileHashEntry fileHashEntry,
      Queue<Path> remainingPaths) throws IOException {
    if (materializedPaths.contains(path)) {
      return;
    }

    projectFilesystem.mkdirs(path);

    for (PathWithUnixSeparators unixPath : fileHashEntry.getChildren()) {
      remainingPaths.add(projectFilesystem.resolve(Paths.get(unixPath.getPath())));
    }
  }

  private void symlinkIntegrityCheck(BuildJobStateFileHashEntry fileHashEntry) throws IOException {
    Path symlink = projectFilesystem.resolve(fileHashEntry.getPath().getPath());
    HashCode expectedHash = HashCode.fromString(fileHashEntry.getHashCode());
    HashCode actualHash = directFileHashCacheDelegate.get(symlink);
    if (!expectedHash.equals(actualHash)) {
      throw new RuntimeException(String.format(
          "Symlink [%s] had hashcode [%s] during scheduling, but [%s] during build.",
          symlink.toAbsolutePath(),
          expectedHash,
          actualHash));
    }
  }

  private synchronized void materializeSymlink(
      BuildJobStateFileHashEntry fileHashEntry, Set<Path> processedPaths) {
    Path rootSymlink = projectFilesystem.resolve(fileHashEntry.getRootSymLink().getPath());

    if (symlinkedPaths.contains(rootSymlink)) {
      processedPaths.add(rootSymlink);
    }

    if (processedPaths.contains(rootSymlink)) {
      return;
    }
    processedPaths.add(rootSymlink);

    if (!projectFilesystem.getPathRelativeToProjectRoot(rootSymlink).isPresent()) {
      // RecordingProjectFileHashCache stored an absolute path (which was also a sym link).
      throw new RuntimeException(
          "Root symlink is not in project root: " + rootSymlink.toAbsolutePath());
    }

    Path rootSymlinkTarget =
        projectFilesystem.resolve(fileHashEntry.getRootSymLinkTarget().getPath());
    LOG.info(
        "Materializing sym link [%s] with target [%s]",
        rootSymlink.toAbsolutePath().toString(),
        rootSymlinkTarget.toAbsolutePath().toString());

    try {
      projectFilesystem.createParentDirs(rootSymlink);
      projectFilesystem.createSymLink(
          rootSymlink,
          rootSymlinkTarget,
          true /* force creation */);
    } catch (IOException e) {
      LOG.error(e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public HashCode get(Path path) throws IOException {
    Queue<Path> remainingPaths = new LinkedList<>();
    remainingPaths.add(path);
    while (remainingPaths.size() > 0) {
      materializeIfNeeded(remainingPaths.remove(), remainingPaths);
    }
    return HashCode.fromInt(0);
  }

  @Override
  public long getSize(Path path) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    materializeIfNeeded(archiveMemberPath.getArchivePath(), new LinkedList<>());
    return HashCode.fromInt(0);
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
  public void invalidate(Path path) {
    throw new UnsupportedOperationException(UNSUPPORTED_EXCEPTION_MSG);
  }

  @Override
  public void invalidateAll() {
    throw new UnsupportedOperationException(UNSUPPORTED_EXCEPTION_MSG);
  }

  @Override
  public void set(Path path, HashCode hashCode) throws IOException {
    throw new UnsupportedOperationException(UNSUPPORTED_EXCEPTION_MSG);
  }

  @Override
  public FileHashCacheVerificationResult verify() throws IOException {
    throw new UnsupportedOperationException(UNSUPPORTED_EXCEPTION_MSG);
  }
}
