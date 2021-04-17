/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.io.filesystem.impl;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.edenfs.EdenProjectFilesystemDelegate;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemDelegate;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Default implementation of {@link ProjectFilesystemDelegate} that talks to the filesystem via
 * standard Java APIs.
 */
public final class DefaultProjectFilesystemDelegate implements ProjectFilesystemDelegate {

  private static final Logger LOG = Logger.get(DefaultProjectFilesystemDelegate.class);

  private final AbsPath root;
  private final boolean loggingEnabled;
  private final long loggingThresholdMicroseconds;

  public DefaultProjectFilesystemDelegate(Path root, Optional<Long> loggingThresholdMicroseconds) {
    this(AbsPath.of(root), loggingThresholdMicroseconds);
  }

  public DefaultProjectFilesystemDelegate(
      AbsPath root, Optional<Long> loggingThresholdMicroseconds) {
    this.root = root;
    this.loggingEnabled = loggingThresholdMicroseconds.isPresent();
    this.loggingThresholdMicroseconds = Math.max(loggingThresholdMicroseconds.orElse(0L), 0L);
  }

  @Override
  public ImmutableMap<String, ? extends Object> getDetailsForLogging() {
    return ImmutableMap.of("filesystem", "default", "filesystem.root", root.toString());
  }

  @Override
  public Sha1HashCode computeSha1(Path pathRelativeToProjectRootOrJustAbsolute) throws IOException {
    AbsPath fileToHash = getPathForRelativePath(pathRelativeToProjectRootOrJustAbsolute);
    try {
      // Normally, we would just use `Files.hash(fileToHash.toFile(), Hashing.sha1())`, but if
      // fileToHash is backed by Jimfs, its toFile() method throws an UnsupportedOperationException.
      // Creating the input stream via java.nio.file.Files.newInputStream() avoids this issue.
      ByteSource source =
          new ByteSource() {
            @Override
            public InputStream openStream() throws IOException {
              // No need to wrap with BufferedInputStream because ByteSource uses
              // ByteStreams.copy(), which already buffers.
              return Files.newInputStream(fileToHash.getPath());
            }
          };

      long startTime = loggingEnabled ? System.nanoTime() : 0;
      HashCode hashCode = source.hash(Hashing.sha1());
      long endTime = loggingEnabled ? System.nanoTime() : 0;

      if (loggingEnabled) {
        long timeElapsedMicroseconds = (endTime - startTime) / 1000L;
        if (timeElapsedMicroseconds >= loggingThresholdMicroseconds) {
          LOG.info(
              String.format(
                  "SHA1: took %d microseconds to hash file at '%s'",
                  timeElapsedMicroseconds, fileToHash));
        }
      }

      return Sha1HashCode.fromHashCode(hashCode);

    } catch (IOException e) {
      String msg =
          String.format("Error computing Sha1 for %s: %s", fileToHash.toString(), e.getMessage());

      throw new IOException(msg, e);
    }
  }

  @Override
  public AbsPath getPathForRelativePath(Path pathRelativeToProjectRoot) {
    // We often create {@link Path} instances using
    // {@link java.nio.file.Paths#get(String, String...)}, but there's no guarantee that the
    // underlying {@link FileSystem} is the default one.
    if (pathRelativeToProjectRoot.getFileSystem().equals(root.getFileSystem())) {
      return root.resolve(pathRelativeToProjectRoot);
    }
    return root.resolve(pathRelativeToProjectRoot.toString());
  }

  /** Delayed-initialize Eden FS with watchman since watchman is initialized after FS is created. */
  public static void setWatchmanIfEdenProjectFileSystemDelegate(
      ProjectFilesystem filesystem, Watchman watchman) {
    if (filesystem instanceof DefaultProjectFilesystem
        && !(watchman instanceof WatchmanFactory.NullWatchman)) {
      DefaultProjectFilesystem defaultProjectFilesystem = (DefaultProjectFilesystem) filesystem;
      if (defaultProjectFilesystem.getDelegate() instanceof EdenProjectFilesystemDelegate) {
        EdenProjectFilesystemDelegate edenProjectFilesystemDelegate =
            ((EdenProjectFilesystemDelegate) defaultProjectFilesystem.getDelegate());
        edenProjectFilesystemDelegate.initEdenWatchman(watchman, filesystem);
      }
    }
  }
}
