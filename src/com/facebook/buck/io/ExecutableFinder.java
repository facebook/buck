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

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.EnvironmentFilter;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nullable;

/**
 * Given the name of an executable, search a set of (possibly platform-specific) known locations for
 * that executable.
 */
public class ExecutableFinder {

  private static final Logger LOG = Logger.get(ExecutableFinder.class);
  private static final ImmutableSet<String> DEFAULT_WINDOWS_EXTENSIONS =
      ImmutableSet.of(
          ".bat",
          ".cmd",
          ".com",
          ".cpl",
          ".exe",
          ".js",
          ".jse",
          ".msc",
          ".vbs",
          ".wsf",
          ".wsh");

  private final Platform platform;

  public ExecutableFinder() {
    this(Platform.detect());
  }

  @VisibleForTesting
  public ExecutableFinder(Platform platform) {
    this.platform = platform;
  }

  public Path getExecutable(
      Path suggestedExecutable,
      ImmutableMap<String, String> env) {
    Optional<Path> exe = getOptionalExecutable(suggestedExecutable, env);
    if (!exe.isPresent()) {
      throw new HumanReadableException(String.format(
          "Unable to locate %s on PATH, or it's not marked as being executable",
          suggestedExecutable));
    }
    return exe.get();
  }

  public Optional<Path> getOptionalExecutable(
      Path suggestedExecutable,
      ImmutableMap<String, String> env) {
    env = EnvironmentFilter.filteredEnvironment(env, platform);

    return getOptionalExecutable(suggestedExecutable, getPaths(env), getExecutableSuffixes(env));
  }

  public Optional<Path> getOptionalExecutable(
      Path suggestedExecutable,
      Path basePath) {
    Optional<Path> executable = Optional.fromNullable(
        findExecutable(
            suggestedExecutable,
            ImmutableSet.of(basePath),
            getExecutableSuffixes(ImmutableMap.<String, String>of())));
    LOG.debug("Executable '%s' mapped to '%s'", suggestedExecutable, executable);

    return executable;
  }

  public Optional<Path> getOptionalExecutable(
      Path suggestedExecutable,
      ImmutableCollection<Path> path,
      ImmutableCollection<String> fileSuffixes) {
    Optional<Path> executable = Optional.fromNullable(
        findExecutable(suggestedExecutable, path, fileSuffixes));
    LOG.debug("Executable '%s' mapped to '%s'", suggestedExecutable, executable);

    return executable;
  }

  @Nullable
  protected Path findExecutable(
      Path suggestedPath,
      ImmutableCollection<Path> searchPath,
      ImmutableCollection<String> fileSuffixes) {
    // Fast path out of here.
    if (Files.exists(suggestedPath) && Files.isExecutable(suggestedPath)) {
      return suggestedPath;
    }

    // Always search at least the given path without suffixes.
    if (fileSuffixes.isEmpty()) {
      fileSuffixes = ImmutableSet.of("");
    }

    for (Path path : searchPath) {
      for (String suffix : fileSuffixes) {
        Path exe = path.resolve(path).resolve(suggestedPath + suffix);
        if (Files.exists(exe) && !Files.isDirectory(exe)) {
          if (Files.isExecutable(exe)) {
            return exe;
          }
          LOG.debug("Found potential executable, but not actually executable: %s", exe);
        }
      }
    }
    return null;
  }

  private ImmutableSet<Path> getPaths(ImmutableMap<String, String> env) {
    ImmutableSet.Builder<Path> paths = ImmutableSet.builder();

    // Add the empty path so that when we iterate over it, we can check for the suffixed version of
    // a given path, be it absolute or not.
    paths.add(Paths.get(""));

    String pathEnv = env.get("PATH");
    if (pathEnv != null) {
      paths.addAll(
          FluentIterable.from(Splitter.on(pathSeparator).omitEmptyStrings().split(pathEnv))
              .transform(MorePaths.TO_PATH));
    }

    if (platform == Platform.MACOS) {
      Path osXPaths = Paths.get("/etc/paths");
      if (Files.exists(osXPaths)) {
        try {
          paths.addAll(
              FluentIterable.from(Files.readAllLines(osXPaths, Charset.defaultCharset()))
                  .transform(MorePaths.TO_PATH));
        } catch (IOException e) {
          LOG.warn("Unable to read mac-specific paths. Skipping");
        }
      }
    }

    return paths.build();
  }

  private ImmutableSet<String> getExecutableSuffixes(ImmutableMap<String, String> env) {
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
}
