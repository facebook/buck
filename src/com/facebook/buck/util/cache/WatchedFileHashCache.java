/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.WatchmanOverflowEvent;
import com.facebook.buck.util.WatchmanPathEvent;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

public class WatchedFileHashCache extends DefaultFileHashCache {

  private static final Logger LOG = Logger.get(WatchedFileHashCache.class);

  private long newCacheInvalidationAggregatedNanoTime = 0;
  private long oldCacheInvalidationAggregatedNanoTime = 0;
  private long numberOfInvalidations = 0;
  private long newCacheRetrievalAggregatedNanoTime = 0;
  private long oldCacheRetrievalAggregatedNanoTime = 0;
  private long numberOfRetrievals = 0;
  private long sha1Mismatches = 0;
  private String sha1MismatchInfo = "";

  public WatchedFileHashCache(ProjectFilesystem projectFilesystem) {
    super(projectFilesystem, Optional.empty());
  }

  /**
   * Called when file change events are posted to the file change EventBus to invalidate cached
   * build rules if required. {@link Path}s contained within events must all be relative to the
   * {@link ProjectFilesystem} root.
   */
  @Subscribe
  public synchronized void onFileSystemChange(WatchmanPathEvent event) {
    // Path event, remove the path from the cache as it has been changed, added or deleted.
    Path path = event.getPath().normalize();
    LOG.verbose("Invalidating %s", path);
    invalidate(path);
  }

  // TODO(rvitale): remove block below after the file hash cache experiment is over.
  /* *****************************************************************************/
  public void resetCounters() {
    newCacheInvalidationAggregatedNanoTime = 0;
    oldCacheInvalidationAggregatedNanoTime = 0;
    numberOfInvalidations = 0;
    sha1Mismatches = 0;
  }

  public long getNewCacheInvalidationAggregatedNanoTime() {
    return newCacheInvalidationAggregatedNanoTime;
  }

  public long getOldCacheInvalidationAggregatedNanoTime() {
    return oldCacheInvalidationAggregatedNanoTime;
  }

  public long getNumberOfInvalidations() {
    return numberOfInvalidations;
  }

  public long getNewCacheRetrievalAggregatedNanoTime() {
    return newCacheRetrievalAggregatedNanoTime;
  }

  public long getOldCacheRetrievalAggregatedNanoTime() {
    return oldCacheRetrievalAggregatedNanoTime;
  }

  public long getNumberOfRetrievals() {
    return numberOfRetrievals;
  }

  public long getSha1Mismatches() {
    return sha1Mismatches;
  }

  public String getSha1MismatchInfo() {
    return sha1MismatchInfo;
  }

  private void invalidateOldCache(Path path) {
    Iterable<Path> pathsToInvalidate =
        Maps.filterEntries(
                loadingCache.asMap(),
                entry -> {
                  Preconditions.checkNotNull(entry);

                  // If we get a invalidation for a file which is a prefix of our current one, this
                  // means the invalidation is of a symlink which points to a directory (since events
                  // won't be triggered for directories).  We don't fully support symlinks, however,
                  // we do support some limited flows that use them to point to read-only storage
                  // (e.g. the `project.read_only_paths`).  For these limited flows to work correctly,
                  // we invalidate.
                  if (entry.getKey().startsWith(path)) {
                    return true;
                  }

                  // Otherwise, we want to invalidate the entry if the path matches it.  We also
                  // invalidate any directories that contain this entry, so use the following
                  // comparison to capture both these scenarios.
                  if (path.startsWith(entry.getKey())) {
                    return true;
                  }

                  return false;
                })
            .keySet();
    LOG.verbose("Paths to invalidate: %s", pathsToInvalidate);
    for (Path pathToInvalidate : pathsToInvalidate) {
      invalidateOld(pathToInvalidate);
    }
  }

  @Override
  public HashCode get(Path relativeFilePath) throws IOException {
    long start = System.nanoTime();
    HashCode sha1 = super.get(relativeFilePath.normalize());
    oldCacheRetrievalAggregatedNanoTime += System.nanoTime() - start;
    start = System.nanoTime();
    HashCode newSha1 = getFromNewCache(relativeFilePath);
    newCacheRetrievalAggregatedNanoTime += System.nanoTime() - start;
    numberOfRetrievals++;
    if (!sha1.equals(newSha1)) {
      if (sha1Mismatches == 0) {
        StringBuilder sb = new StringBuilder();
        sb.append("Path: ").append(relativeFilePath.toString());
        try {
          sb.append("\nOld timestamp: ").append(loadingCache.get(relativeFilePath).getTimestamp());
        } catch (ExecutionException e) {
          throw new RuntimeException(e);
        }
        sb.append("\nNew timestamp: ").append(newLoadingCache.get(relativeFilePath).getTimestamp());
        sb.append("\nOld hash: ").append(sha1.toString());
        sb.append("\nNew hash: ").append(newSha1.toString());
        sb.append("\nOld hash rerun: ").append(super.get(relativeFilePath));
        sb.append("\nNew hash rerun: ").append(getFromNewCache(relativeFilePath));
        sb.append("\nHash recomputed: ").append(super.getHashCodeAndFileType(relativeFilePath));

        loadingCache.invalidate(relativeFilePath);
        newLoadingCache.remove(relativeFilePath);
        sha1 = super.get(relativeFilePath);
        newSha1 = getFromNewCache(relativeFilePath);
        sb.append("\n*************");
        try {
          sb.append("\nOld timestamp: ").append(loadingCache.get(relativeFilePath).getTimestamp());
        } catch (ExecutionException e) {
          throw new RuntimeException(e);
        }
        sb.append("\nNew timestamp: ").append(newLoadingCache.get(relativeFilePath).getTimestamp());
        sb.append("\nOld hash: ").append(sha1.toString());
        sb.append("\nNew hash: ").append(newSha1.toString());
        sb.append("\nOld hash rerun: ").append(super.get(relativeFilePath));
        sb.append("\nNew hash rerun: ").append(getFromNewCache(relativeFilePath));
        sb.append("\nHash recomputed: ").append(super.getHashCodeAndFileType(relativeFilePath));
        sha1MismatchInfo = sb.toString();
        LOG.debug(sha1MismatchInfo);
      }
      sha1Mismatches += 1;
    }
    return sha1;
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    long start = System.nanoTime();
    HashCode sha1 = super.get(archiveMemberPath);
    oldCacheRetrievalAggregatedNanoTime += System.nanoTime() - start;
    start = System.nanoTime();
    HashCode newSha1 = getFromNewCache(archiveMemberPath);
    newCacheRetrievalAggregatedNanoTime += System.nanoTime() - start;
    numberOfRetrievals++;
    if (!sha1.equals(newSha1)) {
      if (sha1Mismatches == 0) {
        Path relativeFilePath = archiveMemberPath.getArchivePath().normalize();
        StringBuilder sb = new StringBuilder();
        sb.append("Path: ").append(archiveMemberPath.toString());
        try {
          sb.append("\nOld timestamp: ").append(loadingCache.get(relativeFilePath).getTimestamp());
        } catch (ExecutionException e) {
          throw new RuntimeException(e);
        }
        sb.append("\nOld timestamp: ").append(newLoadingCache.get(relativeFilePath).getTimestamp());
        sb.append("\nOld hash: ").append(sha1.toString());
        sb.append("\nNew hash: ").append(newSha1.toString());
        sb.append("\nOld hash rerun: ").append(super.get(archiveMemberPath));
        sb.append("\nNew hash rerun: ").append(getFromNewCache(archiveMemberPath));
        sb.append("\nHash recomputed: ")
            .append(
                super.getHashCodeAndFileType(relativeFilePath)
                    .getContents()
                    .get(archiveMemberPath.getMemberPath()));

        loadingCache.invalidate(archiveMemberPath.getArchivePath());
        newLoadingCache.remove(archiveMemberPath.getArchivePath());
        sha1 = super.get(archiveMemberPath);
        newSha1 = getFromNewCache(archiveMemberPath);
        sb.append("\n*************");
        try {
          sb.append("\nOld timestamp: ").append(loadingCache.get(relativeFilePath).getTimestamp());
        } catch (ExecutionException e) {
          throw new RuntimeException(e);
        }
        sb.append("\nOld timestamp: ").append(newLoadingCache.get(relativeFilePath).getTimestamp());
        sb.append("\nOld hash: ").append(sha1.toString());
        sb.append("\nNew hash: ").append(newSha1.toString());
        sb.append("\nOld hash rerun: ").append(super.get(archiveMemberPath));
        sb.append("\nNew hash rerun: ").append(getFromNewCache(archiveMemberPath));
        sb.append("\nHash recomputed: ")
            .append(
                super.getHashCodeAndFileType(relativeFilePath)
                    .getContents()
                    .get(archiveMemberPath.getMemberPath()));
        sha1MismatchInfo = sb.toString();
        LOG.debug(sha1MismatchInfo);
      }
      sha1Mismatches += 1;
    }
    return sha1;
  }

  private HashCode getFromNewCache(Path relativeFilePath) throws IOException {
    Preconditions.checkArgument(!relativeFilePath.isAbsolute());
    return newLoadingCache.get(relativeFilePath).getHashCode();
  }

  private HashCode getFromNewCache(ArchiveMemberPath archiveMemberPath) throws IOException {
    Path relativeFilePath = archiveMemberPath.getArchivePath().normalize();
    HashCodeAndFileType fileHashCodeAndFileType = newLoadingCache.get(relativeFilePath);
    Path memberPath = archiveMemberPath.getMemberPath();
    HashCodeAndFileType memberHashCodeAndFileType =
        fileHashCodeAndFileType.getContents().get(memberPath);
    if (memberHashCodeAndFileType == null) {
      throw new NoSuchFileException(archiveMemberPath.toString());
    }

    return memberHashCodeAndFileType.getHashCode();
  }

  @Override
  public void invalidate(Path relativePath) {
    // invalidate(path) will invalidate all the child paths of the given path and that all the
    // parent paths will be invalidated and, if possible, removed too.
    long start = System.nanoTime();
    invalidateNew(relativePath);
    newCacheInvalidationAggregatedNanoTime += System.nanoTime() - start;
    start = System.nanoTime();
    invalidateOldCache(relativePath);
    oldCacheInvalidationAggregatedNanoTime += System.nanoTime() - start;
    numberOfInvalidations++;
  }
  /* *****************************************************************************/

  @SuppressWarnings("unused")
  @Subscribe
  public synchronized void onFileSystemChange(WatchmanOverflowEvent event) {
    // Non-path change event, likely an overflow due to many change events: invalidate everything.
    LOG.debug("Invalidating all");
    invalidateAll();
  }
}
