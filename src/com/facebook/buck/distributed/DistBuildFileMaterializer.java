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
import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.hash.HashCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

class DistBuildFileMaterializer implements FileHashLoader {
  private static final Logger LOG = Logger.get(DistBuildFileMaterializer.class);
  private final Map<Path, BuildJobStateFileHashEntry> remoteFileHashesByPath;
  private final Set<Path> symlinkedPaths;
  private final Set<Path> materializedPaths;
  private final FileContentsProvider provider;
  private final ProjectFilesystem projectFilesystem;
  private final FileHashCache directFileHashCacheDelegate;

  public DistBuildFileMaterializer(
      final ProjectFilesystem projectFilesystem,
      BuildJobStateFileHashes remoteFileHashes,
      FileContentsProvider provider,
      FileHashCache directFileHashCacheDelegate) {
    this.directFileHashCacheDelegate = directFileHashCacheDelegate;
    this.remoteFileHashesByPath = DistBuildFileHashes.indexEntriesByPath(
        projectFilesystem,
        remoteFileHashes);
    this.symlinkedPaths = Collections.newSetFromMap(new ConcurrentHashMap<Path, Boolean>());
    this.materializedPaths = Collections.newSetFromMap(new ConcurrentHashMap<Path, Boolean>());
    this.provider = provider;
    this.projectFilesystem = projectFilesystem;
  }

  public void preloadAllFiles() throws IOException {
    for (Path path : remoteFileHashesByPath.keySet()) {
      LOG.info("Preloading: [%s]", path.toString());
      BuildJobStateFileHashEntry fileHashEntry = remoteFileHashesByPath.get(path);
      if (fileHashEntry == null || fileHashEntry.isPathIsAbsolute()) {
        continue;
      } else if (fileHashEntry.isSetRootSymLink()) {
        materializeSymlink(fileHashEntry, symlinkedPaths);
        symlinkedPaths.add(path);
      } else {
        // Touch file
        projectFilesystem.createParentDirs(path);
        projectFilesystem.touch(path);
      }
    }
  }

  private synchronized void materializeIfNeeded(Path path) throws IOException {
    if (materializedPaths.contains(path)) {
      return;
    }

    LOG.info("Materializing: [%s]", path.toString());

    BuildJobStateFileHashEntry fileHashEntry = remoteFileHashesByPath.get(path);
    if (fileHashEntry == null || fileHashEntry.isPathIsAbsolute()) {
      materializedPaths.add(path);
      return;
    }

    if (fileHashEntry.isSetRootSymLink()) {
      if (!symlinkedPaths.contains(path)) {
        materializeSymlink(fileHashEntry, materializedPaths);
      }
      symlinkIntegrityCheck(fileHashEntry);
      materializedPaths.add(path);
      return;
    }

    materializedPaths.add(path);

    // TODO(alisdair04,ruibm,shivanker): materialize directories
    if (fileHashEntry.isIsDirectory()) {
      return;
    }

    projectFilesystem.createParentDirs(projectFilesystem.resolve(path));

    // Write the actual file contents.
    Optional<InputStream> fileContents = provider.getFileContents(fileHashEntry);
    if (!fileContents.isPresent()) {
      throw new HumanReadableException(
          String.format(
              "Input source file is missing from stampede. File=[%s]",
              fileHashEntry.toString()));
    }

    try (InputStream sourceStream = fileContents.get()) {
      Files.copy(sourceStream, path, StandardCopyOption.REPLACE_EXISTING);
      // TODO(alisdair04,ruibm,shivanker): apply original file permissions
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
      // RecordingFileHashLoader stored an absolute path (which was also a sym link).
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
    materializeIfNeeded(path);
    return HashCode.fromInt(0);
  }

  @Override
  public long getSize(Path path) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    materializeIfNeeded(archiveMemberPath.getArchivePath());
    return HashCode.fromInt(0);
  }
}
