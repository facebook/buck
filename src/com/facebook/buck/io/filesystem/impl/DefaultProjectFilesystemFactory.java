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

package com.facebook.buck.io.filesystem.impl;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.EmbeddedCellBuckOutInfo;
import com.facebook.buck.io.filesystem.PathOrGlobMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.io.windowsfs.WindowsFS;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

public class DefaultProjectFilesystemFactory implements ProjectFilesystemFactory {

  @VisibleForTesting public static final String BUCK_BUCKD_DIR_KEY = "buck.buckd_dir";

  // A non-exhaustive list of characters that might indicate that we're about to deal with a glob.
  private static final Pattern GLOB_CHARS = Pattern.compile("[*?{\\[]");

  @Nullable
  private static final WindowsFS winFSInstance =
      Platform.detect() == Platform.WINDOWS ? new WindowsFS() : null;

  /** @return the WindowsFS singleton. */
  @Nullable
  public static WindowsFS getWindowsFSInstance() {
    return winFSInstance;
  }

  @Override
  public DefaultProjectFilesystem createProjectFilesystem(
      Path root, Config config, Optional<EmbeddedCellBuckOutInfo> embeddedCellBuckOutInfo) {
    BuckPaths buckPaths = getConfiguredBuckPaths(root, config, embeddedCellBuckOutInfo);
    return new DefaultProjectFilesystem(
        root.getFileSystem(),
        root,
        extractIgnorePaths(root, config, buckPaths),
        buckPaths,
        ProjectFilesystemDelegateFactory.newInstance(root, config),
        getWindowsFSInstance());
  }

  @Override
  public DefaultProjectFilesystem createProjectFilesystem(Path root, Config config) {
    return createProjectFilesystem(root, config, Optional.empty());
  }

  @Override
  public DefaultProjectFilesystem createProjectFilesystem(Path root) {
    return createProjectFilesystem(root, new Config());
  }

  private static ImmutableSet<PathOrGlobMatcher> extractIgnorePaths(
      Path root, Config config, BuckPaths buckPaths) {
    ImmutableSet.Builder<PathOrGlobMatcher> builder = ImmutableSet.builder();

    builder.add(new PathOrGlobMatcher(Paths.get(".idea")));

    String projectKey = "project";
    String ignoreKey = "ignore";

    String buckdDirProperty = System.getProperty(BUCK_BUCKD_DIR_KEY, ".buckd");
    if (!Strings.isNullOrEmpty(buckdDirProperty)) {
      addPathMatcherRelativeToRepo(root, builder, Paths.get(buckdDirProperty));
    }

    Path cacheDir =
        DefaultProjectFilesystem.getCacheDir(root, config.getValue("cache", "dir"), buckPaths);
    addPathMatcherRelativeToRepo(root, builder, cacheDir);

    config
        .getListWithoutComments(projectKey, ignoreKey)
        .forEach(
            input -> {
              // We don't really want to ignore the output directory when doing things like
              // filesystem
              // walks, so return null
              if (buckPaths.getBuckOut().toString().equals(input)) {
                return; // root.getFileSystem().getPathMatcher("glob:**");
              }

              if (GLOB_CHARS.matcher(input).find()) {
                builder.add(new PathOrGlobMatcher(input));
                return;
              }
              addPathMatcherRelativeToRepo(root, builder, Paths.get(input));
            });

    return builder.build();
  }

  private static void addPathMatcherRelativeToRepo(
      Path root, Builder<PathOrGlobMatcher> builder, Path pathToAdd) {
    if (!pathToAdd.isAbsolute()
        || pathToAdd.normalize().startsWith(root.toAbsolutePath().normalize())) {
      if (pathToAdd.isAbsolute()) {
        pathToAdd = root.relativize(pathToAdd);
      }
      builder.add(new PathOrGlobMatcher(pathToAdd));
    }
  }

  private static BuckPaths getConfiguredBuckPaths(
      Path rootPath, Config config, Optional<EmbeddedCellBuckOutInfo> embeddedCellBuckOutInfo) {
    BuckPaths buckPaths = BuckPaths.createDefaultBuckPaths(rootPath);
    Optional<String> configuredBuckOut = config.getValue("project", "buck_out");
    if (embeddedCellBuckOutInfo.isPresent()) {
      Path cellBuckOut =
          embeddedCellBuckOutInfo
              .get()
              .getEmbeddedCellsBuckOutBaseDir()
              .resolve(embeddedCellBuckOutInfo.get().getCellName());
      buckPaths =
          buckPaths
              .withConfiguredBuckOut(rootPath.relativize(cellBuckOut))
              .withBuckOut(rootPath.relativize(cellBuckOut));
    } else if (configuredBuckOut.isPresent()) {
      buckPaths =
          buckPaths.withConfiguredBuckOut(
              rootPath.getFileSystem().getPath(configuredBuckOut.get()));
    }
    return buckPaths;
  }

  @Override
  public DefaultProjectFilesystem createOrThrow(Path path) {
    try {
      // toRealPath() is necessary to resolve symlinks, allowing us to later
      // check whether files are inside or outside of the project without issue.
      return createProjectFilesystem(path.toRealPath().normalize());
    } catch (IOException e) {
      throw new HumanReadableException(
          String.format(
              ("Failed to resolve project root [%s]."
                  + "Check if it exists and has the right permissions."),
              path.toAbsolutePath()),
          e);
    }
  }
}
