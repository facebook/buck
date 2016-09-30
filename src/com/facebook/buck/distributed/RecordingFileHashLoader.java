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
import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.google.common.base.Optional;
import com.google.common.hash.HashCode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.concurrent.GuardedBy;

public class RecordingFileHashLoader implements FileHashLoader {
  private static final Logger LOG = Logger.get(RecordingFileHashLoader.class);

  private final FileHashLoader delegate;
  private final ProjectFilesystem projectFilesystem;
  @GuardedBy("this")
  private final BuildJobStateFileHashes remoteFileHashes;
  @GuardedBy("this")
  private final Set<Path> seenPaths;
  @GuardedBy("this")
  private final Set<ArchiveMemberPath> seenArchives;

  public RecordingFileHashLoader(
      FileHashLoader delegate,
      ProjectFilesystem projectFilesystem,
      BuildJobStateFileHashes remoteFileHashes) {
    this.delegate = delegate;
    this.projectFilesystem = projectFilesystem;
    this.remoteFileHashes = remoteFileHashes;
    this.seenPaths = new HashSet<>();
    this.seenArchives = new HashSet<>();
  }

  @Override
  public HashCode get(Path path) throws IOException {
    HashCode hashCode = delegate.get(path);
    synchronized (this) {
      if (!seenPaths.contains(path)) {
        seenPaths.add(path);
        record(path, Optional.<String>absent(), hashCode);
      }
    }
    return hashCode;
  }

  @Override
  public long getSize(Path path) throws IOException {
    return delegate.getSize(path);
  }

  private Path followPathToFinalDestination(Path path) {
    try {
      while (isSymlink(path) && projectFilesystem.getPathRelativeToProjectRoot(path).isPresent()) {
        path = path.toFile().getCanonicalFile().toPath();
        LOG.info("Updated path to: %s", path.toAbsolutePath().toString());
      }
      return path;
    } catch (Exception ex) {
      LOG.error("Exception following symlink for path: " + path.toAbsolutePath().toString(), ex);
      throw new RuntimeException(ex);
    }
  }

  Pair<Path, Path> findSymlinkRoot(Path symlink, Path target) {
    Path symLinkParent = null, targetParent = null, symLinkParentTarget = null;

    while (symLinkParentTarget == null || symLinkParentTarget.equals(targetParent)) {
      symLinkParent = symlink.getParent();
      targetParent = target.getParent();
      symLinkParentTarget = followPathToFinalDestination(symLinkParent);

      if (symLinkParentTarget.equals(targetParent)) {
        symlink = symLinkParent;
        target = targetParent;
      }
    }

    return new Pair<>(symlink, target);
  }

  // TODO(alisdair04): do this with ProjectFileSystem.isSymLink. The reason for not using it right
  // now is that it doesn't behave as expected. If we have a path /a/b/c, which points to
  // /d/e/f, but the actual sym link is /a/b to /d/e, then ProjectFileSystem.isSymLink will report
  // that /a/b/c is not a sym link. With ProjectFileSystem.isSymLink we will need to look at the
  // parent dirs of every single Path to figure out whether it is a sym link.
  private static boolean isSymlink(Path path) {
    // TODO(alisdair04,ruibm): migrate tests away from JimFS, so that this isn't needed.
    if (!path.toUri().getScheme().equals("file")) {
      // We can only follow symlinks for files.
      return false;
    }
    File file = new File(path.toAbsolutePath().toUri());
    try {
      Path canonicalPath = file.getCanonicalFile().toPath();
      return !path.toAbsolutePath().equals(canonicalPath);
    } catch (IOException e) {
      LOG.error(e);
      throw new RuntimeException(e);
    }
  }

  private synchronized void record(Path path, Optional<String> memberPath, HashCode hashCode) {
    LOG.info("Recording path: %s", path.toAbsolutePath().toString());
    Optional<Path> pathRelativeToProjectRoot =
        projectFilesystem.getPathRelativeToProjectRoot(path);
    BuildJobStateFileHashEntry fileHashEntry = new BuildJobStateFileHashEntry();
    Path entryKey;
    boolean pathIsAbsolute = !pathRelativeToProjectRoot.isPresent();
    fileHashEntry.setPathIsAbsolute(pathIsAbsolute);
    entryKey = pathIsAbsolute ? path : pathRelativeToProjectRoot.get();
    boolean isDirectory = projectFilesystem.isDirectory(path);
    Path finalPath = followPathToFinalDestination(path);
    boolean finalPathInsideProject =
        projectFilesystem.getPathRelativeToProjectRoot(finalPath).isPresent();

    // Path was a symlink to destination outside of the project
    if (!finalPathInsideProject && !pathIsAbsolute) {
      Pair<Path, Path> symLinkRootAndTarget = findSymlinkRoot(path, finalPath);

      Path symLinkRoot =
          projectFilesystem.getPathRelativeToProjectRoot(symLinkRootAndTarget.getFirst()).get();
      fileHashEntry.setRootSymLink(new PathWithUnixSeparators(MorePaths.pathWithUnixSeparators(
          symLinkRoot)));
      fileHashEntry.setRootSymLinkTarget(
          new PathWithUnixSeparators(MorePaths.pathWithUnixSeparators(
              symLinkRootAndTarget.getSecond().toAbsolutePath())));
    }


    fileHashEntry.setIsDirectory(isDirectory);
    fileHashEntry.setHashCode(hashCode.toString());
    fileHashEntry.setPath(
        new PathWithUnixSeparators(MorePaths.pathWithUnixSeparators(entryKey)));
    if (memberPath.isPresent()) {
      fileHashEntry.setArchiveMemberPath(memberPath.get().toString());
    }
    if (!isDirectory && !pathIsAbsolute && finalPathInsideProject) {
      try {
        // TODO(shivanker, ruibm): Don't read everything in memory right away.
        fileHashEntry.setContents(Files.readAllBytes(path));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    remoteFileHashes.addToEntries(fileHashEntry);
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    HashCode hashCode = delegate.get(archiveMemberPath);
    synchronized (this) {
      if (!seenArchives.contains(archiveMemberPath)) {
        seenArchives.add(archiveMemberPath);
        record(
            archiveMemberPath.getArchivePath(),
            Optional.of(archiveMemberPath.getMemberPath().toString()),
            hashCode);
      }
    }
    return hashCode;
  }
}
