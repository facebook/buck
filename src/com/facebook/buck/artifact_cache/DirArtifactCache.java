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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.DirectoryCleaner;
import com.facebook.buck.util.DirectoryCleanerArgs;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class DirArtifactCache implements ArtifactCache {

  private static final Logger LOG = Logger.get(DirArtifactCache.class);

  private static final ArtifactCacheMode CACHE_MODE = ArtifactCacheMode.dir;
  // Ratio of bytes stored to max size that expresses how many bytes need to be stored after we
  // attempt to delete old files.
  private static final float STORED_TO_MAX_BYTES_RATIO_TRIM_TRIGGER = 0.5f;
  // How much of the max size to leave if we decide to delete old files.
  private static final float MAX_BYTES_TRIM_RATIO = 2 / 3f;
  private static final String TMP_EXTENSION = ".tmp";

  private final String name;
  private final ProjectFilesystem filesystem;
  private final Path cacheDir;
  private final Optional<Long> maxCacheSizeBytes;
  private final CacheReadMode cacheReadMode;
  private long bytesSinceLastDeleteOldFiles;

  public DirArtifactCache(
      String name,
      ProjectFilesystem filesystem,
      Path cacheDir,
      CacheReadMode cacheReadMode,
      Optional<Long> maxCacheSizeBytes)
      throws IOException {
    this.name = name;
    this.filesystem = filesystem;
    this.cacheDir = cacheDir;
    this.maxCacheSizeBytes = maxCacheSizeBytes;
    this.cacheReadMode = cacheReadMode;
    this.bytesSinceLastDeleteOldFiles = 0L;

    // Check first, as mkdirs will fail if the path is a symlink.
    if (!filesystem.isDirectory(cacheDir)) {
      filesystem.mkdirs(cacheDir);
    }
  }

  @Override
  public ListenableFuture<CacheResult> fetchAsync(RuleKey ruleKey, LazyPath output) {
    return Futures.immediateFuture(fetch(ruleKey, output));
  }

  @Override
  public void skipPendingAndFutureAsyncFetches() {
    // Async requests are not supported by DirArtifactCache, so do nothing
  }

  private CacheResult fetch(RuleKey ruleKey, LazyPath output) {
    CacheResult result;
    try {
      // First, build up the metadata from the metadata file.
      ImmutableMap.Builder<String, String> metadata = ImmutableMap.builder();
      try (DataInputStream in =
          new DataInputStream(
              filesystem.newFileInputStream(
                  getPathForRuleKey(ruleKey, Optional.of(".metadata"))))) {
        int sz = in.readInt();
        for (int i = 0; i < sz; i++) {
          String key = in.readUTF();
          int valSize = in.readInt();
          byte[] val = new byte[valSize];
          ByteStreams.readFully(in, val);
          metadata.put(key, new String(val, Charsets.UTF_8));
        }
      }

      // Now copy the artifact out.
      filesystem.copyFile(getPathForRuleKey(ruleKey, Optional.empty()), output.get());

      result =
          CacheResult.hit(name, CACHE_MODE, metadata.build(), filesystem.getFileSize(output.get()));
    } catch (NoSuchFileException e) {
      result = CacheResult.miss();
    } catch (IOException e) {
      LOG.warn(e, "Artifact fetch(%s, %s) error", ruleKey, output);
      result =
          CacheResult.error(
              name, CACHE_MODE, String.format("%s: %s", e.getClass(), e.getMessage()));
    }

    LOG.verbose(
        "Artifact fetch(%s, %s) cache %s",
        ruleKey, output, (result.getType().isSuccess() ? "hit" : "miss"));
    return result;
  }

  @Override
  public ListenableFuture<Void> store(ArtifactInfo info, BorrowablePath output) {

    if (!getCacheReadMode().isWritable()) {
      return Futures.immediateFuture(null);
    }

    try {
      Optional<Path> borrowedAndStoredArtifactPath = Optional.empty();
      for (RuleKey ruleKey : info.getRuleKeys()) {
        Path artifactPath = getPathForRuleKey(ruleKey, Optional.empty());
        Path metadataPath = getPathForRuleKey(ruleKey, Optional.of(".metadata"));

        if (filesystem.exists(artifactPath) && filesystem.exists(metadataPath)) {
          continue;
        }

        filesystem.mkdirs(getParentDirForRuleKey(ruleKey));

        if (!output.canBorrow()) {
          storeArtifactOutput(output.getPath(), artifactPath);
        } else {
          // This branch means that we are apparently the only users of the `output`, so instead
          // of making a safe transfer of the output to the dir cache (copy+move), we can just
          // move it without copying.  This significantly optimizes the Disk I/O.
          if (!borrowedAndStoredArtifactPath.isPresent()) {
            borrowedAndStoredArtifactPath = Optional.of(artifactPath);
            filesystem.move(output.getPath(), artifactPath, StandardCopyOption.REPLACE_EXISTING);
          } else {
            storeArtifactOutput(borrowedAndStoredArtifactPath.get(), artifactPath);
          }
        }
        bytesSinceLastDeleteOldFiles += filesystem.getFileSize(artifactPath);

        // Now, write the meta data artifact.
        Path tmp = filesystem.createTempFile(getPreparedTempFolder(), "metadata", TMP_EXTENSION);
        try {
          try (DataOutputStream out = new DataOutputStream(filesystem.newFileOutputStream(tmp))) {
            out.writeInt(info.getMetadata().size());
            for (Map.Entry<String, String> ent : info.getMetadata().entrySet()) {
              out.writeUTF(ent.getKey());
              byte[] val = ent.getValue().getBytes(Charsets.UTF_8);
              out.writeInt(val.length);
              out.write(val);
            }
          }
          filesystem.move(tmp, metadataPath, StandardCopyOption.REPLACE_EXISTING);
          bytesSinceLastDeleteOldFiles += filesystem.getFileSize(metadataPath);
        } finally {
          filesystem.deleteFileAtPathIfExists(tmp);
        }
      }

    } catch (IOException e) {
      LOG.warn(e, "Artifact store(%s, %s) error", info.getRuleKeys(), output);
    }

    if (maxCacheSizeBytes.isPresent()
        && bytesSinceLastDeleteOldFiles
            > (maxCacheSizeBytes.get() * STORED_TO_MAX_BYTES_RATIO_TRIM_TRIGGER)) {
      bytesSinceLastDeleteOldFiles = 0L;
      deleteOldFiles();
    }

    return Futures.immediateFuture(null);
  }

  @Override
  public ListenableFuture<ImmutableMap<RuleKey, CacheResult>> multiContainsAsync(
      ImmutableSet<RuleKey> ruleKeys) {
    return Futures.immediateFuture(multiContains(ruleKeys));
  }

  private ImmutableMap<RuleKey, CacheResult> multiContains(Set<RuleKey> ruleKeys) {
    ImmutableMap.Builder<RuleKey, CacheResult> results = new ImmutableMap.Builder<>();

    for (RuleKey ruleKey : ruleKeys) {
      Path artifactPath = getPathForRuleKey(ruleKey, Optional.empty());
      Path metadataPath = getPathForRuleKey(ruleKey, Optional.of(".metadata"));

      boolean contains = filesystem.exists(artifactPath) && filesystem.exists(metadataPath);
      results.put(ruleKey, contains ? CacheResult.contains(name, CACHE_MODE) : CacheResult.miss());
      LOG.verbose(
          "Artifact contains request for rulekey [%s] was a cache %s.",
          ruleKey, (contains ? "hit" : "miss"));
    }

    return results.build();
  }

  private void deleteSync(RuleKey ruleKey) {
    Path artifactPath = getPathForRuleKey(ruleKey, Optional.empty());
    Path metadataPath = getPathForRuleKey(ruleKey, Optional.of(".metadata"));

    try {
      filesystem.deleteFileAtPathIfExists(metadataPath);
      filesystem.deleteFileAtPathIfExists(artifactPath);
    } catch (IOException e) {
      String message =
          String.format("Failed to delete artifact for rule key [%s] from local cache", ruleKey);
      LOG.warn(e, message);
      throw new RuntimeException(message, e);
    }
  }

  @Override
  public ListenableFuture<CacheDeleteResult> deleteAsync(List<RuleKey> ruleKeys) {
    ruleKeys.forEach(this::deleteSync);

    ImmutableList<String> cacheNames = ImmutableList.of(DirArtifactCache.class.getSimpleName());
    return Futures.immediateFuture(CacheDeleteResult.builder().setCacheNames(cacheNames).build());
  }

  private Path getPathToTempFolder() {
    return cacheDir.resolve("tmp");
  }

  private Path getPreparedTempFolder() throws IOException {
    Path tmp = getPathToTempFolder();
    if (!filesystem.exists(tmp)) {
      filesystem.mkdirs(tmp);
    }
    return tmp;
  }

  private ImmutableList<String> subfolders(RuleKey ruleKey) {
    if (ruleKey.toString().length() < 4) {
      return ImmutableList.of();
    }
    String first = ruleKey.toString().substring(0, 2);
    String second = ruleKey.toString().substring(2, 4);
    return ImmutableList.of(first, second);
  }

  @VisibleForTesting
  Path getPathForRuleKey(RuleKey ruleKey, Optional<String> extension) {
    return getParentDirForRuleKey(ruleKey).resolve(ruleKey + extension.orElse(""));
  }

  @VisibleForTesting
  Path getParentDirForRuleKey(RuleKey ruleKey) {
    ImmutableList<String> folders = subfolders(ruleKey);
    Path result = cacheDir;
    for (String f : folders) {
      result = result.resolve(f);
    }
    return result;
  }

  private void storeArtifactOutput(Path output, Path artifactPath) throws IOException {
    // Write to a temporary file and move the file to its final location atomically to protect
    // against partial artifacts (whether due to buck interruption or filesystem failure) posing
    // as valid artifacts during subsequent buck runs.
    Path tmp = filesystem.createTempFile(getPreparedTempFolder(), "artifact", TMP_EXTENSION);
    try {
      filesystem.copyFile(output, tmp);
      filesystem.move(tmp, artifactPath);
      bytesSinceLastDeleteOldFiles += filesystem.getFileSize(artifactPath);
    } finally {
      filesystem.deleteFileAtPathIfExists(tmp);
    }
  }

  @Override
  public CacheReadMode getCacheReadMode() {
    return cacheReadMode;
  }

  @Override
  public void close() {
    // Do a cache clean up on exit only if cache was written to.
    if (bytesSinceLastDeleteOldFiles > 0) {
      deleteOldFiles();
    }
  }

  /** Deletes files that haven't been accessed recently from the directory cache. */
  @VisibleForTesting
  void deleteOldFiles() {
    if (!maxCacheSizeBytes.isPresent()) {
      return;
    }

    Path cacheDirInFs = filesystem.resolve(cacheDir);
    try {
      synchronized (this) {
        newDirectoryCleaner().clean(cacheDirInFs);
      }
    } catch (IOException e) {
      LOG.error(e, "Failed to clean path [%s].", cacheDirInFs);
    }
  }

  @VisibleForTesting
  List<Path> getAllFilesInCache() {
    List<Path> allFiles = new ArrayList<>();
    Path tempFolderPath = getPathToTempFolder();
    try {
      Files.walkFileTree(
          filesystem.resolve(cacheDir),
          ImmutableSet.of(),
          Integer.MAX_VALUE,
          new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
              // do not work with files in temp folder as they will be moved later
              if (dir.equals(tempFolderPath)) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              return super.preVisitDirectory(dir, attrs);
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              allFiles.add(file);
              return super.visitFile(file, attrs);
            }
          });
    } catch (IOException e) {
      LOG.error(e, "Error getting a list of files in %s", tempFolderPath);
    }

    return allFiles;
  }

  private DirectoryCleaner newDirectoryCleaner() {
    DirectoryCleanerArgs cleanerArgs =
        DirectoryCleanerArgs.builder()
            .setPathSelector(getDirectoryCleanerPathSelector())
            .setMaxTotalSizeBytes(maxCacheSizeBytes.get())
            .setMaxBytesAfterDeletion((long) (maxCacheSizeBytes.get() * MAX_BYTES_TRIM_RATIO))
            .setMinAmountOfEntriesToKeep(0)
            .build();

    return new DirectoryCleaner(cleanerArgs);
  }

  @VisibleForTesting
  DirectoryCleaner.PathSelector getDirectoryCleanerPathSelector() {
    return new DirectoryCleaner.PathSelector() {
      @Override
      public Iterable<Path> getCandidatesToDelete(Path rootPath) {
        return getAllFilesInCache();
      }

      @Override
      public int comparePaths(DirectoryCleaner.PathStats path1, DirectoryCleaner.PathStats path2) {
        return ComparisonChain.start()
            .compare(path1.getLastAccessMillis(), path2.getLastAccessMillis())
            .compare(path1.getCreationMillis(), path2.getCreationMillis())
            .result();
      }
    };
  }

  @VisibleForTesting
  Path getCacheDir() {
    return cacheDir;
  }
}
