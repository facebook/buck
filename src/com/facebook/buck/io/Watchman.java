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
import com.facebook.buck.io.windowspipe.WindowsNamedPipe;
import com.facebook.buck.log.Logger;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ForwardingProcessListener;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class Watchman implements AutoCloseable {

  public enum Capability {
    DIRNAME,
    SUPPORTS_PROJECT_WATCH,
    WILDMATCH_GLOB,
    WILDMATCH_MULTISLASH,
    GLOB_GENERATOR,
    CLOCK_SYNC_TIMEOUT
  }

  public static final String NULL_CLOCK = "c:0:0";

  private static final int WATCHMAN_CLOCK_SYNC_TIMEOUT = 100;
  static final ImmutableSet<String> REQUIRED_CAPABILITIES = ImmutableSet.of("cmd-watch-project");

  static final ImmutableMap<String, Capability> ALL_CAPABILITIES =
      ImmutableMap.<String, Capability>builder()
          .put("term-dirname", Capability.DIRNAME)
          .put("cmd-watch-project", Capability.SUPPORTS_PROJECT_WATCH)
          .put("wildmatch", Capability.WILDMATCH_GLOB)
          .put("wildmatch_multislash", Capability.WILDMATCH_MULTISLASH)
          .put("glob_generator", Capability.GLOB_GENERATOR)
          .put("clock-sync-timeout", Capability.CLOCK_SYNC_TIMEOUT)
          .build();

  private static final Logger LOG = Logger.get(Watchman.class);

  private static final long POLL_TIME_NANOS = TimeUnit.SECONDS.toNanos(1);
  // Crawling a large repo in `watch-project` might take a long time on a slow disk.
  private static final long DEFAULT_COMMAND_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(45);

  static final Path WATCHMAN = Paths.get("watchman");
  public static final Watchman NULL_WATCHMAN =
      new Watchman(
          ImmutableMap.of(),
          ImmutableSet.of(),
          ImmutableMap.of(),
          Optional.empty(),
          Optional.empty());

  private final ImmutableMap<Path, ProjectWatch> projectWatches;
  private final ImmutableSet<Capability> capabilities;
  private final Optional<Path> transportPath;
  private final Optional<WatchmanClient> watchmanClient;
  private final ImmutableMap<String, String> clockIds;

  public static Watchman build(
      ImmutableSet<Path> projectWatchList,
      ImmutableMap<String, String> env,
      Console console,
      Clock clock,
      Optional<Long> commandTimeoutMillis)
      throws InterruptedException {
    return build(
        new ListeningProcessExecutor(),
        localWatchmanConnector(console, clock),
        projectWatchList,
        env,
        new ExecutableFinder(),
        console,
        clock,
        commandTimeoutMillis);
  }

  @VisibleForTesting
  @SuppressWarnings("PMD.PrematureDeclaration")
  static Watchman build(
      ListeningProcessExecutor executor,
      Function<Path, Optional<WatchmanClient>> watchmanConnector,
      ImmutableSet<Path> projectWatchList,
      ImmutableMap<String, String> env,
      ExecutableFinder exeFinder,
      Console console,
      Clock clock,
      Optional<Long> commandTimeoutMillis)
      throws InterruptedException {
    LOG.info("Creating for: " + projectWatchList);
    Optional<WatchmanClient> watchmanClient = Optional.empty();
    try {
      Path watchmanPath = exeFinder.getExecutable(WATCHMAN, env).toAbsolutePath();
      Optional<? extends Map<String, ?>> result;

      long timeoutMillis = commandTimeoutMillis.orElse(DEFAULT_COMMAND_TIMEOUT_MILLIS);
      long endTimeNanos = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
      result =
          execute(
              executor,
              console,
              clock,
              timeoutMillis,
              TimeUnit.MILLISECONDS.toNanos(timeoutMillis),
              watchmanPath,
              "get-sockname");

      if (!result.isPresent()) {
        return NULL_WATCHMAN;
      }

      String rawSockname = (String) result.get().get("sockname");
      if (rawSockname == null) {
        return NULL_WATCHMAN;
      }
      Path transportPath = Paths.get(rawSockname);

      LOG.info(
          "Connecting to Watchman version %s at %s", result.get().get("version"), transportPath);
      watchmanClient = watchmanConnector.apply(transportPath);
      if (!watchmanClient.isPresent()) {
        LOG.warn("Could not connect to Watchman, disabling.");
        return NULL_WATCHMAN;
      }
      LOG.debug("Connected to Watchman");

      long versionQueryStartTimeNanos = clock.nanoTime();
      result =
          watchmanClient
              .get()
              .queryWithTimeout(
                  endTimeNanos - versionQueryStartTimeNanos,
                  "version",
                  ImmutableMap.of(
                      "required", REQUIRED_CAPABILITIES, "optional", ALL_CAPABILITIES.keySet()));

      LOG.info(
          "Took %d ms to query capabilities %s",
          TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - versionQueryStartTimeNanos),
          ALL_CAPABILITIES);

      if (!result.isPresent()) {
        LOG.warn("Could not get version response from Watchman, disabling Watchman");
        watchmanClient.get().close();
        return NULL_WATCHMAN;
      }

      ImmutableSet.Builder<Capability> capabilitiesBuilder = ImmutableSet.builder();
      if (!extractCapabilities(result.get(), capabilitiesBuilder)) {
        LOG.warn("Could not extract capabilities, disabling Watchman");
        watchmanClient.get().close();
        return NULL_WATCHMAN;
      }
      ImmutableSet<Capability> capabilities = capabilitiesBuilder.build();
      LOG.debug("Got Watchman capabilities: %s", capabilities);

      ImmutableMap.Builder<Path, ProjectWatch> projectWatchesBuilder = ImmutableMap.builder();
      for (Path projectRoot : projectWatchList) {
        Optional<ProjectWatch> projectWatch =
            queryWatchProject(
                watchmanClient.get(), projectRoot, clock, endTimeNanos - clock.nanoTime());
        if (!projectWatch.isPresent()) {
          watchmanClient.get().close();
          return NULL_WATCHMAN;
        }
        projectWatchesBuilder.put(projectRoot, projectWatch.get());
      }
      ImmutableMap<Path, ProjectWatch> projectWatches = projectWatchesBuilder.build();
      Iterable<String> watchRoots =
          RichStream.from(projectWatches.values())
              .map(ProjectWatch::getWatchRoot)
              .distinct()
              .toOnceIterable();

      ImmutableMap.Builder<String, String> clockIdsBuilder = ImmutableMap.builder();
      for (String watchRoot : watchRoots) {
        Optional<String> clockId =
            queryClock(
                watchmanClient.get(),
                watchRoot,
                capabilities,
                clock,
                endTimeNanos - clock.nanoTime());
        if (clockId.isPresent()) {
          clockIdsBuilder.put(watchRoot, clockId.get());
        }
      }

      return new Watchman(
          projectWatches,
          capabilities,
          clockIdsBuilder.build(),
          Optional.of(transportPath),
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

  @SuppressWarnings("unchecked")
  private static boolean extractCapabilities(
      Map<String, ?> versionResponse, ImmutableSet.Builder<Capability> capabilitiesBuilder) {
    if (versionResponse.containsKey("error")) {
      LOG.warn("Error in watchman output: %s", versionResponse.get("error"));
      return false;
    }

    if (versionResponse.containsKey("warning")) {
      LOG.warn("Warning in watchman output: %s", versionResponse.get("warning"));
      // Warnings are not fatal. Don't panic.
    }

    Object capabilitiesResponse = versionResponse.get("capabilities");
    if (!(capabilitiesResponse instanceof Map<?, ?>)) {
      LOG.warn("capabilities response is not map, got %s", capabilitiesResponse);
      return false;
    }

    LOG.debug("Got capabilities response: %s", capabilitiesResponse);

    Map<String, Boolean> capabilities = (Map<String, Boolean>) capabilitiesResponse;
    for (Map.Entry<String, Boolean> capabilityEntry : capabilities.entrySet()) {
      Capability capability = ALL_CAPABILITIES.get(capabilityEntry.getKey());
      if (capability == null) {
        LOG.warn("Unexpected capability in response: %s", capabilityEntry.getKey());
        return false;
      }
      if (capabilityEntry.getValue()) {
        capabilitiesBuilder.add(capability);
      }
    }
    return true;
  }

  /**
   * Requests watchman watch a project directory Executes the underlying watchman query: {@code
   * watchman watch-project <rootPath>}
   *
   * @param watchmanClient to use for the query
   * @param rootPath path to the root of the watch-project
   * @param clock used to compute timeouts and statistics
   * @param timeoutNanos for the watchman query
   * @return If successful, a {@link ProjectWatch} instance containing the root of the watchman
   *     watch, and relative path from the root to {@code rootPath}
   */
  private static Optional<ProjectWatch> queryWatchProject(
      WatchmanClient watchmanClient, Path rootPath, Clock clock, long timeoutNanos)
      throws IOException, InterruptedException {
    Path absoluteRootPath = rootPath.toAbsolutePath();
    LOG.info("Adding watchman root: %s", absoluteRootPath);

    long projectWatchTimeNanos = clock.nanoTime();
    watchmanClient.queryWithTimeout(timeoutNanos, "watch-project", absoluteRootPath.toString());

    // TODO(mzlee): There is a bug in watchman (that will be fixed
    // in a later watchman release) where watch-project returns
    // before the crawl is finished which causes the next
    // interaction to block. Calling watch-project a second time
    // properly attributes where we are spending time.
    Optional<? extends Map<String, ?>> result =
        watchmanClient.queryWithTimeout(
            timeoutNanos - (clock.nanoTime() - projectWatchTimeNanos),
            "watch-project",
            absoluteRootPath.toString());
    LOG.info(
        "Took %d ms to add root %s",
        TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - projectWatchTimeNanos), absoluteRootPath);

    if (!result.isPresent()) {
      return Optional.empty();
    }

    Map<String, ?> map = result.get();
    if (map.containsKey("error")) {
      LOG.warn("Error in watchman output: %s", map.get("error"));
      return Optional.empty();
    }

    if (map.containsKey("warning")) {
      LOG.warn("Warning in watchman output: %s", map.get("warning"));
      // Warnings are not fatal. Don't panic.
    }

    if (!map.containsKey("watch")) {
      return Optional.empty();
    }

    String watchRoot = (String) map.get("watch");
    Optional<String> watchPrefix = Optional.ofNullable((String) map.get("relative_path"));
    return Optional.of(ProjectWatch.of(watchRoot, watchPrefix));
  }

  /**
   * Queries for the watchman clock-id Executes the underlying watchman query: {@code watchman clock
   * (sync_timeout: 100)}
   *
   * @param watchmanClient to use for the query
   * @param watchRoot path to the root of the watch-project
   * @param clock used to compute timeouts and statistics
   * @param timeoutNanos for the watchman query
   * @return If successful, a {@link String} containing the watchman clock id
   */
  private static Optional<String> queryClock(
      WatchmanClient watchmanClient,
      String watchRoot,
      ImmutableSet<Capability> capabilities,
      Clock clock,
      long timeoutNanos)
      throws IOException, InterruptedException {
    Optional<String> clockId = Optional.empty();
    long clockStartTimeNanos = clock.nanoTime();
    ImmutableMap<String, Object> args =
        capabilities.contains(Capability.CLOCK_SYNC_TIMEOUT)
            ? ImmutableMap.of("sync_timeout", WATCHMAN_CLOCK_SYNC_TIMEOUT)
            : ImmutableMap.of();

    Optional<? extends Map<String, ?>> result =
        watchmanClient.queryWithTimeout(timeoutNanos, "clock", watchRoot, args);
    if (result.isPresent()) {
      Map<String, ?> clockResult = result.get();
      clockId = Optional.ofNullable((String) clockResult.get("clock"));
    }
    if (clockId.isPresent()) {
      Map<String, ?> map = result.get();
      clockId = Optional.ofNullable((String) map.get("clock"));
      LOG.info(
          "Took %d ms to query for initial clock id %s",
          TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - clockStartTimeNanos), clockId);
    } else {
      LOG.warn(
          "Took %d ms but could not get an initial clock id. Falling back to a named cursor",
          TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - clockStartTimeNanos));
    }

    return clockId;
  }

  @SuppressWarnings("unchecked")
  private static Optional<Map<String, Object>> execute(
      ListeningProcessExecutor executor,
      Console console,
      Clock clock,
      long commandTimeoutMillis,
      long timeoutNanos,
      Path watchmanPath,
      String... args)
      throws InterruptedException, IOException {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    ForwardingProcessListener listener =
        new ForwardingProcessListener(Channels.newChannel(stdout), Channels.newChannel(stderr));
    ListeningProcessExecutor.LaunchedProcess process =
        executor.launchProcess(
            ProcessExecutorParams.builder()
                .addCommand(watchmanPath.toString(), "--output-encoding=bser")
                .addCommand(args)
                .build(),
            listener);

    long startTimeNanos = clock.nanoTime();
    int exitCode =
        executor.waitForProcess(
            process, Math.min(timeoutNanos, POLL_TIME_NANOS), TimeUnit.NANOSECONDS);
    if (exitCode == Integer.MIN_VALUE) {
      // Let the user know we're still here waiting for Watchman, then wait the
      // rest of the timeout period.
      long remainingNanos = timeoutNanos - (clock.nanoTime() - startTimeNanos);
      if (remainingNanos > 0) {
        console
            .getStdErr()
            .getRawStream()
            .format("Waiting for Watchman command [%s]...\n", Joiner.on(" ").join(args));
        exitCode = executor.waitForProcess(process, remainingNanos, TimeUnit.NANOSECONDS);
      }
    }
    LOG.debug(
        "Waited %d ms for Watchman command %s, exit code %d",
        TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startTimeNanos),
        Joiner.on(" ").join(args),
        exitCode);
    if (exitCode == Integer.MIN_VALUE) {
      LOG.warn("Watchman did not respond within %d ms, disabling.", commandTimeoutMillis);
      console
          .getStdErr()
          .getRawStream()
          .format(
              "Timed out after %d ms waiting for Watchman command [%s]. Disabling Watchman.\n",
              commandTimeoutMillis, Joiner.on(" ").join(args));
      return Optional.empty();
    }
    if (exitCode != 0) {
      LOG.error("Watchman's stderr: %s", new String(stderr.toByteArray(), Charsets.UTF_8));
      LOG.error("Error %d executing %s", exitCode, Joiner.on(" ").join(args));
      return Optional.empty();
    }

    Object response =
        new BserDeserializer(BserDeserializer.KeyOrdering.UNSORTED)
            .deserializeBserValue(new ByteArrayInputStream(stdout.toByteArray()));
    LOG.debug("stdout of command: " + response);
    if (!(response instanceof Map<?, ?>)) {
      LOG.error("Unexpected response from Watchman: %s", response);
      return Optional.empty();
    }
    return Optional.of((Map<String, Object>) response);
  }

  @VisibleForTesting
  static Function<Path, Optional<WatchmanClient>> localWatchmanConnector(
      final Console console, final Clock clock) {
    return new Function<Path, Optional<WatchmanClient>>() {
      @Override
      public Optional<WatchmanClient> apply(Path transportPath) {
        try {
          return Optional.of(
              new WatchmanTransportClient(
                  console, clock, createLocalWatchmanTransport(transportPath)));
        } catch (IOException e) {
          LOG.warn(e, "Could not connect to Watchman at path %s", transportPath);
          return Optional.empty();
        }
      }

      private Transport createLocalWatchmanTransport(Path transportPath) throws IOException {
        if (Platform.detect() == Platform.WINDOWS) {
          return WindowsNamedPipe.createPipeWithPath(transportPath.toString());
        } else {
          return UnixDomainSocket.createSocketWithPath(transportPath);
        }
      }
    };
  }

  public ImmutableMap<Path, WatchmanCursor> buildClockWatchmanCursorMap() {
    ImmutableMap.Builder<Path, WatchmanCursor> cursorBuilder = ImmutableMap.builder();
    for (Map.Entry<Path, ProjectWatch> entry : projectWatches.entrySet()) {
      String clockId = clockIds.get(entry.getValue().getWatchRoot());
      Preconditions.checkNotNull(
          clockId, "No ClockId found for watch root %s", entry.getValue().getWatchRoot());
      cursorBuilder.put(entry.getKey(), new WatchmanCursor(clockId));
    }
    return cursorBuilder.build();
  }

  public ImmutableMap<Path, WatchmanCursor> buildNamedWatchmanCursorMap() {
    ImmutableMap.Builder<Path, WatchmanCursor> cursorBuilder = ImmutableMap.builder();
    for (Path cellPath : projectWatches.keySet()) {
      cursorBuilder.put(
          cellPath,
          new WatchmanCursor(new StringBuilder("n:buckd").append(UUID.randomUUID()).toString()));
    }
    return cursorBuilder.build();
  }

  // TODO(beng): Split the metadata out into an immutable value type and pass
  // the WatchmanClient separately.
  @VisibleForTesting
  public Watchman(
      ImmutableMap<Path, ProjectWatch> projectWatches,
      ImmutableSet<Capability> capabilities,
      ImmutableMap<String, String> clockIds,
      Optional<Path> transportPath,
      Optional<WatchmanClient> watchmanClient) {
    this.projectWatches = projectWatches;
    this.capabilities = capabilities;
    this.clockIds = clockIds;
    this.transportPath = transportPath;
    this.watchmanClient = watchmanClient;
  }

  public ImmutableMap<Path, ProjectWatch> getProjectWatches() {
    return projectWatches;
  }

  public ImmutableSet<Capability> getCapabilities() {
    return capabilities;
  }

  public ImmutableMap<String, String> getClockIds() {
    return clockIds;
  }

  public boolean hasWildmatchGlob() {
    return capabilities.contains(Capability.WILDMATCH_GLOB);
  }

  public Optional<Path> getTransportPath() {
    return transportPath;
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
