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

package com.facebook.buck.io.watchman;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.console.EventConsole;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.unixsocket.UnixDomainSocket;
import com.facebook.buck.io.windowspipe.WindowsNamedPipe;
import com.facebook.buck.util.ForwardingProcessListener;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.bser.BserDeserializer;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.types.Either;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** Factory that is responsible for creating instances of {@link Watchman}. */
public class WatchmanFactory {

  public static final String NULL_CLOCK = "c:0:0";

  /**
   * Watchman implementation which always returns error.
   *
   * <p>Used when watchman cannot be initialized, and in tests.
   */
  public static class NullWatchman extends Watchman {
    public final String reason;

    public NullWatchman(String reason) {
      super(
          /* projectWatches */ ImmutableMap.of(),
          /* capabilities */ ImmutableSet.of(),
          /* clockIds */ ImmutableMap.of(),
          /* transportPath */ Optional.empty(),
          /* version */ "");
      this.reason = reason;
    }

    @Override
    public WatchmanClient createClient() throws IOException {
      throw new IOException("NullWatchman cannot create a WatchmanClient: " + reason);
    }
  }

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
  private static final int DEFAULT_WATCHMAN_CLOCK_SYNC_TIMEOUT_MS =
      (int) TimeUnit.SECONDS.toMillis(60);
  private static final Logger LOG = Logger.get(WatchmanFactory.class);
  private static final long POLL_TIME_NANOS = TimeUnit.SECONDS.toNanos(1);
  private static final long WARN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(1);
  // Crawling a large repo in `watch-project` might take a long time on a slow disk.
  private static final long DEFAULT_COMMAND_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);
  private final InitialWatchmanClientFactory initialWatchmanClientFactory;

  /** Exists to allow us to inject behavior in unit tests. */
  interface InitialWatchmanClientFactory {
    /**
     * This is used to create a {@link WatchmanClient} after the socket path to Watchman has been
     * found. This client will be passed to {@link NullWatchman} to create a {@link Watchman} object
     * that reflects the capabilities, clock ids, etc. of the Watchman instance that is running
     * locally.
     */
    WatchmanClient tryCreateClientToFetchInitialWatchmanData(
        Path socketPath, EventConsole console, Clock clock) throws IOException;
  }

  public WatchmanFactory() {
    this(WatchmanFactory::createWatchmanClient);
  }

  public WatchmanFactory(InitialWatchmanClientFactory factory) {
    this.initialWatchmanClientFactory = factory;
  }

  /** @return new instance of {@link Watchman} using the specified params. */
  public Watchman build(
      ImmutableSet<AbsPath> projectWatchList,
      ImmutableMap<String, String> env,
      EventConsole console,
      Clock clock,
      Optional<Long> commandTimeoutMillis,
      Optional<Integer> syncTimeoutMillis)
      throws InterruptedException {
    return build(
        new ListeningProcessExecutor(),
        projectWatchList,
        env,
        new ExecutableFinder(),
        console,
        clock,
        commandTimeoutMillis,
        syncTimeoutMillis);
  }

  @VisibleForTesting
  @SuppressWarnings("PMD.PrematureDeclaration") // endTimeNanos
  Watchman build(
      ListeningProcessExecutor executor,
      ImmutableSet<AbsPath> projectWatchList,
      ImmutableMap<String, String> env,
      ExecutableFinder exeFinder,
      EventConsole console,
      Clock clock,
      Optional<Long> commandTimeoutMillis,
      Optional<Integer> syncTimeoutMillis)
      throws InterruptedException {
    LOG.info("Creating for: " + projectWatchList);
    Path pathToTransport;
    long timeoutMillis = commandTimeoutMillis.orElse(DEFAULT_COMMAND_TIMEOUT_MILLIS);
    long endTimeNanos = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    try {
      Either<Path, NullWatchman> pathToTransportOpt =
          determineWatchmanTransportPath(executor, env, exeFinder, console, clock, timeoutMillis);
      if (pathToTransportOpt.isLeft()) {
        pathToTransport = pathToTransportOpt.getLeft();
      } else {
        return returnNullWatchman(console, pathToTransportOpt.getRight().reason, null);
      }
    } catch (IOException e) {
      return returnNullWatchman(console, "Unable to determine the version of watchman", e);
    }

    try (WatchmanClient client =
        initialWatchmanClientFactory.tryCreateClientToFetchInitialWatchmanData(
            pathToTransport, console, clock)) {
      int effectiveSyncTimeoutMillis =
          syncTimeoutMillis.orElse(DEFAULT_WATCHMAN_CLOCK_SYNC_TIMEOUT_MS);
      Watchman watchman =
          getWatchman(
              client,
              pathToTransport,
              projectWatchList,
              console,
              clock,
              endTimeNanos,
              effectiveSyncTimeoutMillis);
      LOG.debug("Connected to Watchman");
      return watchman;
    } catch (ClassCastException | IOException e) {
      return returnNullWatchman(console, "Unable to determine the version of watchman", e);
    }
  }

  private static Either<Path, NullWatchman> determineWatchmanTransportPath(
      ListeningProcessExecutor executor,
      ImmutableMap<String, String> env,
      ExecutableFinder exeFinder,
      EventConsole console,
      Clock clock,
      long timeoutMillis)
      throws IOException, InterruptedException {
    String watchmanSockFromEnv = env.get("WATCHMAN_SOCK");
    if (watchmanSockFromEnv != null) {
      LOG.info("WATCHMAN_SOCK set in env, using %s", watchmanSockFromEnv);
      return Either.ofLeft(Paths.get(watchmanSockFromEnv));
    }

    Optional<Path> watchmanPathOpt = exeFinder.getOptionalExecutable(WATCHMAN, env);
    if (!watchmanPathOpt.isPresent()) {
      return Either.ofRight(new NullWatchman(String.format("%s executable not found", WATCHMAN)));
    }

    Path watchmanPath = watchmanPathOpt.get().toAbsolutePath();
    Either<Map<String, Object>, NullWatchman> result =
        execute(
            executor,
            console,
            clock,
            timeoutMillis,
            TimeUnit.MILLISECONDS.toNanos(timeoutMillis),
            watchmanPath,
            "get-sockname");

    if (!result.isLeft()) {
      return Either.ofRight(result.getRight());
    }

    String rawSockname = (String) result.getLeft().get("sockname");
    if (rawSockname == null) {
      return Either.ofRight(new NullWatchman("watchman get-sockname response has no sockname"));
    }

    Path transportPath = Paths.get(rawSockname);

    LOG.info(
        "Connecting to Watchman version %s at %s", result.getLeft().get("version"), transportPath);
    return Either.ofLeft(transportPath);
  }

  /** Query Watchman's capabilities and watch the given directories. */
  @VisibleForTesting
  public static Watchman getWatchman(
      WatchmanClient client,
      Path transportPath,
      ImmutableSet<AbsPath> projectWatchList,
      EventConsole console,
      Clock clock,
      long endTimeNanos,
      int clockSyncTimeoutMillis)
      throws IOException, InterruptedException {
    // Must be nonzero, otherwise no sync happens
    Preconditions.checkArgument(clockSyncTimeoutMillis > 0, "Clock sync timeout must be positive");

    long versionQueryStartTimeNanos = clock.nanoTime();
    Either<Map<String, Object>, WatchmanClient.Timeout> result;
    try {
      result =
          client.queryWithTimeout(
              endTimeNanos - versionQueryStartTimeNanos,
              WARN_TIMEOUT_NANOS,
              WatchmanQuery.version(
                  REQUIRED_CAPABILITIES.asList(), ALL_CAPABILITIES.keySet().asList()));
    } catch (WatchmanQueryFailedException e) {
      return returnNullWatchman(console, "Could not get version from watchman", e);
    }

    LOG.info(
        "Took %d ms to query capabilities %s",
        TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - versionQueryStartTimeNanos),
        ALL_CAPABILITIES);

    if (!result.isLeft()) {
      return returnNullWatchman(console, "Could not get version response from Watchman", null);
    }
    Object versionRaw = result.getLeft().get("version");
    if (!(versionRaw instanceof String)) {
      return returnNullWatchman(console, "Unexpected version format", null);
    }
    String version = (String) versionRaw;
    ImmutableSet.Builder<Capability> capabilitiesBuilder = ImmutableSet.builder();
    if (!extractCapabilities(result.getLeft(), capabilitiesBuilder)) {
      return returnNullWatchman(console, "Could not extract capabilities", null);
    }
    ImmutableSet<Capability> capabilities = capabilitiesBuilder.build();
    LOG.debug("Got Watchman capabilities: %s", capabilities);

    ImmutableMap.Builder<AbsPath, ProjectWatch> projectWatchesBuilder = ImmutableMap.builder();
    for (AbsPath projectRoot : projectWatchList) {
      Optional<ProjectWatch> projectWatch =
          queryWatchProject(client, projectRoot, clock, endTimeNanos - clock.nanoTime());
      if (!projectWatch.isPresent()) {
        return returnNullWatchman(console, "watch-project failed", null);
      }
      projectWatchesBuilder.put(projectRoot, projectWatch.get());
    }
    ImmutableMap<AbsPath, ProjectWatch> projectWatches = projectWatchesBuilder.build();
    Iterable<String> watchRoots =
        RichStream.from(projectWatches.values())
            .map(ProjectWatch::getWatchRoot)
            .distinct()
            .toOnceIterable();

    ImmutableMap.Builder<String, String> clockIdsBuilder = ImmutableMap.builder();
    for (String watchRoot : watchRoots) {
      Optional<String> clockId;
      try {
        clockId =
            queryClock(
                client,
                watchRoot,
                capabilities,
                clock,
                endTimeNanos - clock.nanoTime(),
                clockSyncTimeoutMillis);
      } catch (WatchmanQueryFailedException e) {
        return returnNullWatchman(console, "watchman clock query failed", e);
      }
      if (clockId.isPresent()) {
        clockIdsBuilder.put(watchRoot, clockId.get());
      } else {
        return returnNullWatchman(console, "watchman clock query timed out", null);
      }
    }

    return new Watchman(
        projectWatches,
        capabilities,
        clockIdsBuilder.build(),
        Optional.of(transportPath),
        version) {
      @Override
      public WatchmanClient createClient() throws IOException {
        return createWatchmanClient(transportPath, console, clock);
      }
    };
  }

  private static Watchman returnNullWatchman(
      EventConsole console, String reason, @Nullable Throwable e) {
    if (e != null) {
      LOG.warn(e, "%s, disabling watchman.", reason);
    } else {
      LOG.warn("%s, disabling watchman", reason);
    }
    // Unavailable watchman is an important event,
    // so print that to the console, not just log it.
    console.err(String.format("%s, disabling watchman", reason));
    return new NullWatchman(reason);
  }

  @VisibleForTesting
  public static WatchmanClient createWatchmanClient(
      Path transportPath, EventConsole console, Clock clock) throws IOException {
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
      WatchmanClient watchmanClient, AbsPath rootPath, Clock clock, long timeoutNanos)
      throws IOException, InterruptedException {
    LOG.info("Adding watchman root: %s", rootPath);

    long projectWatchTimeNanos = clock.nanoTime();
    Either<Map<String, Object>, WatchmanClient.Timeout> result;
    try {
      result =
          watchmanClient.queryWithTimeout(
              timeoutNanos, WARN_TIMEOUT_NANOS, WatchmanQuery.watchProject(rootPath.toString()));
    } catch (WatchmanQueryFailedException e) {
      LOG.warn(e, "Error in watchman output");
      return Optional.empty();
    }

    LOG.info(
        "Took %d ms to add root %s",
        TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - projectWatchTimeNanos), rootPath);

    if (!result.isLeft()) {
      return Optional.empty();
    }

    Map<String, ?> map = result.getLeft();

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
      long timeoutNanos,
      int syncTimeoutMilis)
      throws IOException, InterruptedException, WatchmanQueryFailedException {
    Preconditions.checkState(
        capabilities.contains(Capability.CLOCK_SYNC_TIMEOUT),
        "watchman capabilities must include %s, which is available in watchman since 3.9",
        Capability.CLOCK_SYNC_TIMEOUT);

    Optional<String> clockId = Optional.empty();
    long clockStartTimeNanos = clock.nanoTime();

    Either<Map<String, Object>, WatchmanClient.Timeout> result =
        watchmanClient.queryWithTimeout(
            timeoutNanos,
            WARN_TIMEOUT_NANOS,
            WatchmanQuery.clock(watchRoot, Optional.of(syncTimeoutMilis)));
    if (result.isLeft()) {
      Map<String, ?> clockResult = result.getLeft();
      clockId = Optional.ofNullable((String) clockResult.get("clock"));
    }
    if (clockId.isPresent()) {
      Map<String, ?> map = result.getLeft();
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
  private static Either<Map<String, Object>, NullWatchman> execute(
      ListeningProcessExecutor executor,
      EventConsole console,
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
        console.warn("Waiting for watchman...");
        exitCode = executor.waitForProcess(process, remainingNanos, TimeUnit.NANOSECONDS);
      }
    }
    LOG.debug(
        "Waited %d ms for Watchman command %s, exit code %d",
        TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startTimeNanos),
        Joiner.on(" ").join(args),
        exitCode);
    if (exitCode == Integer.MIN_VALUE) {
      String message =
          String.format("Watchman did not respond within %d ms, disabling.", commandTimeoutMillis);
      LOG.warn(message);
      console.warn(
          String.format(
              "Timed out after %d ms waiting for Watchman command [%s]. Disabling Watchman.",
              commandTimeoutMillis, Joiner.on(" ").join(args)));
      return Either.ofRight(new NullWatchman(message));
    }
    if (exitCode != 0) {
      LOG.error("Watchman's stderr: %s", new String(stderr.toByteArray(), StandardCharsets.UTF_8));
      LOG.error("Error %d executing %s", exitCode, Joiner.on(" ").join(args));
      return Either.ofRight(
          new NullWatchman(String.format("watchman get-sockname exited with code %s", exitCode)));
    }

    Object response =
        new BserDeserializer(BserDeserializer.KeyOrdering.UNSORTED)
            .deserializeBserValue(new ByteArrayInputStream(stdout.toByteArray()));
    LOG.debug("stdout of command: " + response);
    if (!(response instanceof Map<?, ?>)) {
      LOG.error("Unexpected response from Watchman: %s", response);
      return Either.ofRight(new NullWatchman("Unexpected response from watchman"));
    }
    return Either.ofLeft((Map<String, Object>) response);
  }
}
