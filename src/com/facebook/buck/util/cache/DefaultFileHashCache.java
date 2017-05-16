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

package com.facebook.buck.util.cache;

import com.facebook.buck.hashing.PathHashing;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.FileSystemMap;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

public class DefaultFileHashCache implements ProjectFileHashCache {

  private static final boolean SHOULD_CHECK_IGNORED_PATHS =
      Boolean.getBoolean("buck.DefaultFileHashCache.check_ignored_paths");

  private final ProjectFilesystem projectFilesystem;
  private final Optional<Path> buckOutPath;

  @VisibleForTesting final FileSystemMap<HashCodeAndFileType> newLoadingCache;

  @VisibleForTesting final FileSystemMap<Long> newSizeCache;

  // TODO(rvitale): remove block below after the file hash cache experiment is over.
  /* *****************************************************************************/
  @VisibleForTesting final LoadingCache<Path, HashCodeAndFileType> loadingCache;

  @VisibleForTesting final LoadingCache<Path, Long> sizeCache;
  /* *****************************************************************************/

  @VisibleForTesting
  DefaultFileHashCache(ProjectFilesystem projectFilesystem, Optional<Path> buckOutPath) {
    this.projectFilesystem = projectFilesystem;
    this.buckOutPath = buckOutPath;

    this.newLoadingCache =
        new FileSystemMap<>(
            path -> {
              try {
                return getHashCodeAndFileType(path);
              } catch (IOException e) {
                throw new RuntimeException(e.getCause());
              }
            });

    this.newSizeCache =
        new FileSystemMap<>(
            path -> {
              try {
                return getPathSize(path);
              } catch (IOException e) {
                throw new RuntimeException(e.getCause());
              }
            });

    // TODO(rvitale): remove block below after the file hash cache experiment is over.
    /* *****************************************************************************/
    this.loadingCache =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Path, HashCodeAndFileType>() {
                  @Override
                  public HashCodeAndFileType load(Path path) throws Exception {
                    return getHashCodeAndFileType(path);
                  }
                });

    this.sizeCache =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Path, Long>() {
                  @Override
                  public Long load(Path path) throws Exception {
                    return getPathSize(path);
                  }
                });
    /* *****************************************************************************/
  }

  public static DefaultFileHashCache createBuckOutFileHashCache(
      ProjectFilesystem projectFilesystem, Path buckOutPath) {
    return new DefaultFileHashCache(projectFilesystem, Optional.of(buckOutPath));
  }

  public static DefaultFileHashCache createDefaultFileHashCache(
      ProjectFilesystem projectFilesystem) {
    return new DefaultFileHashCache(projectFilesystem, Optional.empty());
  }

  public static ImmutableList<? extends ProjectFileHashCache> createOsRootDirectoriesCaches()
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

      ProjectFilesystem projectFilesystem =
          ProjectFilesystem.createNewOrThrowHumanReadableException(root);
      // A cache which caches hashes of absolute paths which my be accessed by certain
      // rules (e.g. /usr/bin/gcc), and only serves to prevent rehashing the same file
      // multiple times in a single run.
      allCaches.add(DefaultFileHashCache.createDefaultFileHashCache(projectFilesystem));
    }

    return allCaches.build();
  }

  private void checkNotIgnored(Path relativePath) {
    if (SHOULD_CHECK_IGNORED_PATHS) {
      Preconditions.checkArgument(!projectFilesystem.isIgnored(relativePath));
    }
  }

  // TODO(rvitale): restrict visibility of this method after the file hash cache experiment is over.
  HashCodeAndFileType getHashCodeAndFileType(Path path) throws IOException {
    if (projectFilesystem.isDirectory(path)) {
      return getDirHashCode(path);
    } else if (path.toString().endsWith(".jar")) {
      return HashCodeAndFileType.ofArchive(getFileHashCode(path), projectFilesystem, path);
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
    ImmutableSet<Path> children = PathHashing.hashPath(hasher, this, projectFilesystem, path);
    return HashCodeAndFileType.ofDirectory(hasher.hash(), children);
  }

  @Override
  public boolean willGet(Path relativePath) {
    Preconditions.checkState(!relativePath.isAbsolute());
    checkNotIgnored(relativePath);
    return loadingCache.getIfPresent(relativePath) != null
        || (projectFilesystem.exists(relativePath) && !isIgnored(relativePath));
  }

  private boolean isIgnored(Path path) {
    if (buckOutPath.isPresent()) {
      return !path.startsWith(buckOutPath.get());
    }
    return projectFilesystem.isIgnored(path);
  }

  @Override
  public boolean willGet(ArchiveMemberPath archiveMemberPath) {
    Preconditions.checkState(!archiveMemberPath.getArchivePath().isAbsolute());
    checkNotIgnored(archiveMemberPath.getArchivePath());
    return willGet(archiveMemberPath.getArchivePath());
  }

  // TODO(rvitale): remove block below after the file hash cache experiment is over.
  /* *****************************************************************************/
  private void invalidateImmediate(Path path) {
    loadingCache.invalidate(path);
    sizeCache.invalidate(path);
  }

  @Override
  public void invalidate(Path relativePath) {
    invalidateOld(relativePath);
    invalidateNew(relativePath);
  }

  @Override
  public void invalidateAll() {
    loadingCache.invalidateAll();
    sizeCache.invalidateAll();
    invalidateAllNew();
  }
  /* *****************************************************************************/

  // TODO(rvitale): rename functions below after the file hash cache experiment is over.
  /* *****************************************************************************/
  public void invalidateOld(Path relativePath) {
    Preconditions.checkArgument(!relativePath.isAbsolute());
    checkNotIgnored(relativePath);
    HashCodeAndFileType cached = loadingCache.getIfPresent(relativePath);
    invalidateImmediate(relativePath);
    if (cached != null) {
      for (Path child : cached.getChildren()) {
        invalidateImmediate(relativePath.resolve(child));
      }
    }
  }

  public void invalidateNew(Path relativePath) {
    Preconditions.checkArgument(!relativePath.isAbsolute());
    checkNotIgnored(relativePath);
    newLoadingCache.remove(relativePath);
    newSizeCache.remove(relativePath);
  }

  public void invalidateAllNew() {
    newLoadingCache.removeAll();
    newSizeCache.removeAll();
  }
  /* *****************************************************************************/

  /** @return The {@link com.google.common.hash.HashCode} of the contents of path. */
  @Override
  public HashCode get(Path relativePath) throws IOException {
    Preconditions.checkArgument(!relativePath.isAbsolute());
    checkNotIgnored(relativePath);
    HashCode sha1;
    // TODO(rvitale): remove block below after the file hash cache experiment is over.
    /* *****************************************************************************/
    try {
      sha1 = loadingCache.get(relativePath.normalize()).getHashCode();
    } catch (ExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      throw new RuntimeException(e.getCause());
    }
    /* *****************************************************************************/

    return Preconditions.checkNotNull(sha1, "Failed to find a HashCode for %s.", relativePath);
  }

  @Override
  public long getSize(Path relativePath) throws IOException {
    Preconditions.checkArgument(!relativePath.isAbsolute());
    checkNotIgnored(relativePath);
    newSizeCache.get(relativePath.normalize());
    // TODO(rvitale): remove block below after the file hash cache experiment is over.
    /* *****************************************************************************/
    try {
      return sizeCache.get(relativePath.normalize());
    } catch (ExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      throw new RuntimeException(e.getCause());
    }
    /* *****************************************************************************/
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    Preconditions.checkArgument(!archiveMemberPath.isAbsolute());
    checkNotIgnored(archiveMemberPath.getArchivePath());

    Path relativeFilePath = archiveMemberPath.getArchivePath().normalize();
    // TODO(rvitale): remove try-catch and un-indent after file hash cache experiment is over.
    /* *****************************************************************************/
    try {
      HashCodeAndFileType fileHashCodeAndFileType = loadingCache.get(relativeFilePath);
      Path memberPath = archiveMemberPath.getMemberPath();
      HashCodeAndFileType memberHashCodeAndFileType =
          fileHashCodeAndFileType.getContents().get(memberPath);
      if (memberHashCodeAndFileType == null) {
        throw new NoSuchFileException(archiveMemberPath.toString());
      }

      return memberHashCodeAndFileType.getHashCode();
    } catch (ExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      throw new RuntimeException(e.getCause());
    }
    /* *****************************************************************************/
  }

  @Override
  public ProjectFilesystem getFilesystem() {
    return projectFilesystem;
  }

  @Override
  public void set(Path relativePath, HashCode hashCode) throws IOException {
    Preconditions.checkArgument(!relativePath.isAbsolute());
    checkNotIgnored(relativePath);

    HashCodeAndFileType value;

    if (projectFilesystem.isDirectory(relativePath)) {
      value =
          HashCodeAndFileType.ofDirectory(
              hashCode,
              projectFilesystem
                  .getFilesUnderPath(relativePath)
                  .stream()
                  .map(relativePath::relativize)
                  .collect(MoreCollectors.toImmutableSet()));
    } else if (relativePath.toString().endsWith(".jar")) {
      value =
          HashCodeAndFileType.ofArchive(
              hashCode,
              projectFilesystem,
              projectFilesystem.getPathRelativeToProjectRoot(relativePath).get());

    } else {
      value = HashCodeAndFileType.ofFile(hashCode);
    }

    loadingCache.put(relativePath, value);
    newLoadingCache.put(relativePath, value);
  }

  @Override
  public FileHashCacheVerificationResult verify() throws IOException {
    List<String> errors = new ArrayList<>();
    Map<Path, HashCodeAndFileType> cacheMap = loadingCache.asMap();
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
}
