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

package com.facebook.buck.util;

import com.facebook.buck.io.file.MoreFiles;
import com.facebook.buck.log.Logger;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class DirectoryCleaner {
  private static final Logger LOG = Logger.get(DirectoryCleaner.class);

  public interface PathSelector {
    Iterable<Path> getCandidatesToDelete(Path rootPath) throws IOException;

    /** Returns the preferred sorting order to delete paths. */
    int comparePaths(PathStats path1, PathStats path2);
  }

  private final DirectoryCleanerArgs args;

  public DirectoryCleaner(DirectoryCleanerArgs args) {
    this.args = args;
  }

  public void clean(Path pathToClean) throws IOException {

    List<PathStats> pathStats = new ArrayList<>();
    long totalSizeBytes = 0;
    for (Path path : args.getPathSelector().getCandidatesToDelete(pathToClean)) {
      PathStats stats = computePathStats(path);
      totalSizeBytes += stats.getTotalSizeBytes();
      pathStats.add(stats);
    }

    if (shouldDeleteOldestLog(pathStats.size(), totalSizeBytes)) {
      Collections.sort(
          pathStats, (stats1, stats2) -> args.getPathSelector().comparePaths(stats1, stats2));

      int remainingLogDirectories = pathStats.size();
      long finalMaxSizeBytes = args.getMaxTotalSizeBytes();
      if (args.getMaxBytesAfterDeletion().isPresent()) {
        finalMaxSizeBytes = args.getMaxBytesAfterDeletion().get();
      }

      for (int i = 0; i < pathStats.size(); ++i) {
        if (totalSizeBytes <= finalMaxSizeBytes
            && remainingLogDirectories <= args.getMaxPathCount()) {
          break;
        }

        PathStats currentPath = pathStats.get(i);
        LOG.verbose(
            "Deleting path [%s] of total size [%d] bytes.",
            currentPath.getPath(), currentPath.getTotalSizeBytes());
        MoreFiles.deleteRecursivelyIfExists(currentPath.getPath());
        --remainingLogDirectories;
        totalSizeBytes -= currentPath.getTotalSizeBytes();
      }
    }
  }

  private boolean shouldDeleteOldestLog(int currentNumberOfLogs, long totalSizeBytes) {
    if (currentNumberOfLogs <= args.getMinAmountOfEntriesToKeep()) {
      return false;
    }

    return totalSizeBytes > args.getMaxTotalSizeBytes()
        || currentNumberOfLogs > args.getMaxPathCount();
  }

  private PathStats computePathStats(Path path) throws IOException {
    // Note this will change the lastAccessTime of the file.
    BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);

    if (attributes.isDirectory()) {
      return new PathStats(
          path,
          computeDirSizeBytesRecursively(path),
          attributes.creationTime().toMillis(),
          attributes.lastAccessTime().toMillis());
    } else if (attributes.isRegularFile()) {
      return new PathStats(
          path,
          attributes.size(),
          attributes.creationTime().toMillis(),
          attributes.lastAccessTime().toMillis());
    }

    throw new IllegalArgumentException(
        String.format("Argument path [%s] is not a valid file or directory.", path.toString()));
  }

  private static long computeDirSizeBytesRecursively(Path directoryPath) throws IOException {
    final AtomicLong totalSizeBytes = new AtomicLong(0);
    Files.walkFileTree(
        directoryPath,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            totalSizeBytes.addAndGet(attrs.size());
            return FileVisitResult.CONTINUE;
          }
        });

    return totalSizeBytes.get();
  }

  public static class PathStats {
    private final Path path;
    private final long totalSizeBytes;
    private final long creationMillis;
    private final long lastAccessMillis;

    public PathStats(Path path, long totalSizeBytes, long creationMillis, long lastAccessMillis) {
      this.path = path;
      this.totalSizeBytes = totalSizeBytes;
      this.creationMillis = creationMillis;
      this.lastAccessMillis = lastAccessMillis;
    }

    public Path getPath() {
      return path;
    }

    public long getTotalSizeBytes() {
      return totalSizeBytes;
    }

    public long getCreationMillis() {
      return creationMillis;
    }

    public long getLastAccessMillis() {
      return lastAccessMillis;
    }
  }
}
