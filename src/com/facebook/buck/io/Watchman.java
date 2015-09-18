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
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.SimpleProcessListener;
import com.facebook.buck.util.VersionStringComparator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
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
  private static final long POLL_TIME_NANOS = TimeUnit.SECONDS.toNanos(1);
  // Match default timeout of hgwatchman.
  private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);

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
      Console console,
      Clock clock)
      throws InterruptedException {
    return build(
        new ListeningProcessExecutor(),
        rootPath,
        env,
        new ExecutableFinder(),
        console,
        clock);
  }

  @VisibleForTesting
  @SuppressWarnings("PMD.PrematureDeclaration")
  static Watchman build(
      ListeningProcessExecutor executor,
      Path rootPath,
      ImmutableMap<String, String> env,
      ExecutableFinder exeFinder,
      Console console,
      Clock clock) throws InterruptedException {
    try {
      String watchman = exeFinder.getExecutable(WATCHMAN, env).toAbsolutePath().toString();
      Optional<Map<String, String>> result;

      long remainingTimeNanos = TIMEOUT_NANOS;
      long startTimeNanos = clock.nanoTime();
      result = execute(executor, console, clock, remainingTimeNanos, watchman, "version");

      if (!result.isPresent()) {
        return NULL_WATCHMAN;
      }

      Optional<String> rawVersion = Optional.fromNullable(result.get().get("version"));
      if (!rawVersion.isPresent()) {
        return NULL_WATCHMAN;
      }
      LOG.debug("Discovered watchman version: %s", rawVersion.get());

      ImmutableSet<Capability> capabilities = deriveCapabilities(rawVersion.get());

      Path absoluteRootPath = rootPath.toAbsolutePath();
      LOG.info("Adding watchman root: %s", absoluteRootPath);

      long watchStartTimeNanos = clock.nanoTime();
      remainingTimeNanos -= (watchStartTimeNanos - startTimeNanos);
      if (capabilities.contains(Capability.SUPPORTS_PROJECT_WATCH)) {
        result = execute(
            executor,
            console,
            clock,
            remainingTimeNanos,
            watchman,
            "watch-project",
            absoluteRootPath.toString());
      } else {
        result = execute(
            executor,
            console,
            clock,
            remainingTimeNanos,
            watchman,
            "watch",
            absoluteRootPath.toString());
      }
      LOG.info(
          "Took %d ms to add root %s",
          TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - watchStartTimeNanos),
          absoluteRootPath);

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
      ListeningProcessExecutor executor,
      Console console,
      Clock clock,
      long timeoutNanos,
      String... args)
      throws InterruptedException, IOException {
    SimpleProcessListener listener = new SimpleProcessListener();
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        ProcessExecutorParams.ofCommand(args),
        listener);

    long startTimeNanos = clock.nanoTime();
    int exitCode = executor.waitForProcess(
        process,
        Math.min(timeoutNanos, POLL_TIME_NANOS),
        TimeUnit.NANOSECONDS);
    if (exitCode == Integer.MIN_VALUE) {
      // Let the user know we're still here waiting for Watchman, then wait the
      // rest of the timeout period.
      long remainingNanos = timeoutNanos - (clock.nanoTime() - startTimeNanos);
      if (remainingNanos > 0) {
        console.getStdErr().getRawStream().format(
            "Waiting for Watchman command [%s]...\n",
            Joiner.on(" ").join(args));
        exitCode = executor.waitForProcess(
            process,
            remainingNanos,
            TimeUnit.NANOSECONDS);
      }
    }
    LOG.debug(
        "Waited %d ms for Watchman command %s, exit code %d",
        TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startTimeNanos),
        Joiner.on(" ").join(args),
        exitCode);
    if (exitCode == Integer.MIN_VALUE) {
      LOG.warn(
          "Watchman did not respond within %d ms, disabling.",
          TimeUnit.NANOSECONDS.toMillis(TIMEOUT_NANOS));
      console.getStdErr().getRawStream().format(
          "Timed out after %d ms waiting for Watchman command [%s]. Disabling Watchman.\n",
          TimeUnit.NANOSECONDS.toMillis(TIMEOUT_NANOS),
          Joiner.on(" ").join(args));
      return Optional.absent();
    }
    if (exitCode != 0) {
      LOG.error("Error %d executing %s", exitCode, Joiner.on(" ").join(args));
      return Optional.absent();
    }

    String stdout = listener.getStdout();
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
