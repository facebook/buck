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

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.VersionStringComparator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Watchman {

  private static final String WATCHMAN_PROJECT_WATCH_VERSION = "3.4";

  public enum Capability {
    DIRNAME,
    SUPPORTS_PROJECT_WATCH,
    WILDMATCH_GLOB
  }

  private static final Logger LOG = Logger.get(Watchman.class);

  private static final String WATCHMAN_DIRNAME_MIN_VERSION = "3.1";
  private static final String WATCHMAN_WILDMATCH_GLOB_MIN_VERSION = "3.6.0";

  private static final VersionStringComparator VERSION_COMPARATOR = new VersionStringComparator();
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Path WATCHMAN = Paths.get("watchman");
  public static final Watchman NULL_WATCHMAN = new Watchman(
      Optional.<String>absent(),
      Optional.<String>absent(),
      Optional.<String>absent(),
      ImmutableSet.<Capability>of());

  private final Optional<String> version;
  private final Optional<String> projectName;
  private final Optional<String> watchRoot;
  private final ImmutableSet<Capability> capabilities;

  public static Watchman build(
      Path rootPath,
      ImmutableMap<String, String> env,
      Console console)
      throws InterruptedException {
    // We have to create our own process executor, since the one from Main may well have ansi
    // enabled, causing some commands to output escape codes.
    try (
        PrintStream stdout = new CapturingPrintStream();
        PrintStream stderr = new CapturingPrintStream()) {
      ProcessExecutor executor = new ProcessExecutor(
          new Console(
              Verbosity.SILENT,
              stdout,
              stderr,
              Ansi.withoutTty()));

      return build(executor, rootPath, env, new ExecutableFinder(), console);
    }
  }

  @VisibleForTesting
  static Watchman build(
      ProcessExecutor executor,
      Path rootPath,
      ImmutableMap<String, String> env,
      ExecutableFinder exeFinder,
      Console console) throws InterruptedException {
    try {
      String watchman = exeFinder.getExecutable(WATCHMAN, env).toAbsolutePath().toString();
      Optional<Map<String, String>> result;

      result = execute(executor, watchman, "version");

      if (!result.isPresent()) {
        return NULL_WATCHMAN;
      }

      Optional<String> rawVersion = Optional.fromNullable(result.get().get("version"));
      if (!rawVersion.isPresent()) {
        return NULL_WATCHMAN;
      }
      LOG.debug("Discovered watchman version: %s", rawVersion.get());

      ImmutableSet<Capability> capabilities = deriveCapabilities(rawVersion.get());

      // We write to the raw stream since it's not the end of the world if this gets overwritten,
      // and if we don't we freeze the super console.
      console.getStdErr().getRawStream().format("Adding watchman root: %s\n", rootPath);
      LOG.info("Adding watchman root: %s", rootPath);

      long start = System.currentTimeMillis();
      if (capabilities.contains(Capability.SUPPORTS_PROJECT_WATCH)) {
        result = execute(executor, watchman, "watch-project", rootPath.toAbsolutePath().toString());
      } else {
        result = execute(executor, watchman, "watch", rootPath.toAbsolutePath().toString());
      }
      LOG.info("Took %d ms to add root %s", (System.currentTimeMillis() - start), rootPath);

      if (!result.isPresent()) {
        return NULL_WATCHMAN;
      }

      Map<String, String> map = result.get();

      if (map.containsKey("error")) {
        LOG.warn("Error in watchman output: %s", map.get("error"));
        return NULL_WATCHMAN;
      }

      if (map.containsKey("warning")) {
        LOG.warn("Warning in watchman output: %s", map.get("warning"));
        return NULL_WATCHMAN;
      }

      if (!map.containsKey("watch")) {
        return NULL_WATCHMAN;
      }

      return new Watchman(
          rawVersion,
          Optional.fromNullable(map.get("relative_path")),
          Optional.fromNullable(map.get("watch")),
          capabilities);
    } catch (ClassCastException | HumanReadableException | IOException e) {
      LOG.warn(e, "Unable to determine the version of watchman. Going without.");
      return NULL_WATCHMAN;
    }
  }

  private static ImmutableSet<Capability> deriveCapabilities(String version) {
    ImmutableSet.Builder<Capability> capabilities = ImmutableSet.builder();

    // Check watchman version. We only want to use watch-project in watchman >= 3.4, since early
    // versions don't return the project path relative to the repo root.
    if (VERSION_COMPARATOR.compare(version, WATCHMAN_PROJECT_WATCH_VERSION) >= 0) {
      capabilities.add(Capability.SUPPORTS_PROJECT_WATCH);
    }

    if (VERSION_COMPARATOR.compare(version, WATCHMAN_DIRNAME_MIN_VERSION) >= 0) {
      capabilities.add(Capability.DIRNAME);
    }
    if (VERSION_COMPARATOR.compare(version, WATCHMAN_WILDMATCH_GLOB_MIN_VERSION) >= 0) {
      capabilities.add(Capability.WILDMATCH_GLOB);
    }

    return capabilities.build();
  }

  private static Optional<Map<String, String>> execute(
      ProcessExecutor executor,
      String... args)
      throws InterruptedException, IOException {
    ProcessExecutor.Result process = executor.launchAndExecute(
        ProcessExecutorParams.ofCommand(args),
        ImmutableSet.<ProcessExecutor.Option>of(),
        /* stdin */ Optional.<String>absent(),
        // Some watchman operations can take a long time. Set overly generous timeout.
        /* timeOutMs */ Optional.of(TimeUnit.MINUTES.toMillis(5)),
        /* timeOutHandler */ Optional.<Function<Process, Void>>absent());
    if (process.getExitCode() != 0) {
      LOG.error("Error %d executing %s", process.getExitCode(), Joiner.on(" ").join(args));
      return Optional.absent();
    }

    String stdout = process.getStdout().or("{}");
    LOG.debug("stdout of command: " + stdout);

    Map<String, String> output = OBJECT_MAPPER.readValue(
        stdout,
        new TypeReference<Map<String, String>>() {
        });
    return Optional.fromNullable(output);
  }

  private Watchman(
      Optional<String> version,
      Optional<String> projectName,
      Optional<String> watchRoot,
      ImmutableSet<Capability> capabilities) {
    this.version = version;
    this.projectName = projectName;
    this.watchRoot = watchRoot;


    this.capabilities = capabilities;
  }

  public Optional<String> getVersion() {
    return version;
  }

  public Optional<String> getProjectPrefix() {
    return projectName;
  }

  public Optional<String> getWatchRoot() {
    return watchRoot;
  }

  public ImmutableSet<Capability> getCapabilities() {
    return capabilities;
  }

  public boolean hasWildmatchGlob() {
    return capabilities.contains(Capability.WILDMATCH_GLOB);
  }
}
