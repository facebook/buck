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

import com.facebook.buck.bser.BserDeserializer;
import com.facebook.buck.io.unixsocket.UnixDomainSocket;
import com.facebook.buck.log.Logger;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.ForwardingProcessListener;
import com.facebook.buck.util.VersionStringComparator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Watchman implements AutoCloseable {

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
  private static final Path WATCHMAN = Paths.get("watchman");
  public static final Watchman NULL_WATCHMAN = new Watchman(
      Optional.<String>absent(),
      Optional.<String>absent(),
      Optional.<String>absent(),
      ImmutableSet.<Capability>of(),
      Optional.<Path>absent(),
      Optional.<WatchmanClient>absent());

  private final Optional<String> version;
  private final Optional<String> projectName;
  private final Optional<String> watchRoot;
  private final ImmutableSet<Capability> capabilities;
  private final Optional<Path> socketPath;
  private final Optional<WatchmanClient> watchmanClient;

  public static Watchman build(
      Path rootPath,
      ImmutableMap<String, String> env,
      Console console,
      Clock clock)
      throws InterruptedException {
    return build(
        new ListeningProcessExecutor(),
        localSocketWatchmanConnector(
            console,
            clock),
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
      Function<Path, Optional<WatchmanClient>> watchmanConnector,
      Path rootPath,
      ImmutableMap<String, String> env,
      ExecutableFinder exeFinder,
      Console console,
      Clock clock) throws InterruptedException {
    LOG.info("Creating for: " + rootPath);
    Optional<WatchmanClient> watchmanClient = Optional.absent();
    try {
      Path watchmanPath = exeFinder.getExecutable(WATCHMAN, env).toAbsolutePath();
      Optional<? extends Map<String, ? extends Object>> result;

      long remainingTimeNanos = TIMEOUT_NANOS;
      long startTimeNanos = clock.nanoTime();
      result = execute(executor, console, clock, remainingTimeNanos, watchmanPath, "get-sockname");

      if (!result.isPresent()) {
        return NULL_WATCHMAN;
      }

      Optional<String> rawVersion = Optional.fromNullable((String) result.get().get("version"));
      if (!rawVersion.isPresent()) {
        return NULL_WATCHMAN;
      }

      String rawSockname = (String) result.get().get("sockname");
      if (rawSockname == null) {
        return NULL_WATCHMAN;
      }
      Path socketPath = Paths.get(rawSockname);

      LOG.info("Connecting to Watchman version %s at %s", rawVersion.get(), socketPath);
      watchmanClient = watchmanConnector.apply(socketPath);
      if (!watchmanClient.isPresent()) {
        LOG.warn("Could not connect to Watchman, disabling.");
        return NULL_WATCHMAN;
      }
      LOG.debug("Connected to Watchman");

      ImmutableSet<Capability> capabilities = deriveCapabilities(rawVersion.get());

      Path absoluteRootPath = rootPath.toAbsolutePath();
      LOG.info("Adding watchman root: %s", absoluteRootPath);

      long watchStartTimeNanos = clock.nanoTime();
      remainingTimeNanos -= (watchStartTimeNanos - startTimeNanos);
      if (capabilities.contains(Capability.SUPPORTS_PROJECT_WATCH)) {
        result = watchmanClient.get().queryWithTimeout(
            remainingTimeNanos,
            "watch-project",
            absoluteRootPath.toString());
      } else {
        result = watchmanClient.get().queryWithTimeout(
            remainingTimeNanos,
            "watch",
            absoluteRootPath.toString());
      }
      LOG.info(
          "Took %d ms to add root %s",
          TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - watchStartTimeNanos),
          absoluteRootPath);

      if (!result.isPresent()) {
        watchmanClient.get().close();
        return NULL_WATCHMAN;
      }

      Map<String, ? extends Object> map = result.get();

      if (map.containsKey("error")) {
        LOG.warn("Error in watchman output: %s", map.get("error"));
        watchmanClient.get().close();
        return NULL_WATCHMAN;
      }

      if (map.containsKey("warning")) {
        LOG.warn("Warning in watchman output: %s", map.get("warning"));
        // Warnings are not fatal. Don't panic.
      }

      if (!map.containsKey("watch")) {
        watchmanClient.get().close();
        return NULL_WATCHMAN;
      }

      return new Watchman(
          rawVersion,
          Optional.fromNullable((String) map.get("relative_path")),
          Optional.fromNullable((String) map.get("watch")),
          capabilities,
          Optional.of(socketPath),
          watchmanClient);
    } catch (ClassCastException | HumanReadableException | IOException e) {
      LOG.warn(e, "Unable to determine the version of watchman. Going without.");
      if (watchmanClient.isPresent()) {
        try {
          watchmanClient.get().close();
        } catch (IOException ioe) {
          LOG.warn(ioe, "Could not close watchman query client");
        }
      }
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

  @SuppressWarnings("unchecked")
  private static Optional<Map<String, Object>> execute(
      ListeningProcessExecutor executor,
      Console console,
      Clock clock,
      long timeoutNanos,
      Path watchmanPath,
      String... args)
    throws InterruptedException, IOException {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    ForwardingProcessListener listener = new ForwardingProcessListener(
        Channels.newChannel(stdout), Channels.newChannel(stderr));
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        ProcessExecutorParams.builder()
            .addCommand(watchmanPath.toString(), "--output-encoding=bser")
            .addCommand(args)
            .build(),
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

    Object response = new BserDeserializer(BserDeserializer.KeyOrdering.UNSORTED)
        .deserializeBserValue(new ByteArrayInputStream(stdout.toByteArray()));
    LOG.debug("stdout of command: " + response);
    if (!(response instanceof Map<?, ?>)) {
      LOG.error("Unexpected response from Watchman: %s", response);
      return Optional.absent();
    }
    return Optional.of((Map<String, Object>) response);
  }

  private static Function<Path, Optional<WatchmanClient>> localSocketWatchmanConnector(
      final Console console,
      final Clock clock) {
    return new Function<Path, Optional<WatchmanClient>>() {
      @Override
      public Optional<WatchmanClient> apply(Path socketPath) {
        try {
          return Optional.<WatchmanClient>of(
              new WatchmanSocketClient(
                  console,
                  clock,
                  createLocalWatchmanSocket(socketPath)));
        } catch (IOException e) {
          LOG.warn(e, "Could not connect to Watchman at path %s", socketPath);
          return Optional.absent();
        }
      }

      private Socket createLocalWatchmanSocket(Path socketPath) throws IOException {
        // TODO(bhamiltoncx): Support Windows named pipes here.
        return UnixDomainSocket.createSocketWithPath(socketPath);
      }
    };
  }

  private Watchman(
      Optional<String> version,
      Optional<String> projectName,
      Optional<String> watchRoot,
      ImmutableSet<Capability> capabilities,
      Optional<Path> socketPath,
      Optional<WatchmanClient> watchmanClient) {
    this.version = version;
    this.projectName = projectName;
    this.watchRoot = watchRoot;
    this.capabilities = capabilities;
    this.socketPath = socketPath;
    this.watchmanClient = watchmanClient;
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

  public Optional<Path> getSocketPath() {
    return socketPath;
  }

  public Optional<WatchmanClient> getWatchmanClient() {
    return watchmanClient;
  }

  @Override
  public void close() throws IOException {
    if (watchmanClient.isPresent()) {
      watchmanClient.get().close();
    }
  }
}
