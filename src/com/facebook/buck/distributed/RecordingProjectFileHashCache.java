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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.PathWithUnixSeparators;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.cache.FileHashCacheVerificationResult;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import javax.annotation.concurrent.GuardedBy;

/**
 * Decorator class the records information about the paths being hashed as a side effect of
 * producing file hashes required for rule key computation.
 */
public class RecordingProjectFileHashCache implements ProjectFileHashCache {
  private static final Logger LOG = Logger.get(RecordingProjectFileHashCache.class);

  private static final int MAX_SYMLINK_DEPTH = 1000;

  private final ProjectFileHashCache delegate;
  private final ProjectFilesystem projectFilesystem;
  private final ImmutableSet<Path> cellPaths;

  @GuardedBy("this")
  private final RecordedFileHashes remoteFileHashes;

  private final boolean allRecordedPathsAreAbsolute;
  private boolean materializeCurrentFileDuringPreloading = false;

  public static RecordingProjectFileHashCache createForCellRoot(
      ProjectFileHashCache decoratedCache,
      RecordedFileHashes remoteFileHashes,
      DistBuildConfig distBuildConfig,
      CellPathResolver cellPathResolver) {
    return new RecordingProjectFileHashCache(
        decoratedCache,
        remoteFileHashes,
        Optional.of(distBuildConfig),
        Optional.of(cellPathResolver));
  }

  public static RecordingProjectFileHashCache createForNonCellRoot(
      ProjectFileHashCache decoratedCache, RecordedFileHashes remoteFileHashes) {
    return new RecordingProjectFileHashCache(
        decoratedCache, remoteFileHashes, Optional.empty(), Optional.empty());
  }

  private RecordingProjectFileHashCache(
      ProjectFileHashCache delegate,
      RecordedFileHashes remoteFileHashes,
      Optional<DistBuildConfig> distBuildConfig,
      Optional<CellPathResolver> cellPathResolver) {
    this.allRecordedPathsAreAbsolute = !distBuildConfig.isPresent();
    this.delegate = delegate;
    this.projectFilesystem = delegate.getFilesystem();
    this.remoteFileHashes = remoteFileHashes;
    ImmutableSet.Builder<Path> cellPaths = ImmutableSet.builder();
    cellPaths.add(MorePaths.normalize(projectFilesystem.getRootPath()));
    cellPathResolver.ifPresent(
        resolver ->
            resolver.getCellPaths().values().forEach(p -> cellPaths.add(MorePaths.normalize(p))));
    this.cellPaths = cellPaths.build();

    // TODO(alisdair,ruibm): Capture all .buckconfig dependencies automatically.
    distBuildConfig.ifPresent(this::recordWhitelistedPaths);
  }

  /**
   * Returns true if symlinks resolves to a target inside the known cell roots, and false if it
   * points outside of all known cell root.
   */
  private boolean isSymlinkInternalToKnownCellRoots(Path relPath) {
    return getCellRootForAbsolutePath(findSafeRealPath(projectFilesystem.resolve(relPath)))
        .isPresent();
  }

  /**
   * Check if the given absolute path is inside any of the known cells, and if yes, return the
   * corresponding the root path of the cell to which it belongs. Does not resolve/follow symlinks.
   */
  private Optional<Path> getCellRootForAbsolutePath(Path absPath) {
    absPath = MorePaths.normalize(absPath);
    Preconditions.checkState(absPath.isAbsolute());

    for (Path cellRoot : cellPaths) {
      if (absPath.startsWith(cellRoot)) {
        return Optional.of(cellRoot);
      }
    }
    return Optional.empty();
  }

  @Override
  public HashCode get(Path relPath) throws IOException {
    checkIsRelative(relPath);
    Queue<Path> remainingPaths = new LinkedList<>();
    remainingPaths.add(relPath);
    while (remainingPaths.size() > 0) {

      Path nextPath = remainingPaths.remove();
      Optional<HashCode> hashCode = Optional.empty();
      List<PathWithUnixSeparators> children = ImmutableList.of();
      if (isSymlinkInternalToKnownCellRoots(nextPath)) {
        hashCode = Optional.of(delegate.get(nextPath));
        if (projectFilesystem.isDirectory(nextPath)) {
          children = processDirectory(nextPath, remainingPaths);
        }
      }

      record(nextPath, Optional.empty(), hashCode, children);
    }

    return delegate.get(relPath);
  }

  private List<PathWithUnixSeparators> processDirectory(Path path, Queue<Path> remainingPaths)
      throws IOException {
    List<PathWithUnixSeparators> childrenRelativePaths = new ArrayList<>();
    for (Path relativeChildPath : projectFilesystem.getDirectoryContents(path)) {
      childrenRelativePaths.add(
          new PathWithUnixSeparators()
              .setPath(MorePaths.pathWithUnixSeparators(relativeChildPath)));
      remainingPaths.add(relativeChildPath);
    }

    return childrenRelativePaths;
  }

  @Override
  public long getSize(Path path) throws IOException {
    return delegate.getSize(path);
  }

  @Override
  public Optional<HashCode> getIfPresent(Path path) {
    throw new UnsupportedOperationException();
  }

  private static void checkIsRelative(Path path) {
    Preconditions.checkArgument(
        !path.isAbsolute(), "Path must be relative. Found [%s] instead.", path);
  }

  /**
   * This method does the same job as {@link Path#toRealPath}, except that: 1. It doesn't care once
   * the resolved target is outside of known cell roots. 2. The final target need not exist. We'll
   * resolve the links as far as we can.
   */
  private Path findSafeRealPath(Path symlinkPath) {
    return findSafeRealPathHelper(symlinkPath, 0);
  }

  private Path findSafeRealPathHelper(Path symlinkPath, int levelsProcessed) {
    Path symlinkAbsPath = MorePaths.normalize(symlinkPath.toAbsolutePath());
    if (levelsProcessed > MAX_SYMLINK_DEPTH) {
      throw new RuntimeException(
          String.format("Too many levels of symbolic links in path [%s].", symlinkAbsPath));
    }

    Optional<Path> cellRoot = getCellRootForAbsolutePath(symlinkAbsPath);
    if (!cellRoot.isPresent()) {
      return symlinkAbsPath;
    }

    int projectRootLength = cellRoot.get().getNameCount();
    for (int index = projectRootLength + 1; index <= symlinkAbsPath.getNameCount(); ++index) {

      // Note: subpath(..) does not return a rooted path, so we need to prepend an additional '/'.
      Path subpath = symlinkAbsPath.getRoot().resolve(symlinkAbsPath.subpath(0, index));
      if (!projectFilesystem.exists(subpath, LinkOption.NOFOLLOW_LINKS)) {
        return symlinkAbsPath;
      }

      if (projectFilesystem.isSymLink(subpath)) {
        try {
          return findSafeRealPathHelper(
              subpath
                  .getParent()
                  .resolve(projectFilesystem.readSymLink(subpath))
                  .resolve(subpath.relativize(symlinkAbsPath)),
              levelsProcessed + 1);
        } catch (IOException e) {
          throw new RuntimeException(
              String.format("Unexpected error in reading symlink [%s].", subpath), e);
        }
      }
    }
    return symlinkAbsPath;
  }

  // For given symlink, finds the highest level symlink in the path that points outside the
  // project. This is to avoid collisions/redundant symlink creation during re-materialization.
  // Example notes:
  // In the below examples, /a is the root of the project, and /e is outside the project.
  // Example 1:
  // /a/b/symlink_to_x_y/d -> /e/f/x/y/d
  // (where /a/b -> /e/f, and /e/f/symlink_to_x_y -> /e/f/x/y)
  // returns /a/b -> /e/f
  // Example 2:
  // /a/b/symlink_to_c/d -> /e/f/d
  // (where /a/b/symlink_to_c -> /a/b/c and /a/b/c -> /e/f)
  // returns /a/b/symlink_to_c -> /e/f
  // Note: when re-materializing symlinks we skip any intermediate symlinks inside the project
  // (in Example 2 we will re-materialize /a/b/symlink_to_c -> /e/f, and skip /a/b/c).
  private Pair<Path, Path> findExternalSymlinkRoot(Path symlinkPath) {
    int projectPathComponents = projectFilesystem.getRootPath().getNameCount();
    for (int pathEndIndex = (projectPathComponents + 1);
        pathEndIndex <= symlinkPath.getNameCount();
        pathEndIndex++) {

      // Note: subpath(..) does not return a rooted path, so we need to prepend an additional '/'.
      Path symlinkSubpath = symlinkPath.getRoot().resolve(symlinkPath.subpath(0, pathEndIndex));
      Path realSubpath = findSafeRealPath(symlinkSubpath);

      boolean realPathOutsideProject = !getCellRootForAbsolutePath(realSubpath).isPresent();
      if (realPathOutsideProject) {
        return new Pair<>(
            projectFilesystem.getPathRelativeToProjectRoot(symlinkSubpath).get(), realSubpath);
      }
    }

    throw new RuntimeException(
        String.format(
            "Failed to find root symlink pointing outside the project for symlink with path [%s].",
            symlinkPath.toAbsolutePath()));
  }

  private synchronized void record(ArchiveMemberPath relPath, Optional<HashCode> hashCode)
      throws IOException {
    if (!remoteFileHashes.containsAndAddPath(relPath)) {
      record(
          relPath.getArchivePath(),
          Optional.of(relPath.getMemberPath().toString()),
          hashCode,
          new LinkedList<>());
    }
  }

  private synchronized void record(
      Path relPath,
      Optional<String> memberRelPath,
      Optional<HashCode> hashCode,
      List<PathWithUnixSeparators> children) {
    relPath = MorePaths.normalize(relPath);
    if (remoteFileHashes.containsAndAddPath(relPath)) {
      return;
    }

    LOG.verbose("Recording path: [%s]", projectFilesystem.resolve(relPath));

    boolean isRealPathInsideProject = isSymlinkInternalToKnownCellRoots(relPath);
    if (isRealPathInsideProject && !hashCode.isPresent()) {
      throw new RuntimeException(
          String.format("Path [%s] is not an external symlink and HashCode was not set.", relPath));
    }

    BuildJobStateFileHashEntry fileHashEntry = new BuildJobStateFileHashEntry();
    boolean pathIsAbsolute = allRecordedPathsAreAbsolute;
    fileHashEntry.setPathIsAbsolute(pathIsAbsolute);
    Path entryKey = pathIsAbsolute ? projectFilesystem.resolve(relPath) : relPath;
    boolean isDirectory = isRealPathInsideProject && projectFilesystem.isDirectory(relPath);

    // Symlink handling:
    // 1) Symlink points inside a known cell root:
    // - We treat it like a regular file when uploading/re-materializing.
    // 2) Symlink points outside the project:
    // - We find the highest level part of the path that points outside the project and upload
    // meta-data about this before it is re-materialized. See findExternalSymlinkRoot() for more
    // details.
    if (!isRealPathInsideProject && !pathIsAbsolute) {
      Pair<Path, Path> symLinkRootAndTarget =
          findExternalSymlinkRoot(projectFilesystem.resolve(relPath).toAbsolutePath());

      Path symLinkRoot =
          projectFilesystem.getPathRelativeToProjectRoot(symLinkRootAndTarget.getFirst()).get();
      fileHashEntry.setRootSymLink(
          new PathWithUnixSeparators().setPath(MorePaths.pathWithUnixSeparators(symLinkRoot)));
      fileHashEntry.setRootSymLinkTarget(
          new PathWithUnixSeparators()
              .setPath(
                  MorePaths.pathWithUnixSeparators(
                      symLinkRootAndTarget.getSecond().toAbsolutePath())));
    }

    fileHashEntry.setIsDirectory(isDirectory);
    fileHashEntry.setPath(
        new PathWithUnixSeparators().setPath(MorePaths.pathWithUnixSeparators(entryKey)));
    if (memberRelPath.isPresent()) {
      fileHashEntry.setArchiveMemberPath(memberRelPath.get());
    }
    if (hashCode.isPresent()) {
      fileHashEntry.setSha1(hashCode.get().toString());
    }
    if (!isDirectory && !pathIsAbsolute && isRealPathInsideProject) {
      Path absPath = projectFilesystem.resolve(relPath).toAbsolutePath();
      fileHashEntry.setIsExecutable(absPath.toFile().canExecute());
    } else if (isDirectory && !pathIsAbsolute && isRealPathInsideProject) {
      fileHashEntry.setChildren(children);
    }

    fileHashEntry.setMaterializeDuringPreloading(materializeCurrentFileDuringPreloading);

    // TODO(alisdair): handling for symlink to internal directory (including infinite loop).
    remoteFileHashes.addEntry(fileHashEntry);
  }

  @Override
  public HashCode get(ArchiveMemberPath relPath) throws IOException {
    checkIsRelative(relPath.getArchivePath());
    HashCode hashCode = delegate.get(relPath);
    record(relPath, Optional.of(hashCode));
    return hashCode;
  }

  private synchronized void recordWhitelistedPaths(DistBuildConfig distBuildConfig) {
    // TODO(alisdair,shivanker): KnownBuildRuleTypes always loads java compilers if they are
    // defined in a .buckconfig, regardless of what type of build is taking place. Unless peforming
    // a Java build, they are not added to the build graph, and as such Stampede needs to be told
    // about them directly via the whitelist.

    Optional<ImmutableList<String>> whitelist = distBuildConfig.getOptionalPathWhitelist();
    if (!whitelist.isPresent()) {
      return;
    }

    LOG.info(
        "Stampede always_materialize_whitelist=[%s] cell=[%s].",
        whitelist.isPresent() ? Joiner.on(", ").join(whitelist.get()) : "",
        delegate.getFilesystem().getRootPath().toString());

    try {
      // We want to materialize files during pre-loading for .buckconfig entries
      materializeCurrentFileDuringPreloading = true;

      for (String entry : whitelist.get()) {
        Path relPath = projectFilesystem.getPath(entry);

        if (isIgnored(relPath)) {
          LOG.info("Not recording file because it's an ignored path: [%s].", relPath);
          continue;
        }

        if (!isSymlinkInternalToKnownCellRoots(relPath)) {
          LOG.info(
              "Not recording file because it's a symlink external to known cell roots: [%s].",
              relPath);
          record(relPath, Optional.empty(), Optional.empty(), ImmutableList.of());
          continue;
        }

        if (!willGet(relPath)) {
          LOG.warn("Unable to record file: [%s].", relPath);
          continue;
        }
        get(relPath);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      materializeCurrentFileDuringPreloading = false;
    }
  }

  @Override
  public ProjectFilesystem getFilesystem() {
    return projectFilesystem;
  }

  @Override
  public boolean willGet(Path relPath) {
    return delegate.willGet(relPath);
  }

  @Override
  public boolean willGet(ArchiveMemberPath archiveMemberRelPath) {
    return delegate.willGet(archiveMemberRelPath);
  }

  @Override
  public boolean isIgnored(Path path) {
    return delegate.isIgnored(path);
  }

  @Override
  public void invalidate(Path path) {
    delegate.invalidate(path);
  }

  @Override
  public void invalidateAll() {
    delegate.invalidateAll();
  }

  @Override
  public void set(Path path, HashCode hashCode) throws IOException {
    delegate.set(path, hashCode);
  }

  @Override
  public FileHashCacheVerificationResult verify() throws IOException {
    return delegate.verify();
  }
}
