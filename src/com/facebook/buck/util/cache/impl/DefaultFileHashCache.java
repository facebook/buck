/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util.cache.impl;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.util.cache.FileHashCacheEngine;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.FileHashCacheVerificationResult;
import com.facebook.buck.util.cache.HashCodeAndFileType;
import com.facebook.buck.util.cache.JarHashCodeAndFileType;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.hashing.PathHashing;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class DefaultFileHashCache implements ProjectFileHashCache {

  private static final boolean SHOULD_CHECK_IGNORED_PATHS =
      Boolean.getBoolean("buck.DefaultFileHashCache.check_ignored_paths");

  private final ProjectFilesystem projectFilesystem;
  private final Predicate<Path> ignoredPredicate;

  @VisibleForTesting FileHashCacheEngine fileHashCacheEngine;

  protected DefaultFileHashCache(
      ProjectFilesystem projectFilesystem,
      Predicate<Path> ignoredPredicate,
      FileHashCacheMode fileHashCacheMode) {
    this.projectFilesystem = projectFilesystem;
    this.ignoredPredicate = ignoredPredicate;
    FileHashCacheEngine.ValueLoader<HashCodeAndFileType> hashLoader =
        path -> {
          try {
            return getHashCodeAndFileType(path);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        };

    FileHashCacheEngine.ValueLoader<Long> sizeLoader =
        path -> {
          try {
            return getPathSize(path);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        };

    FileHashCacheEngine.ValueLoader<HashCode> fileHashLoader =
        (path) -> {
          try {
            return getFileHashCode(path);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        };

    FileHashCacheEngine.ValueLoader<HashCodeAndFileType> dirHashLoader =
        (path) -> {
          try {
            return getDirHashCode(path);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        };
    switch (fileHashCacheMode) {
      case PARALLEL_COMPARISON:
        fileHashCacheEngine = new ComboFileHashCache(hashLoader, sizeLoader, projectFilesystem);
        break;
      case LOADING_CACHE:
        fileHashCacheEngine = LoadingCacheFileHashCache.createWithStats(hashLoader, sizeLoader);
        break;
      case PREFIX_TREE:
        fileHashCacheEngine =
            FileSystemMapFileHashCache.createWithStats(hashLoader, sizeLoader, projectFilesystem);
        break;
      case LIMITED_PREFIX_TREE:
        fileHashCacheEngine =
            new StatsTrackingFileHashCacheEngine(
                new LimitedFileHashCacheEngine(
                    projectFilesystem, fileHashLoader, dirHashLoader, sizeLoader),
                "limited");
        break;
      case LIMITED_PREFIX_TREE_PARALLEL:
        fileHashCacheEngine =
            new ComboFileHashCache(
                LoadingCacheFileHashCache.createWithStats(hashLoader, sizeLoader),
                new StatsTrackingFileHashCacheEngine(
                    new LimitedFileHashCacheEngine(
                        projectFilesystem, fileHashLoader, dirHashLoader, sizeLoader),
                    "limited"));
        break;
      default:
        throw new RuntimeException("Unsupported file hash cache engine: " + fileHashCacheMode);
    }
  }

  public static DefaultFileHashCache createBuckOutFileHashCache(
      ProjectFilesystem projectFilesystem, FileHashCacheMode fileHashCacheMode) {
    return new DefaultFileHashCache(
        projectFilesystem, (path) -> !isInBuckOut(projectFilesystem, path), fileHashCacheMode);
  }

  public static DefaultFileHashCache createDefaultFileHashCache(
      ProjectFilesystem projectFilesystem, FileHashCacheMode fileHashCacheMode) {
    return new DefaultFileHashCache(
        projectFilesystem, getDefaultPathPredicate(projectFilesystem), fileHashCacheMode);
  }

  /**
   * This predicate matches files that might be result of builds or files that are explicitly
   * ignored.
   */
  protected static Predicate<Path> getDefaultPathPredicate(ProjectFilesystem projectFilesystem) {
    return path ->
        isInBuckOut(projectFilesystem, path)
            || isInEmbeddedCellBuckOut(projectFilesystem, path)
            || projectFilesystem.isIgnored(path);
  }

  /** Check that the file is in the buck-out of the cell that's related to the project filesystem */
  private static boolean isInBuckOut(ProjectFilesystem projectFilesystem, Path path) {
    return path.startsWith(projectFilesystem.getBuckPaths().getConfiguredBuckOut())
        && !isInEmbeddedCellBuckOut(projectFilesystem, path);
  }

  /** Return true if file is the buck-out of a different cell when embedded buck-out is enabled */
  private static boolean isInEmbeddedCellBuckOut(ProjectFilesystem projectFilesystem, Path path) {
    return path.startsWith(projectFilesystem.getBuckPaths().getEmbeddedCellsBuckOutBaseDir());
  }

  public static ImmutableList<? extends ProjectFileHashCache> createOsRootDirectoriesCaches(
      ProjectFilesystemFactory projectFilesystemFactory, FileHashCacheMode fileHashCacheMode)
      throws InterruptedException {
    ImmutableList.Builder<ProjectFileHashCache> allCaches = ImmutableList.builder();
    for (Path root : FileSystems.getDefault().getRootDirectories()) {
      if (!root.toFile().exists()) {
        // On Windows, it is possible that the system will have a
        // drive for something that does not exist such as a floppy
        // disk or SD card.  The drive exists, but it does not
        // contain anything useful, so Buck should not consider it
        // as a cacheable location.
        continue;
      }

      ProjectFilesystem projectFilesystem = projectFilesystemFactory.createOrThrow(root);
      // A cache which caches hashes of absolute paths which my be accessed by certain
      // rules (e.g. /usr/bin/gcc), and only serves to prevent rehashing the same file
      // multiple times in a single run.
      allCaches.add(
          DefaultFileHashCache.createDefaultFileHashCache(projectFilesystem, fileHashCacheMode));
    }

    return allCaches.build();
  }

  private void checkNotIgnored(Path relativePath) {
    if (SHOULD_CHECK_IGNORED_PATHS) {
      Preconditions.checkArgument(!projectFilesystem.isIgnored(relativePath));
    }
  }

  private HashCodeAndFileType getHashCodeAndFileType(Path path) throws IOException {
    if (projectFilesystem.isDirectory(path)) {
      return getDirHashCode(path);
    } else if (path.toString().endsWith(".jar")) {
      return JarHashCodeAndFileType.ofArchive(
          getFileHashCode(path), new DefaultJarContentHasher(projectFilesystem, path));
    }

    return HashCodeAndFileType.ofFile(getFileHashCode(path));
  }

  private HashCode getFileHashCode(Path path) throws IOException {
    return projectFilesystem.computeSha1(path).asHashCode();
  }

  private long getPathSize(Path path) throws IOException {
    long size = 0;
    for (Path child : projectFilesystem.getFilesUnderPath(path)) {
      size += projectFilesystem.getFileSize(child);
    }
    return size;
  }

  private HashCodeAndFileType getDirHashCode(Path path) throws IOException {
    Hasher hasher = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher, this, projectFilesystem, path);
    return HashCodeAndFileType.ofDirectory(hasher.hash());
  }

  @Override
  public boolean willGet(Path relativePath) {
    Preconditions.checkState(!relativePath.isAbsolute());
    checkNotIgnored(relativePath);
    return fileHashCacheEngine.getIfPresent(relativePath) != null
        || (projectFilesystem.exists(relativePath) && !isIgnored(relativePath));
  }

  @Override
  public boolean isIgnored(Path path) {
    return ignoredPredicate.test(path);
  }

  @Override
  public boolean willGet(ArchiveMemberPath archiveMemberPath) {
    Preconditions.checkState(!archiveMemberPath.getArchivePath().isAbsolute());
    checkNotIgnored(archiveMemberPath.getArchivePath());
    return willGet(archiveMemberPath.getArchivePath());
  }

  @Override
  public void invalidate(Path relativePath) {
    fileHashCacheEngine.invalidate(relativePath);
  }

  @Override
  public void invalidateAll() {
    fileHashCacheEngine.invalidateAll();
  }

  /** @return The {@link com.google.common.hash.HashCode} of the contents of path. */
  @Override
  public HashCode get(Path relativePath) throws IOException {
    Preconditions.checkArgument(!relativePath.isAbsolute());
    checkNotIgnored(relativePath);
    return fileHashCacheEngine.get(relativePath);
  }

  @Override
  public long getSize(Path relativePath) throws IOException {
    Preconditions.checkArgument(!relativePath.isAbsolute());
    checkNotIgnored(relativePath);
    return fileHashCacheEngine.getSize(relativePath);
  }

  @Override
  public Optional<HashCode> getIfPresent(Path relativePath) {
    Preconditions.checkArgument(!relativePath.isAbsolute());
    checkNotIgnored(relativePath);
    return Optional.ofNullable(fileHashCacheEngine.getIfPresent(relativePath))
        .map(HashCodeAndFileType::getHashCode);
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    Preconditions.checkArgument(!archiveMemberPath.isAbsolute());
    checkNotIgnored(archiveMemberPath.getArchivePath());
    return fileHashCacheEngine.get(archiveMemberPath);
  }

  @Override
  public ProjectFilesystem getFilesystem() {
    return projectFilesystem;
  }

  @Override
  public void set(Path relativePath, HashCode hashCode) {
    Preconditions.checkArgument(!relativePath.isAbsolute());
    checkNotIgnored(relativePath);

    HashCodeAndFileType value;

    if (projectFilesystem.isDirectory(relativePath)) {
      value = HashCodeAndFileType.ofDirectory(hashCode);
    } else if (relativePath.toString().endsWith(".jar")) {
      value =
          JarHashCodeAndFileType.ofArchive(
              hashCode,
              new DefaultJarContentHasher(
                  projectFilesystem,
                  projectFilesystem.getPathRelativeToProjectRoot(relativePath).get()));
    } else {
      value = HashCodeAndFileType.ofFile(hashCode);
    }

    fileHashCacheEngine.put(relativePath, value);
  }

  @Override
  public FileHashCacheVerificationResult verify() throws IOException {
    List<String> errors = new ArrayList<>();
    Map<Path, HashCodeAndFileType> cacheMap = fileHashCacheEngine.asMap();
    for (Map.Entry<Path, HashCodeAndFileType> entry : cacheMap.entrySet()) {
      Path path = entry.getKey();
      HashCodeAndFileType cached = entry.getValue();
      HashCodeAndFileType current = getHashCodeAndFileType(path);
      if (!cached.equals(current)) {
        errors.add(path.toString());
      }
    }
    return FileHashCacheVerificationResult.builder()
        .setCachesExamined(1)
        .setFilesExamined(cacheMap.size())
        .addAllVerificationErrors(errors)
        .build();
  }

  @Override
  public Stream<Entry<Path, HashCode>> debugDump() {
    return fileHashCacheEngine
        .asMap()
        .entrySet()
        .stream()
        .map(
            entry ->
                new AbstractMap.SimpleEntry<>(
                    projectFilesystem.resolve(entry.getKey()), entry.getValue().getHashCode()));
  }

  public List<AbstractBuckEvent> getStatsEvents() {
    return fileHashCacheEngine.getStatsEvents();
  }
}
