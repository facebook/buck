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

import com.facebook.buck.cli.ConfigPathGetter;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.PathWithUnixSeparators;
import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import javax.annotation.concurrent.GuardedBy;

public class RecordingFileHashLoader implements FileHashLoader {
  private static final Logger LOG = Logger.get(RecordingFileHashLoader.class);

  private static final long MAX_ROOT_FILE_SIZE_BYTES = 1024 * 1024;

  private final FileHashLoader delegate;
  private final ProjectFilesystem projectFilesystem;
  @GuardedBy("this")
  private final BuildJobStateFileHashes remoteFileHashes;
  private ConfigPathGetter buckConfig;
  @GuardedBy("this")
  private final Set<Path> seenPaths;
  @GuardedBy("this")
  private final Set<ArchiveMemberPath> seenArchives;
  private boolean materializeCurrentFileDuringPreloading = false;

  public RecordingFileHashLoader(
      FileHashLoader delegate,
      ProjectFilesystem projectFilesystem,
      BuildJobStateFileHashes remoteFileHashes,
      ConfigPathGetter buckConfig) {
    this.delegate = delegate;
    this.projectFilesystem = projectFilesystem;
    this.remoteFileHashes = remoteFileHashes;
    this.buckConfig = buckConfig;
    this.seenPaths = new HashSet<>();
    this.seenArchives = new HashSet<>();

    extractBuckConfigFileHashes();
    extractFilesAtRoot();
  }

  @Override
  public HashCode get(Path rootPath) throws IOException {
    Queue<Path> remainingPaths = new LinkedList<>();
    remainingPaths.add(rootPath);
    while (remainingPaths.size() > 0) {
      Path nextPath = remainingPaths.remove();
      HashCode hashCode = delegate.get(nextPath);
      List<PathWithUnixSeparators> children = ImmutableList.of();
      if (projectFilesystem.isDirectory(nextPath)) {
        children = processDirectory(nextPath, remainingPaths);
      }
      synchronized (this) {
        if (!seenPaths.contains(nextPath)) {
          seenPaths.add(nextPath);
          record(nextPath, Optional.empty(), hashCode, children);
        }
      }
    }

    return delegate.get(rootPath);
  }

  private List<PathWithUnixSeparators> processDirectory(Path path, Queue<Path> remainingPaths)
      throws IOException {
    List<PathWithUnixSeparators> childrenRelativePaths = new ArrayList<>();
    for (Path relativeChildPath : projectFilesystem.getDirectoryContents(path)) {
      childrenRelativePaths.add(
          new PathWithUnixSeparators(MorePaths.pathWithUnixSeparators(relativeChildPath)));
      remainingPaths.add(projectFilesystem.resolve(relativeChildPath));
    }

    return childrenRelativePaths;
  }

  @Override
  public long getSize(Path path) throws IOException {
    return delegate.getSize(path);
  }

  private Path findRealPath(Path path) {
    try {
      Path realPath = path.toRealPath();
      boolean pathContainedSymLinks =
          !path.toAbsolutePath().normalize().equals(realPath.normalize());

      if (pathContainedSymLinks) {
        LOG.info("Followed path [%s] to real path: [%s]", path.toAbsolutePath(), realPath);
        return realPath;
      }
      return path;
    } catch (Exception ex) {
      LOG.error(ex, "Exception following symlink for path [%s]", path.toAbsolutePath());
      throw new RuntimeException(ex);
    }
  }

  // For given symlink, finds the highest level symlink in the path that points outside the project.
  // This is to avoid collisions/redundant symlink creation during re-materialization.
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
  private Pair<Path, Path> findSymlinkRoot(Path symlinkPath) {
    int projectPathComponents = projectFilesystem.getRootPath().getNameCount();
    for (int pathEndIndex = (projectPathComponents + 1);
         pathEndIndex <= symlinkPath.getNameCount();
         pathEndIndex++) {
      // Note: subpath(..) does not return a rooted path, so we need to prepend an additional '/'.
      Path symlinkSubpath = symlinkPath.getRoot().resolve(symlinkPath.subpath(
          0, pathEndIndex));
      Path realSymlinkSubpath = findRealPath(symlinkSubpath);
      boolean realPathOutsideProject =
          !projectFilesystem.getPathRelativeToProjectRoot(realSymlinkSubpath).isPresent();
      if (realPathOutsideProject) {
        return new Pair<>(
            projectFilesystem.getPathRelativeToProjectRoot(
                symlinkSubpath).get(), realSymlinkSubpath);
      }
    }

    throw new RuntimeException(
        String.format(
            "Failed to find root symlink for symlink with path [%s]",
            symlinkPath.toAbsolutePath()));

  }

  private synchronized void record(
      Path path,
      Optional<String> memberPath,
      HashCode hashCode,
      List<PathWithUnixSeparators> children) {
    LOG.info("Recording path: %s", path.toAbsolutePath());

    Optional<Path> pathRelativeToProjectRoot =
        projectFilesystem.getPathRelativeToProjectRoot(path);
    BuildJobStateFileHashEntry fileHashEntry = new BuildJobStateFileHashEntry();
    boolean pathIsAbsolute = !pathRelativeToProjectRoot.isPresent();
    fileHashEntry.setPathIsAbsolute(pathIsAbsolute);
    Path entryKey = pathIsAbsolute ? path : pathRelativeToProjectRoot.get();
    boolean isDirectory = projectFilesystem.isDirectory(path);
    Path realPath = findRealPath(path);
    boolean realPathInsideProject =
        projectFilesystem.getPathRelativeToProjectRoot(realPath).isPresent();

    // Symlink handling:
    // 1) Symlink points inside the project:
    // - We treat it like a regular file when uploading/re-materializing.
    // 2) Symlink points outside the project:
    // - We find the highest level part of the path that points outside the project and upload
    // meta-data about this before it is re-materialized. See findSymlinkRoot() for more details.
    if (!realPathInsideProject && !pathIsAbsolute) {
      Pair<Path, Path> symLinkRootAndTarget = findSymlinkRoot(path);

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
    if (!isDirectory && !pathIsAbsolute && realPathInsideProject) {
      try {
        // TODO(shivanker, ruibm): Don't read everything in memory right away.
        fileHashEntry.setContents(Files.readAllBytes(path));
        fileHashEntry.setIsExecutable(path.toFile().canExecute());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    } else if (isDirectory && !pathIsAbsolute && realPathInsideProject) {
      fileHashEntry.setChildren(children);
    }

    fileHashEntry.setMaterializeDuringPreloading(materializeCurrentFileDuringPreloading);

    // TODO(alisdair04): handling for symlink to internal directory (including infinite loop).
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
            hashCode,
            new LinkedList<>());
      }
    }
    return hashCode;
  }

  private void addIfPresent(Set<Path> paths, Optional<Path> path) {
    if (path.isPresent() &&
        projectFilesystem.getPathRelativeToProjectRoot(path.get()).isPresent()) {
      paths.add(path.get());
    }
  }

  private synchronized void extractBuckConfigFileHashes() {
    // We want to materialize files during pre-loading for .buckconfig entries
    materializeCurrentFileDuringPreloading = true;

    Set<Path> paths = new HashSet<>();

    // KnownBuildRuleTypes always loads these paths, if they are defined in a .buckconfig,
    // regardless of what type of build is taking place.
    // Unless peforming a Java build, they are not added to the build graph, and as such
    // Stampede needs to be told about them directly.
    // TODO(alisdair04,ruibm): capture all .buckconfig dependencies automatically.
    addIfPresent(paths, buckConfig.getPath("tools", "java"));
    addIfPresent(paths, buckConfig.getPath("tools", "java_for_tests"));
    addIfPresent(paths, buckConfig.getPath("tools", "javac"));
    addIfPresent(paths, buckConfig.getPath("tools", "javac_jar"));

    try {
      for (Path path : paths) {
        get(path);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      materializeCurrentFileDuringPreloading = false;
    }
  }

  private synchronized void extractFilesAtRoot() {
    // We want to materialize files at the root of the repo during pre-loading
    materializeCurrentFileDuringPreloading = true;

    try {
      Path[] rootFiles = Arrays.stream(projectFilesystem.listFiles(Paths.get(".")))
          .filter(f -> f.isFile())
          .filter(f -> !Files.isSymbolicLink(f.toPath()))
          .filter(f -> f.getName().startsWith("."))
          .filter(f -> f.length() < MAX_ROOT_FILE_SIZE_BYTES)
          .map(f -> f.toPath().toAbsolutePath())
          .toArray(Path[]::new);
      for (Path p : rootFiles) {
        get(p);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      materializeCurrentFileDuringPreloading = false;
    }
  }
}
