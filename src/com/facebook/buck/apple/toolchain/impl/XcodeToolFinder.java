/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.apple.toolchain.impl;

import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.file.FileFinder;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

public final class XcodeToolFinder {
  private final Platform platform;
  private final AppleConfig appleConfig;

  public XcodeToolFinder(AppleConfig appleConfig) {
    this.platform = Platform.detect();
    this.appleConfig = appleConfig;
  }

  private final LoadingCache<Path, ImmutableSet<Path>> directoryContentsCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<Path, ImmutableSet<Path>>() {
                @Override
                public ImmutableSet<Path> load(@Nonnull Path key) throws IOException {
                  try (Stream<Path> contents = Files.list(key)) {
                    return RichStream.from(contents).map(Path::getFileName).toImmutableSet();
                  } catch (NotDirectoryException | NoSuchFileException e) {
                    return ImmutableSet.of();
                  }
                }
              });

  public Optional<Path> getToolPath(ImmutableList<Path> searchPath, String toolName) {
    Optional<Path> toolPath = appleConfig.getXcodeToolReplacement(toolName);
    if (toolPath.isPresent()) {
      if (ExecutableFinder.isExecutable(toolPath.get())) {
        return toolPath;
      } else {
        throw new HumanReadableException(
            "Could not find executable at %s. apple.%s_replacement must point to valid executable",
            toolPath.get(), toolName);
      }
    }

    return FileFinder.getOptionalFile(
        FileFinder.combine(
            ImmutableSet.of(),
            appleConfig.getXcodeToolName(toolName),
            ExecutableFinder.getExecutableSuffixes(platform, ImmutableMap.of())),
        searchPath,
        (path) -> {
          try {
            if (!directoryContentsCache.get(path.getParent()).contains(path.getFileName())) {
              return false;
            }
          } catch (ExecutionException e) {
            if (!(e.getCause() instanceof IOException)) {
              throw new IllegalStateException("Unexpected exception cause", e);
            }
            // Fallback to a direct check if the `ls` on the parent directory experienced an
            // unexpected IOException.
          }

          return !Files.isDirectory(path) && Files.isExecutable(path);
        });
  }
}
