/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.io.watchman;

import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.unixsocket.UnixDomainSocket;
import com.facebook.buck.io.windowspipe.WindowsNamedPipe;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ForwardingProcessListener;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.bser.BserDeserializer;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Factory that is responsible for creating instances of {@link Watchman}. */
public class WatchmanFactory {

  public static final String NULL_CLOCK = "c:0:0";
  public static final Watchman NULL_WATCHMAN =
      new Watchman(
          /* projectWatches */ ImmutableMap.of(),
          /* capabilities */ ImmutableSet.of(),
          /* clockIds */ ImmutableMap.of(),
          /* transportPath */ Optional.empty()) {
        @Override
        public WatchmanClient createClient() throws IOException {
          throw new IOException("NULL_WATCHMAN cannot create a WatchmanClient.");
        }
      };
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
  static final Path WATCHMAN = Paths.get("watchman");
  private static final int WATCHMAN_CLOCK_SYNC_TIMEOUT = 100;
  private static final Logger LOG = Logger.get(WatchmanFactory.class);
  private static final long POLL_TIME_NANOS = TimeUnit.SECONDS.toNanos(1);
  // Crawling a large repo in `watch-project` might take a long time on a slow disk.
  private static final long DEFAULT_COMMAND_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(45);
  private final InitialWatchmanClientFactory initialWatchmanClientFactory;

  /** Exists to allow us to inject behavior in unit tests. */
  interface InitialWatchmanClientFactory {
    /**
     * This is used to create a {@link WatchmanClient} after the socket path to Watchman has been
     * found. This client will be passed to {@link #getWatchman} to create a {@link Watchman} object
     * that reflects the capabilities, clock ids, etc. of the Watchman instance that is running
     * locally.
     */
    WatchmanClient tryCreateClientToFetchInitialWatchmanData(
        Path socketPath, Console console, Clock clock) throws IOException;
  }

  public WatchmanFactory() {
    this((socketPath, console, clock) -> createWatchmanClient(socketPath, console, clock));
  }

  public WatchmanFactory(InitialWatchmanClientFactory factory) {
    this.initialWatchmanClientFactory = factory;
  }

  /** @return new instance of {@link Watchman} using the specified params. */
  public Watchman build(
      ImmutableSet<Path> projectWatchList,
      ImmutableMap<String, String> env,
      Console console,
      Clock clock,
      Optional<Long> commandTimeoutMillis)
      throws InterruptedException {
    return build(
        new ListeningProcessExecutor(),
        projectWatchList,
        env,
        new ExecutableFinder(),
        console,
        clock,
        commandTimeoutMillis);
  }

  @VisibleForTesting
  @SuppressWarnings("PMD.PrematureDeclaration") // endTimeNanos
  Watchman build(
      ListeningProcessExecutor executor,
      ImmutableSet<Path> projectWatchList,
      ImmutableMap<String, String> env,
      ExecutableFinder exeFinder,
      Console console,
      Clock clock,
      Optional<Long> commandTimeoutMillis)
      throws InterruptedException {
    LOG.info("Creating for: " + projectWatchList);
    Path pathToTransport;
    long timeoutMillis = commandTimeoutMillis.orElse(DEFAULT_COMMAND_TIMEOUT_MILLIS);
    long endTimeNanos = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    try {
      Optional<Path> pathToTransportOpt =
          determineWatchmanTransportPath(executor, env, exeFinder, console, clock, timeoutMillis);
      if (pathToTransportOpt.isPresent()) {
        pathToTransport = pathToTransportOpt.get();
      } else {
        LOG.warn("Could not determine the socket to talk to Watchman, disabling.");
        return NULL_WATCHMAN;
      }
    } catch (IOException e) {
      LOG.warn(e, "Unable to determine the version of watchman. Going without.");
      return NULL_WATCHMAN;
    }

    try (WatchmanClient client =
        initialWatchmanClientFactory.tryCreateClientToFetchInitialWatchmanData(
            pathToTransport, console, clock)) {
      Watchman watchman =
          getWatchman(client, pathToTransport, projectWatchList, console, clock, endTimeNanos);
      LOG.debug("Connected to Watchman");
      return watchman;
    } catch (ClassCastException | IOException e) {
      LOG.warn(e, "Unable to determine the version of watchman. Going without.");
      return NULL_WATCHMAN;
    }
  }

  private static Optional<Path> determineWatchmanTransportPath(
      ListeningProcessExecutor executor,
      ImmutableMap<String, String> env,
      ExecutableFinder exeFinder,
      Console console,
      Clock clock,
      long timeoutMillis)
      throws IOException, InterruptedException {
    Optional<Path> watchmanPathOpt = exeFinder.getOptionalExecutable(WATCHMAN, env);
    if (!watchmanPathOpt.isPresent()) {
      return Optional.empty();
    }

    Path watchmanPath = watchmanPathOpt.get().toAbsolutePath();
    Optional<? extends Map<String, ?>> result =
        execute(
            executor,
            console,
            clock,
            timeoutMillis,
            TimeUnit.MILLISECONDS.toNanos(timeoutMillis),
            watchmanPath,
            "get-sockname");

    if (!result.isPresent()) {
      return Optional.empty();
    }

    String rawSockname = (String) result.get().get("sockname");
    if (rawSockname == null) {
      return Optional.empty();
    }

    Path transportPath = Paths.get(rawSockname);
    LOG.info("Connecting to Watchman version %s at %s", result.get().get("version"), transportPath);
    return Optional.of(transportPath);
  }

  private static Watchman getWatchman(
      WatchmanClient client,
      Path transportPath,
      ImmutableSet<Path> projectWatchList,
      Console console,
      Clock clock,
      long endTimeNanos)
      throws IOException, InterruptedException {
    long versionQueryStartTimeNanos = clock.nanoTime();
    Optional<? extends Map<String, ?>> result =
        client.queryWithTimeout(
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
      return NULL_WATCHMAN;
    }

    ImmutableSet.Builder<Capability> capabilitiesBuilder = ImmutableSet.builder();
    if (!extractCapabilities(result.get(), capabilitiesBuilder)) {
      LOG.warn("Could not extract capabilities, disabling Watchman");
      return NULL_WATCHMAN;
    }
    ImmutableSet<Capability> capabilities = capabilitiesBuilder.build();
    LOG.debug("Got Watchman capabilities: %s", capabilities);

    ImmutableMap.Builder<Path, ProjectWatch> projectWatchesBuilder = ImmutableMap.builder();
    for (Path projectRoot : projectWatchList) {
      Optional<ProjectWatch> projectWatch =
          queryWatchProject(client, projectRoot, clock, endTimeNanos - clock.nanoTime());
      if (!projectWatch.isPresent()) {
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
          queryClock(client, watchRoot, capabilities, clock, endTimeNanos - clock.nanoTime());
      if (clockId.isPresent()) {
        clockIdsBuilder.put(watchRoot, clockId.get());
      }
    }

    return new Watchman(
        projectWatches, capabilities, clockIdsBuilder.build(), Optional.of(transportPath)) {
      @Override
      public WatchmanClient createClient() throws IOException {
        return createWatchmanClient(transportPath, console, clock);
      }
    };
  }

  @VisibleForTesting
  static Optional<WatchmanClient> tryCreateWatchmanClient(
      Path transportPath, Console console, Clock clock) {
    try {
      return Optional.of(createWatchmanClient(transportPath, console, clock));
    } catch (IOException e) {
      LOG.warn(e, "Could not connect to Watchman at path %s", transportPath);
      return Optional.empty();
    }
  }

  private static WatchmanClient createWatchmanClient(
      Path transportPath, Console console, Clock clock) throws IOException {
    return new WatchmanTransportClient(console, clock, createLocalWatchmanTransport(transportPath));
  }

  private static Transport createLocalWatchmanTransport(Path transportPath) throws IOException {
    if (Platform.detect() == Platform.WINDOWS) {
      return WindowsNamedPipe.createPipeWithPath(transportPath.toString());
    } else {
      return UnixDomainSocket.createSocketWithPath(transportPath);
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
   * @return If successful, a {@link com.facebook.buck.io.watchman.ProjectWatch} instance containing
   *     the root of the watchman watch, and relative path from the root to {@code rootPath}
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
    ForwardingProcessListener listener = new ForwardingProcessListener(stdout, stderr);
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
        console.getStdErr().getRawStream().format("Waiting for watchman...\n");
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
}
