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

package com.facebook.buck.io;

import static java.io.File.pathSeparator;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.file.FileFinder;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;

/**
 * Given the name of an executable, search a set of (possibly platform-specific) known locations for
 * that executable.
 */
public class ExecutableFinder {

  private static final Logger LOG = Logger.get(ExecutableFinder.class);
  private static final ImmutableSet<String> DEFAULT_WINDOWS_EXTENSIONS =
      ImmutableSet.of(
          "", ".bat", ".cmd", ".com", ".cpl", ".exe", ".js", ".jse", ".msc", ".vbs", ".wsf",
          ".wsh");

  private final Platform platform;

  public ExecutableFinder() {
    this(Platform.detect());
  }

  @VisibleForTesting
  public ExecutableFinder(Platform platform) {
    this.platform = platform;
  }

  public Path getExecutable(Path suggestedExecutable, ImmutableMap<String, String> env) {
    Optional<Path> exe = getOptionalExecutable(suggestedExecutable, env);
    if (!exe.isPresent()) {
      throw new HumanReadableException(
          String.format(
              "Unable to locate %s on PATH, or it's not marked as being executable",
              suggestedExecutable));
    }
    return exe.get();
  }

  public Optional<Path> getOptionalExecutable(
      Path suggestedExecutable, ImmutableMap<String, String> env) {
    return getOptionalExecutable(
        suggestedExecutable, getPaths(env), getExecutableSuffixes(platform, env));
  }

  public Optional<Path> getOptionalExecutable(Path suggestedExecutable, Path basePath) {
    return getOptionalExecutable(
        suggestedExecutable,
        ImmutableSet.of(basePath),
        getExecutableSuffixes(platform, ImmutableMap.of()));
  }

  public Optional<Path> getOptionalExecutable(
      Path suggestedExecutable,
      ImmutableCollection<Path> path,
      ImmutableCollection<String> fileSuffixes) {

    // Fast path out of here.
    if (isExecutable(suggestedExecutable)) {
      return Optional.of(suggestedExecutable);
    }

    Optional<Path> executable =
        FileFinder.getOptionalFile(
            FileFinder.combine(
                ImmutableSet.of(),
                suggestedExecutable.toString(),
                ImmutableSet.copyOf(fileSuffixes)),
            path,
            ExecutableFinder::isExecutable);
    LOG.debug("Executable '%s' mapped to '%s'", suggestedExecutable, executable);

    return executable;
  }

  public static boolean isExecutable(Path exe) {
    if (!Files.exists(exe)) {
      return false;
    }

    if (Files.isSymbolicLink(exe)) {
      try {
        Path target = Files.readSymbolicLink(exe);
        return isExecutable(exe.resolveSibling(target).normalize());
      } catch (IOException | SecurityException e) { // NOPMD
      }
    }

    if (Files.isDirectory(exe)) {
      LOG.debug("Found potential executable, but is a directory: %s", exe);
      return false;
    }

    if (!Files.isExecutable(exe) && !Files.isSymbolicLink(exe)) {
      LOG.debug("Found potential executable, but not actually executable: %s", exe);
      return false;
    }

    return true;
  }

  // Returns Path or null, if string can not be converted to Path
  private static final Function<String, Path> getPathSafe =
      new Function<String, Path>() {
        @Override
        @Nullable
        public Path apply(@Nullable String path) {
          if (path == null) {
            return null;
          }
          try {
            return Paths.get(path);
          } catch (InvalidPathException e) {
            LOG.warn("Path '%s' is invalid: %s", path, e.getReason());
            return null;
          }
        }
      };

  private ImmutableSet<Path> getPaths(ImmutableMap<String, String> env) {
    ImmutableSet.Builder<Path> paths = ImmutableSet.builder();

    // Add the empty path so that when we iterate over it, we can check for the suffixed version of
    // a given path, be it absolute or not.
    paths.add(Paths.get(""));

    String pathEnv = env.get("PATH");
    if (pathEnv != null) {
      pathEnv = pathEnv.trim();
      paths.addAll(
          StreamSupport.stream(
                  Splitter.on(pathSeparator).omitEmptyStrings().split(pathEnv).spliterator(), false)
              .map(getPathSafe)
              .filter(Objects::nonNull)
              .iterator());
    }

    if (platform == Platform.MACOS) {
      Path osXPaths = Paths.get("/etc/paths");
      if (Files.exists(osXPaths)) {
        try {
          paths.addAll(
              Files.readAllLines(osXPaths, Charset.defaultCharset()).stream()
                  .map(Paths::get)
                  .iterator());
        } catch (IOException e) {
          LOG.warn("Unable to read mac-specific paths. Skipping");
        }
      }
    }

    return paths.build();
  }

  public static ImmutableSet<String> getExecutableSuffixes(
      Platform platform, ImmutableMap<String, String> env) {
    if (platform == Platform.WINDOWS) {
      String pathext = env.get("PATHEXT");
      if (pathext == null) {
        return DEFAULT_WINDOWS_EXTENSIONS;
      }
      return ImmutableSet.<String>builder()
          .addAll(Splitter.on(";").omitEmptyStrings().split(pathext))
          .build();
    }
    return ImmutableSet.of("");
  }

  public Optional<Path> getOptionalToolPath(String tool, ImmutableList<Path> toolSearchPaths) {
    return getOptionalExecutable(Paths.get(tool), toolSearchPaths, ImmutableSet.of());
  }
}
