/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static com.facebook.buck.core.cell.CellConfig.MalformedOverridesException;
import static com.facebook.buck.util.AnsiEnvironmentChecking.NAILGUN_STDERR_ISTTY_ENV;
import static com.facebook.buck.util.AnsiEnvironmentChecking.NAILGUN_STDOUT_ISTTY_ENV;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;

import com.facebook.buck.artifact_cache.ArtifactCaches;
import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.cli.exceptions.handlers.ExceptionHandlerRegistryFactory;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.resources.ResourcesConfig;
import com.facebook.buck.core.build.engine.cache.manager.BuildInfoStoreManager;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.cell.impl.LocalCellProviderFactory;
import com.facebook.buck.core.cell.name.RelativeCellName;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.exceptions.handler.ExceptionHandlerRegistry;
import com.facebook.buck.core.exceptions.handler.HumanReadableExceptionAugmentor;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphCache;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.knowntypes.DefaultKnownBuildRuleTypesFactory;
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypesFactory;
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypesProvider;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.counters.CounterRegistry;
import com.facebook.buck.counters.CounterRegistryImpl;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.BuckInitializationDurationEvent;
import com.facebook.buck.event.CacheStatsEvent;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.DaemonEvent;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.event.chrome_trace.ChromeTraceBuckConfig;
import com.facebook.buck.event.listener.AbstractConsoleEventBusListener;
import com.facebook.buck.event.listener.BuildTargetDurationListener;
import com.facebook.buck.event.listener.CacheRateStatsListener;
import com.facebook.buck.event.listener.ChromeTraceBuildListener;
import com.facebook.buck.event.listener.FileSerializationEventBusListener;
import com.facebook.buck.event.listener.JavaUtilsLoggingBuildListener;
import com.facebook.buck.event.listener.LoadBalancerEventsListener;
import com.facebook.buck.event.listener.LogUploaderListener;
import com.facebook.buck.event.listener.LoggingBuildListener;
import com.facebook.buck.event.listener.MachineReadableLoggerListener;
import com.facebook.buck.event.listener.ParserProfilerLoggerListener;
import com.facebook.buck.event.listener.ProgressEstimator;
import com.facebook.buck.event.listener.PublicAnnouncementManager;
import com.facebook.buck.event.listener.RuleKeyDiagnosticsListener;
import com.facebook.buck.event.listener.RuleKeyLoggerListener;
import com.facebook.buck.event.listener.SimpleConsoleEventBusListener;
import com.facebook.buck.event.listener.SuperConsoleConfig;
import com.facebook.buck.event.listener.SuperConsoleEventBusListener;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.AsynchronousDirectoryContentsCleaner;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.io.WatchmanDiagnosticEventListener;
import com.facebook.buck.io.WatchmanFactory;
import com.facebook.buck.io.WatchmanWatcher;
import com.facebook.buck.io.WatchmanWatcher.FreshInstanceAction;
import com.facebook.buck.io.WatchmanWatcherException;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.PathOrGlobMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.log.ConsoleHandlerState;
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.log.LogConfig;
import com.facebook.buck.log.Logger;
import com.facebook.buck.module.BuckModuleManager;
import com.facebook.buck.module.impl.BuckModuleJarHashProvider;
import com.facebook.buck.module.impl.DefaultBuckModuleManager;
import com.facebook.buck.parser.DefaultParser;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.TargetSpecResolver;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.keys.config.impl.ConfigRuleKeyConfigurationFactory;
import com.facebook.buck.sandbox.SandboxExecutionStrategyFactory;
import com.facebook.buck.sandbox.impl.PlatformSandboxExecutionStrategyFactory;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.test.TestConfig;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.toolchain.ToolchainProviderFactory;
import com.facebook.buck.toolchain.impl.DefaultToolchainProviderFactory;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.AnsiEnvironmentChecking;
import com.facebook.buck.util.BgProcessKiller;
import com.facebook.buck.util.BuckArgsMethods;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.CloseableWrapper;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.Libc;
import com.facebook.buck.util.PkillProcessManager;
import com.facebook.buck.util.PrintStreamProcessExecutorFactory;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.ThrowingCloseableWrapper;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.cache.InstrumentingCacheStatsTracker;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.config.RawConfig;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.environment.CommandMode;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.environment.NetworkInfo;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.network.MacIpv6BugWorkaround;
import com.facebook.buck.util.network.RemoteLogBuckConfig;
import com.facebook.buck.util.perf.PerfStatsTracking;
import com.facebook.buck.util.perf.ProcessTracker;
import com.facebook.buck.util.shutdown.NonReentrantSystemExit;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.timing.NanosAdjustedClock;
import com.facebook.buck.util.versioncontrol.DelegatingVersionControlCmdLineInterface;
import com.facebook.buck.util.versioncontrol.VersionControlBuckConfig;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.facebook.buck.versions.InstrumentedVersionedTargetGraphCache;
import com.facebook.buck.versions.VersionedTargetGraphCache;
import com.facebook.buck.worker.WorkerProcessPool;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.common.reflect.ClassPath;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.martiansoftware.nailgun.NGClientDisconnectReason;
import com.martiansoftware.nailgun.NGContext;
import com.martiansoftware.nailgun.NGListeningAddress;
import com.martiansoftware.nailgun.NGServer;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.immutables.value.Value;
import org.kohsuke.args4j.CmdLineException;
import org.pf4j.PluginManager;

public final class Main {

  /**
   * Force JNA to be initialized early to avoid deadlock race condition.
   *
   * <p>
   *
   * <p>See: https://github.com/java-native-access/jna/issues/652
   */
  public static final int JNA_POINTER_SIZE = Pointer.SIZE;

  private static final Optional<String> BUCKD_LAUNCH_TIME_NANOS =
      Optional.ofNullable(System.getProperty("buck.buckd_launch_time_nanos"));
  private static final String BUCK_BUILD_ID_ENV_VAR = "BUCK_BUILD_ID";

  private static final String BUCKD_COLOR_DEFAULT_ENV_VAR = "BUCKD_COLOR_DEFAULT";

  private static final Duration DAEMON_SLAYER_TIMEOUT = Duration.ofDays(1);

  private static final Duration SUPER_CONSOLE_REFRESH_RATE = Duration.ofMillis(100);

  private static final Duration HANG_DETECTOR_TIMEOUT = Duration.ofMinutes(5);

  /** Path to a directory of static content that should be served by the {@link WebServer}. */
  private static final int DISK_IO_STATS_TIMEOUT_SECONDS = 10;

  private static final int EXECUTOR_SERVICES_TIMEOUT_SECONDS = 60;
  private static final int EVENT_BUS_TIMEOUT_SECONDS = 15;
  private static final int COUNTER_AGGREGATOR_SERVICE_TIMEOUT_SECONDS = 20;

  private final InputStream stdIn;
  private final PrintStream stdOut;
  private final PrintStream stdErr;

  private final Architecture architecture;

  private static final Semaphore commandSemaphore = new Semaphore(1);
  private static volatile Optional<NGContext> commandSemaphoreNgClient = Optional.empty();

  private static final DaemonLifecycleManager daemonLifecycleManager = new DaemonLifecycleManager();

  // Ensure we only have one instance of this, so multiple trash cleaning
  // operations are serialized on one queue.
  private static final AsynchronousDirectoryContentsCleaner TRASH_CLEANER =
      new AsynchronousDirectoryContentsCleaner();

  private final Platform platform;

  private Console console;

  private Optional<NGContext> context;

  // Ignore changes to generated Xcode project files and editors' backup files
  // so we don't dump buckd caches on every command.
  private static final ImmutableSet<PathOrGlobMatcher> DEFAULT_IGNORE_GLOBS =
      ImmutableSet.of(
          new PathOrGlobMatcher("**/*.pbxproj"),
          new PathOrGlobMatcher("**/*.xcscheme"),
          new PathOrGlobMatcher("**/*.xcworkspacedata"),
          // Various editors' temporary files
          new PathOrGlobMatcher("**/*~"),
          // Emacs
          new PathOrGlobMatcher("**/#*#"),
          new PathOrGlobMatcher("**/.#*"),
          // Vim
          new PathOrGlobMatcher("**/*.swo"),
          new PathOrGlobMatcher("**/*.swp"),
          new PathOrGlobMatcher("**/*.swpx"),
          new PathOrGlobMatcher("**/*.un~"),
          new PathOrGlobMatcher("**/.netrhwist"),
          // Eclipse
          new PathOrGlobMatcher(".idea"),
          new PathOrGlobMatcher(".iml"),
          new PathOrGlobMatcher("**/*.pydevproject"),
          new PathOrGlobMatcher(".project"),
          new PathOrGlobMatcher(".metadata"),
          new PathOrGlobMatcher("**/*.tmp"),
          new PathOrGlobMatcher("**/*.bak"),
          new PathOrGlobMatcher("**/*~.nib"),
          new PathOrGlobMatcher(".classpath"),
          new PathOrGlobMatcher(".settings"),
          new PathOrGlobMatcher(".loadpath"),
          new PathOrGlobMatcher(".externalToolBuilders"),
          new PathOrGlobMatcher(".cproject"),
          new PathOrGlobMatcher(".buildpath"),
          // Mac OS temp files
          new PathOrGlobMatcher(".DS_Store"),
          new PathOrGlobMatcher(".AppleDouble"),
          new PathOrGlobMatcher(".LSOverride"),
          new PathOrGlobMatcher(".Spotlight-V100"),
          new PathOrGlobMatcher(".Trashes"),
          // Windows
          new PathOrGlobMatcher("$RECYCLE.BIN"),
          // Sublime
          new PathOrGlobMatcher(".*.sublime-workspace"));

  private static final Logger LOG = Logger.get(Main.class);

  private ExceptionHandlerRegistry<ExitCode> exceptionHandlerRegistry;

  private static boolean isSessionLeader;
  private static PluginManager pluginManager;
  private static BuckModuleManager moduleManager;

  @Nullable private static FileLock resourcesFileLock = null;

  private static final HangMonitor.AutoStartInstance HANG_MONITOR =
      new HangMonitor.AutoStartInstance(
          (input) -> {
            LOG.info(
                "No recent activity, dumping thread stacks (`tr , '\\n'` to decode): %s", input);
          },
          HANG_DETECTOR_TIMEOUT);

  private static final NonReentrantSystemExit NON_REENTRANT_SYSTEM_EXIT =
      new NonReentrantSystemExit();

  public interface KnownBuildRuleTypesFactoryFactory {
    KnownBuildRuleTypesFactory create(
        ProcessExecutor processExecutor,
        PluginManager pluginManager,
        SandboxExecutionStrategyFactory sandboxExecutionStrategyFactory);
  }

  private final KnownBuildRuleTypesFactoryFactory knownBuildRuleTypesFactoryFactory;

  private Optional<BuckConfig> parsedRootConfig = Optional.empty();

  static {
    MacIpv6BugWorkaround.apply();
  }

  /**
   * This constructor allows integration tests to add/remove/modify known build rules (aka
   * descriptions).
   */
  @VisibleForTesting
  public Main(
      PrintStream stdOut,
      PrintStream stdErr,
      InputStream stdIn,
      KnownBuildRuleTypesFactoryFactory knownBuildRuleTypesFactoryFactory,
      Optional<NGContext> context) {
    this.stdOut = stdOut;
    this.stdErr = stdErr;
    this.stdIn = stdIn;
    this.knownBuildRuleTypesFactoryFactory = knownBuildRuleTypesFactoryFactory;
    this.architecture = Architecture.detect();
    this.platform = Platform.detect();
    this.context = context;

    // Create default console to start outputting errors immediately, if any
    // console may be overridden with custom console later once we have enough information to
    // construct it
    this.console =
        new Console(
            Verbosity.STANDARD_INFORMATION,
            stdOut,
            stdErr,
            new Ansi(
                AnsiEnvironmentChecking.environmentSupportsAnsiEscapes(
                    platform, getClientEnvironment(context))));
  }

  @VisibleForTesting
  public Main(
      PrintStream stdOut, PrintStream stdErr, InputStream stdIn, Optional<NGContext> context) {
    this(stdOut, stdErr, stdIn, DefaultKnownBuildRuleTypesFactory::of, context);
  }

  /* Define all error handling surrounding main command */
  private void runMainThenExit(String[] args, long initTimestamp) {

    ExitCode exitCode = ExitCode.SUCCESS;

    try {
      installUncaughtExceptionHandler(context);

      Path projectRoot = Paths.get(".");
      BuildId buildId = getBuildId(context);

      // Only post an overflow event if Watchman indicates a fresh instance event
      // after our initial query.
      WatchmanWatcher.FreshInstanceAction watchmanFreshInstanceAction =
          daemonLifecycleManager.hasDaemon()
              ? WatchmanWatcher.FreshInstanceAction.POST_OVERFLOW_EVENT
              : WatchmanWatcher.FreshInstanceAction.NONE;

      // Get the client environment, either from this process or from the Nailgun context.
      ImmutableMap<String, String> clientEnvironment = getClientEnvironment(context);

      CommandMode commandMode = CommandMode.RELEASE;

      exitCode =
          runMainWithExitCode(
              buildId,
              projectRoot,
              clientEnvironment,
              commandMode,
              watchmanFreshInstanceAction,
              initTimestamp,
              ImmutableList.copyOf(args));
    } catch (Throwable t) {

      HumanReadableExceptionAugmentor augmentor;
      try {
        augmentor =
            new HumanReadableExceptionAugmentor(
                parsedRootConfig
                    .map(BuckConfig::getErrorMessageAugmentations)
                    .orElse(ImmutableMap.of()));
      } catch (HumanReadableException e) {
        console.printErrorText(e.getHumanReadableErrorMessage());
        augmentor = new HumanReadableExceptionAugmentor(ImmutableMap.of());
      }
      exceptionHandlerRegistry =
          ExceptionHandlerRegistryFactory.create(console, context, augmentor);
      exitCode = exceptionHandlerRegistry.handleException(t);
    } finally {
      LOG.debug("Done.");
      LogConfig.flushLogs();
      // Exit explicitly so that non-daemon threads (of which we use many) don't
      // keep the VM alive.
      System.exit(exitCode.getCode());
    }
  }

  private void setupLogging(
      CommandMode commandMode, BuckCommand command, ImmutableList<String> args) throws IOException {
    // Setup logging.
    if (commandMode.isLoggingEnabled()) {
      // Reset logging each time we run a command while daemonized.
      // This will cause us to write a new log per command.
      LOG.debug("Rotating log.");
      LogConfig.flushLogs();
      LogConfig.setupLogging(command.getLogConfig());

      if (LOG.isDebugEnabled()) {
        Long gitCommitTimestamp = Long.getLong("buck.git_commit_timestamp");
        String buildDateStr;
        if (gitCommitTimestamp == null) {
          buildDateStr = "(unknown)";
        } else {
          buildDateStr =
              new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
                  .format(new Date(TimeUnit.SECONDS.toMillis(gitCommitTimestamp)));
        }
        String buildRev = System.getProperty("buck.git_commit", "(unknown)");
        LOG.debug("Starting up (build date %s, rev %s), args: %s", buildDateStr, buildRev, args);
        LOG.debug("System properties: %s", System.getProperties());
      }
    }
  }

  private ImmutableMap<RelativeCellName, Path> getCellMapping(Path canonicalRootPath)
      throws IOException {
    return DefaultCellPathResolver.bootstrapPathMapping(
        canonicalRootPath, Configs.createDefaultConfig(canonicalRootPath));
  }

  private Config setupDefaultConfig(
      ImmutableMap<RelativeCellName, Path> cellMapping, BuckCommand command) throws IOException {
    Path rootPath = cellMapping.get(RelativeCellName.ROOT_CELL_NAME);
    Preconditions.checkNotNull(rootPath, "Root cell should be implicitly added");
    RawConfig rootCellConfigOverrides;

    try {
      ImmutableMap<Path, RawConfig> overridesByPath =
          command.getConfigOverrides().getOverridesByPath(cellMapping);
      rootCellConfigOverrides =
          Optional.ofNullable(overridesByPath.get(rootPath)).orElse(RawConfig.of());
    } catch (MalformedOverridesException exception) {
      rootCellConfigOverrides =
          command.getConfigOverrides().getForCell(RelativeCellName.ROOT_CELL_NAME);
    }
    return Configs.createDefaultConfig(rootPath, rootCellConfigOverrides);
  }

  private ImmutableSet<Path> getProjectWatchList(
      Path canonicalRootPath, BuckConfig buckConfig, DefaultCellPathResolver cellPathResolver) {
    return ImmutableSet.<Path>builder()
        .add(canonicalRootPath)
        .addAll(
            buckConfig.getView(ParserConfig.class).getWatchCells()
                ? cellPathResolver.getPathMapping().values()
                : ImmutableList.of())
        .build();
  }

  private void checkJavaSpecificationVersions(BuckConfig buckConfig) {
    Optional<ImmutableList<String>> allowedJavaSpecificationVersions =
        buckConfig.getAllowedJavaSpecificationVersions();
    if (allowedJavaSpecificationVersions.isPresent()) {
      String specificationVersion = System.getProperty("java.specification.version");
      boolean javaSpecificationVersionIsAllowed =
          allowedJavaSpecificationVersions.get().contains(specificationVersion);
      if (!javaSpecificationVersionIsAllowed) {
        throw new HumanReadableException(
            "Current Java version '%s' is not in the allowed java specification versions:\n%s",
            specificationVersion, Joiner.on(", ").join(allowedJavaSpecificationVersions.get()));
      }
    }
  }

  /**
   * @param buildId an identifier for this command execution.
   * @param initTimestamp Value of System.nanoTime() when process got main()/nailMain() invoked.
   * @param unexpandedCommandLineArgs command line arguments
   * @return an ExitCode representing the result of the command
   */
  @SuppressWarnings("PMD.PrematureDeclaration")
  public ExitCode runMainWithExitCode(
      BuildId buildId,
      Path projectRoot,
      ImmutableMap<String, String> clientEnvironment,
      CommandMode commandMode,
      WatchmanWatcher.FreshInstanceAction watchmanFreshInstanceAction,
      long initTimestamp,
      ImmutableList<String> unexpandedCommandLineArgs)
      throws IOException, InterruptedException {

    // Set initial exitCode value to FATAL. This will eventually get reassigned unless an exception
    // happens
    ExitCode exitCode = ExitCode.FATAL_GENERIC;

    // Setup filesystem and buck config.
    Path canonicalRootPath = projectRoot.toRealPath().normalize();
    ImmutableMap<RelativeCellName, Path> rootCellMapping = getCellMapping(canonicalRootPath);
    ImmutableList<String> args =
        BuckArgsMethods.expandAtFiles(unexpandedCommandLineArgs, rootCellMapping);

    // Parse command line arguments
    BuckCommand command = new BuckCommand();
    // Parse the command line args.
    AdditionalOptionsCmdLineParser cmdLineParser = new AdditionalOptionsCmdLineParser(command);
    try {
      cmdLineParser.parseArgument(args);
    } catch (CmdLineException e) {
      throw new CommandLineException(e, e.getLocalizedMessage() + "\nFor help see 'buck --help'.");
    }

    // Return help strings fast if the command is a help request.
    Optional<ExitCode> result = command.runHelp(stdOut);
    if (result.isPresent()) {
      return result.get();
    }

    // If this command is not read only, acquire the command semaphore to become the only executing
    // read/write command. Early out will also help to not rotate log on each BUSY status which
    // happens in setupLogging().
    boolean shouldCleanUpTrash = false;
    try (CloseableWrapper<Semaphore> semaphore = getSemaphoreWrapper(command)) {
      if (!command.isReadOnly() && semaphore == null) {
        LOG.warn("Buck server was busy executing a command. Maybe retrying later will help.");
        return ExitCode.BUSY;
      }

      if (moduleManager == null) {
        pluginManager = BuckPluginManagerFactory.createPluginManager();
        moduleManager =
            new DefaultBuckModuleManager(pluginManager, new BuckModuleJarHashProvider());
      }

      // statically configure Buck logging environment based on Buck config, usually buck-x.log
      // files
      setupLogging(commandMode, command, args);

      Config config = setupDefaultConfig(rootCellMapping, command);

      ProjectFilesystemFactory projectFilesystemFactory = new DefaultProjectFilesystemFactory();
      ProjectFilesystem filesystem =
          projectFilesystemFactory.createProjectFilesystem(canonicalRootPath, config);

      DefaultCellPathResolver cellPathResolver =
          DefaultCellPathResolver.of(filesystem.getRootPath(), config);
      BuckConfig buckConfig =
          new BuckConfig(
              config, filesystem, architecture, platform, clientEnvironment, cellPathResolver);
      // Set so that we can use some settings when we print out messages to users
      parsedRootConfig = Optional.of(buckConfig);

      ImmutableSet<Path> projectWatchList =
          getProjectWatchList(canonicalRootPath, buckConfig, cellPathResolver);

      checkJavaSpecificationVersions(buckConfig);

      Verbosity verbosity = VerbosityParser.parse(args);

      // Setup the console.
      console = makeCustomConsole(context, verbosity, buckConfig);

      DistBuildConfig distBuildConfig = new DistBuildConfig(buckConfig);
      boolean isUsingDistributedBuild = false;

      ExecutionEnvironment executionEnvironment =
          new DefaultExecutionEnvironment(clientEnvironment, System.getProperties());

      // Automatically use distributed build for supported repositories and users.
      if (command.subcommand instanceof BuildCommand) {
        BuildCommand subcommand = (BuildCommand) command.subcommand;
        isUsingDistributedBuild = subcommand.isUsingDistributedBuild();
        if (!isUsingDistributedBuild
            && (distBuildConfig.shouldUseDistributedBuild(
                buildId, executionEnvironment.getUsername(), subcommand.getArguments()))) {
          isUsingDistributedBuild = subcommand.tryConvertingToStampede(distBuildConfig);
        }
      }

      // Switch to async file logging, if configured. A few log samples will have already gone
      // via the regular file logger, but that's OK.
      boolean isDistBuildCommand = command.subcommand instanceof DistBuildCommand;
      if (isDistBuildCommand) {
        LogConfig.setUseAsyncFileLogging(distBuildConfig.isAsyncLoggingEnabled());
      }

      RuleKeyConfiguration ruleKeyConfiguration =
          ConfigRuleKeyConfigurationFactory.create(buckConfig, moduleManager);

      String previousBuckCoreKey;
      if (!command.isReadOnly()) {
        Optional<String> currentBuckCoreKey =
            filesystem.readFileIfItExists(filesystem.getBuckPaths().getCurrentVersionFile());
        BuckPaths unconfiguredPaths =
            filesystem.getBuckPaths().withConfiguredBuckOut(filesystem.getBuckPaths().getBuckOut());

        previousBuckCoreKey = currentBuckCoreKey.orElse("<NOT_FOUND>");

        if (!currentBuckCoreKey.isPresent()
            || !currentBuckCoreKey.get().equals(ruleKeyConfiguration.getCoreKey())
            || (filesystem.exists(unconfiguredPaths.getGenDir(), LinkOption.NOFOLLOW_LINKS)
                && (filesystem.isSymLink(unconfiguredPaths.getGenDir())
                    ^ buckConfig.getBuckOutCompatLink()))) {
          // Migrate any version-dependent directories (which might be huge) to a trash directory
          // so we can delete it asynchronously after the command is done.
          moveToTrash(
              filesystem,
              console,
              buildId,
              filesystem.getBuckPaths().getAnnotationDir(),
              filesystem.getBuckPaths().getGenDir(),
              filesystem.getBuckPaths().getScratchDir(),
              filesystem.getBuckPaths().getResDir());
          shouldCleanUpTrash = true;
          filesystem.mkdirs(filesystem.getBuckPaths().getCurrentVersionFile().getParent());
          filesystem.writeContentsToPath(
              ruleKeyConfiguration.getCoreKey(), filesystem.getBuckPaths().getCurrentVersionFile());
        }
      } else {
        previousBuckCoreKey = "";
      }

      LOG.verbose("Buck core key from the previous Buck instance: %s", previousBuckCoreKey);

      ProcessExecutor processExecutor = new DefaultProcessExecutor(console);

      SandboxExecutionStrategyFactory sandboxExecutionStrategyFactory =
          new PlatformSandboxExecutionStrategyFactory();

      Clock clock;
      boolean enableThreadCpuTime =
          buckConfig.getBooleanValue("build", "enable_thread_cpu_time", true);
      if (BUCKD_LAUNCH_TIME_NANOS.isPresent()) {
        long nanosEpoch = Long.parseLong(BUCKD_LAUNCH_TIME_NANOS.get(), 10);
        LOG.verbose("Using nanos epoch: %d", nanosEpoch);
        clock = new NanosAdjustedClock(nanosEpoch, enableThreadCpuTime);
      } else {
        clock = new DefaultClock(enableThreadCpuTime);
      }

      ParserConfig parserConfig = buckConfig.getView(ParserConfig.class);
      Watchman watchman =
          buildWatchman(context, parserConfig, projectWatchList, clientEnvironment, console, clock);

      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider =
          KnownBuildRuleTypesProvider.of(
              knownBuildRuleTypesFactoryFactory.create(
                  processExecutor, pluginManager, sandboxExecutionStrategyFactory));

      ExecutableFinder executableFinder = new ExecutableFinder();

      ToolchainProviderFactory toolchainProviderFactory =
          new DefaultToolchainProviderFactory(
              pluginManager, clientEnvironment, processExecutor, executableFinder);

      DefaultCellPathResolver rootCellCellPathResolver =
          DefaultCellPathResolver.of(filesystem.getRootPath(), buckConfig.getConfig());

      Cell rootCell =
          LocalCellProviderFactory.create(
                  filesystem,
                  watchman,
                  buckConfig,
                  command.getConfigOverrides(),
                  rootCellCellPathResolver.getPathMapping(),
                  rootCellCellPathResolver,
                  moduleManager,
                  toolchainProviderFactory,
                  projectFilesystemFactory)
              .getCellByPath(filesystem.getRootPath());

      Optional<Daemon> daemon =
          context.isPresent() && (watchman != WatchmanFactory.NULL_WATCHMAN)
              ? Optional.of(
                  daemonLifecycleManager.getDaemon(
                      rootCell, knownBuildRuleTypesProvider, executableFinder, console))
              : Optional.empty();

      // Used the cached provider, if present.
      knownBuildRuleTypesProvider =
          daemon.map(Daemon::getKnownBuildRuleTypesProvider).orElse(knownBuildRuleTypesProvider);

      if (!daemon.isPresent() && shouldCleanUpTrash) {
        // Clean up the trash on a background thread if this was a
        // non-buckd read-write command. (We don't bother waiting
        // for it to complete; the thread is a daemon thread which
        // will just be terminated at shutdown time.)
        TRASH_CLEANER.startCleaningDirectory(filesystem.getBuckPaths().getTrashDir());
      }

      ImmutableList<BuckEventListener> eventListeners = ImmutableList.of();

      ImmutableList.Builder<ProjectFileHashCache> allCaches = ImmutableList.builder();

      // Build up the hash cache, which is a collection of the stateful cell cache and some
      // per-run caches.
      //
      // TODO(coneko, ruibm, agallagher): Determine whether we can use the existing filesystem
      // object that is in scope instead of creating a new rootCellProjectFilesystem. The primary
      // difference appears to be that filesystem is created with a Config that is used to produce
      // ImmutableSet<PathOrGlobMatcher> and BuckPaths for the ProjectFilesystem, whereas this one
      // uses the defaults.
      ProjectFilesystem rootCellProjectFilesystem =
          projectFilesystemFactory.createOrThrow(rootCell.getFilesystem().getRootPath());
      if (daemon.isPresent()) {
        allCaches.addAll(getFileHashCachesFromDaemon(daemon.get()));
      } else {
        rootCell
            .getAllCells()
            .stream()
            .map(
                cell ->
                    DefaultFileHashCache.createDefaultFileHashCache(
                        cell.getFilesystem(), rootCell.getBuckConfig().getFileHashCacheMode()))
            .forEach(allCaches::add);
        // The Daemon caches a buck-out filehashcache for the root cell, so the non-daemon case
        // needs to create that itself.
        allCaches.add(
            DefaultFileHashCache.createBuckOutFileHashCache(
                rootCell.getFilesystem(), rootCell.getBuckConfig().getFileHashCacheMode()));
      }

      rootCell
          .getAllCells()
          .forEach(
              cell -> {
                if (!cell.equals(rootCell)) {
                  allCaches.add(
                      DefaultFileHashCache.createBuckOutFileHashCache(
                          cell.getFilesystem(), rootCell.getBuckConfig().getFileHashCacheMode()));
                }
              });

      // A cache which caches hashes of cell-relative paths which may have been ignore by
      // the main cell cache, and only serves to prevent rehashing the same file multiple
      // times in a single run.
      allCaches.add(
          DefaultFileHashCache.createDefaultFileHashCache(
              rootCellProjectFilesystem, rootCell.getBuckConfig().getFileHashCacheMode()));
      allCaches.addAll(
          DefaultFileHashCache.createOsRootDirectoriesCaches(
              projectFilesystemFactory, rootCell.getBuckConfig().getFileHashCacheMode()));

      StackedFileHashCache fileHashCache = new StackedFileHashCache(allCaches.build());

      Optional<WebServer> webServer = daemon.flatMap(Daemon::getWebServer);
      Optional<ConcurrentMap<String, WorkerProcessPool>> persistentWorkerPools =
          daemon.map(Daemon::getPersistentWorkerPools);

      TestConfig testConfig = new TestConfig(buckConfig);
      ArtifactCacheBuckConfig cacheBuckConfig = new ArtifactCacheBuckConfig(buckConfig);

      SuperConsoleConfig superConsoleConfig = new SuperConsoleConfig(buckConfig);

      // Eventually, we'll want to get allow websocket and/or nailgun clients to specify locale
      // when connecting. For now, we'll use the default from the server environment.
      Locale locale = Locale.getDefault();

      InvocationInfo invocationInfo =
          InvocationInfo.of(
              buildId,
              superConsoleConfig.isEnabled(console, Platform.detect()),
              daemon.isPresent(),
              command.getSubCommandNameForLogging(),
              args,
              unexpandedCommandLineArgs,
              filesystem.getBuckPaths().getLogDir());

      try (GlobalStateManager.LoggerIsMappedToThreadScope loggerThreadMappingScope =
              GlobalStateManager.singleton()
                  .setupLoggers(invocationInfo, console.getStdErr(), stdErr, verbosity);
          DefaultBuckEventBus buildEventBus = new DefaultBuckEventBus(clock, buildId);
          // We use a new executor service beyond client connection lifetime since it can take a
          // long time to stat and cleanup large disk artifact cache directories
          // See https://github.com/facebook/buck/issues/1842
          // TODO(buck_team) switch this to delayed tasks framework
          ThrowingCloseableWrapper<ExecutorService, InterruptedException>
              dirArtifactExecutorService =
                  getExecutorWrapper(
                      MostExecutors.newSingleThreadExecutor("Dir Artifact"),
                      "Dir Artifact",
                      EXECUTOR_SERVICES_TIMEOUT_SECONDS);
          ) {

        try (ThrowingCloseableWrapper<ExecutorService, InterruptedException> diskIoExecutorService =
                getExecutorWrapper(
                    MostExecutors.newSingleThreadExecutor("Disk I/O"),
                    "Disk IO",
                    DISK_IO_STATS_TIMEOUT_SECONDS);
            ThrowingCloseableWrapper<ListeningExecutorService, InterruptedException>
                httpWriteExecutorService =
                    getExecutorWrapper(
                        getHttpWriteExecutorService(cacheBuckConfig, isUsingDistributedBuild),
                        "HTTP Write",
                        cacheBuckConfig.getHttpWriterShutdownTimeout());
            ThrowingCloseableWrapper<ListeningExecutorService, InterruptedException>
                stampedeSyncBuildHttpFetchExecutorService =
                    getExecutorWrapper(
                        getHttpFetchExecutorService(
                            "heavy", cacheBuckConfig.getDownloadHeavyBuildHttpFetchConcurrency()),
                        "Download Heavy Build HTTP Read",
                        cacheBuckConfig.getHttpWriterShutdownTimeout());
            ThrowingCloseableWrapper<ListeningExecutorService, InterruptedException>
                httpFetchExecutorService =
                    getExecutorWrapper(
                        getHttpFetchExecutorService(
                            "standard", cacheBuckConfig.getHttpFetchConcurrency()),
                        "HTTP Read",
                        cacheBuckConfig.getHttpWriterShutdownTimeout());
            ThrowingCloseableWrapper<ScheduledExecutorService, InterruptedException>
                counterAggregatorExecutor =
                    getExecutorWrapper(
                        Executors.newSingleThreadScheduledExecutor(
                            new CommandThreadFactory("CounterAggregatorThread")),
                        "CounterAggregatorExecutor",
                        COUNTER_AGGREGATOR_SERVICE_TIMEOUT_SECONDS);
            ThrowingCloseableWrapper<ScheduledExecutorService, InterruptedException>
                scheduledExecutorPool =
                    getExecutorWrapper(
                        Executors.newScheduledThreadPool(
                            buckConfig.getNumThreadsForSchedulerPool(),
                            new CommandThreadFactory(getClass().getName() + "SchedulerThreadPool")),
                        "ScheduledExecutorService",
                        EXECUTOR_SERVICES_TIMEOUT_SECONDS);
            // Create a cached thread pool for cpu intensive tasks
            ThrowingCloseableWrapper<ListeningExecutorService, InterruptedException>
                cpuExecutorService =
                    getExecutorWrapper(
                        listeningDecorator(Executors.newCachedThreadPool()),
                        ExecutorPool.CPU.toString(),
                        EXECUTOR_SERVICES_TIMEOUT_SECONDS);
            // Create a thread pool for network I/O tasks
            ThrowingCloseableWrapper<ListeningExecutorService, InterruptedException>
                networkExecutorService =
                    getExecutorWrapper(
                        newDirectExecutorService(),
                        ExecutorPool.NETWORK.toString(),
                        EXECUTOR_SERVICES_TIMEOUT_SECONDS);
            ThrowingCloseableWrapper<ListeningExecutorService, InterruptedException>
                projectExecutorService =
                    getExecutorWrapper(
                        listeningDecorator(
                            MostExecutors.newMultiThreadExecutor(
                                "Project", buckConfig.getNumThreads())),
                        ExecutorPool.PROJECT.toString(),
                        EXECUTOR_SERVICES_TIMEOUT_SECONDS);
            BuildInfoStoreManager storeManager = new BuildInfoStoreManager();
            AbstractConsoleEventBusListener consoleListener =
                createConsoleEventListener(
                    clock,
                    superConsoleConfig,
                    console,
                    testConfig.getResultSummaryVerbosity(),
                    executionEnvironment,
                    locale,
                    filesystem.getBuckPaths().getLogDir().resolve("test.log"),
                    buckConfig.isLogBuildIdToConsoleEnabled()
                        ? Optional.of(buildId)
                        : Optional.empty());
            // This makes calls to LOG.error(...) post to the EventBus, instead of writing to
            // stderr.
            Closeable logErrorToEventBus =
                loggerThreadMappingScope.setWriter(createWriterForConsole(consoleListener));
            Scope ddmLibLogRedirector = DdmLibLogRedirector.redirectDdmLogger(buildEventBus);

            // NOTE: This will only run during the lifetime of the process and will flush on close.
            CounterRegistry counterRegistry =
                new CounterRegistryImpl(
                    counterAggregatorExecutor.get(),
                    buildEventBus,
                    buckConfig.getCountersFirstFlushIntervalMillis(),
                    buckConfig.getCountersFlushIntervalMillis());
            PerfStatsTracking perfStatsTracking =
                new PerfStatsTracking(buildEventBus, invocationInfo);
            ProcessTracker processTracker =
                buckConfig.isProcessTrackerEnabled() && platform != Platform.WINDOWS
                    ? new ProcessTracker(
                        buildEventBus,
                        invocationInfo,
                        daemon.isPresent(),
                        buckConfig.isProcessTrackerDeepEnabled())
                    : null;
            ArtifactCaches artifactCacheFactory =
                new ArtifactCaches(
                    cacheBuckConfig,
                    buildEventBus,
                    filesystem,
                    executionEnvironment.getWifiSsid(),
                    httpWriteExecutorService.get(),
                    httpFetchExecutorService.get(),
                    stampedeSyncBuildHttpFetchExecutorService.get(),
                    dirArtifactExecutorService.get());

            // Once command completes it should be safe to not wait for executors and other stateful
            // objects to terminate and release semaphore right away. It will help to retry
            // command faster if user terminated with Ctrl+C.
            // Ideally, we should come up with a better lifecycle management strategy for the
            // semaphore object
            CloseableWrapper<Optional<CloseableWrapper<Semaphore>>> semaphoreCloser =
                CloseableWrapper.of(
                    Optional.ofNullable(semaphore),
                    s -> {
                      if (s.isPresent()) {
                        s.get().close();
                      }
                    });

            // This will get executed first once it gets out of try block and just wait for
            // event bus to dispatch all pending events before we proceed to termination
            // procedures
            CloseableWrapper<BuckEventBus> waitEvents = getWaitEventsWrapper(buildEventBus)) {

          LOG.debug(invocationInfo.toLogLine());

          buildEventBus.register(HANG_MONITOR.getHangMonitor());

          ImmutableMap<ExecutorPool, ListeningExecutorService> executors =
              ImmutableMap.of(
                  ExecutorPool.CPU,
                  cpuExecutorService.get(),
                  ExecutorPool.NETWORK,
                  networkExecutorService.get(),
                  ExecutorPool.PROJECT,
                  projectExecutorService.get());

          // No need to kick off ProgressEstimator for commands that
          // don't build anything -- it has overhead and doesn't seem
          // to work for (e.g.) query anyway. ProgressEstimator has
          // special support for project so we have to include it
          // there too.
          if (consoleListener.displaysEstimatedProgress()
              && (command.performsBuild() || command.subcommand instanceof ProjectCommand)) {
            ProgressEstimator progressEstimator =
                new ProgressEstimator(
                    filesystem
                        .resolve(filesystem.getBuckPaths().getBuckOut())
                        .resolve(ProgressEstimator.PROGRESS_ESTIMATIONS_JSON),
                    buildEventBus);
            consoleListener.setProgressEstimator(progressEstimator);
          }

          BuildEnvironmentDescription buildEnvironmentDescription =
              getBuildEnvironmentDescription(
                  executionEnvironment,
                  buckConfig);

          Iterable<BuckEventListener> commandEventListeners =
              command.getSubcommand().isPresent()
                  ? command
                      .getSubcommand()
                      .get()
                      .getEventListeners(executors, scheduledExecutorPool.get())
                  : ImmutableList.of();

          eventListeners =
              addEventListeners(
                  buildEventBus,
                  daemon.map(d -> d.getFileEventBus()),
                  rootCell.getFilesystem(),
                  invocationInfo,
                  rootCell.getBuckConfig(),
                  webServer,
                  clock,
                  consoleListener,
                  counterRegistry,
                  commandEventListeners
                  );

          if (buckConfig.isBuckConfigLocalWarningEnabled() && !console.getVerbosity().isSilent()) {
            ImmutableList<Path> localConfigFiles =
                rootCell
                    .getAllCells()
                    .stream()
                    .map(
                        cell ->
                            cell.getRoot().resolve(Configs.DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME))
                    .filter(path -> Files.isRegularFile(path))
                    .collect(ImmutableList.toImmutableList());
            if (localConfigFiles.size() > 0) {
              String message =
                  localConfigFiles.size() == 1
                      ? "Using local configuration:"
                      : "Using local configurations:";
              buildEventBus.post(ConsoleEvent.warning(message));
              for (Path localConfigFile : localConfigFiles) {
                buildEventBus.post(ConsoleEvent.warning(String.format("- %s", localConfigFile)));
              }
            }
          }

          if (commandMode == CommandMode.RELEASE && buckConfig.isPublicAnnouncementsEnabled()) {
            PublicAnnouncementManager announcementManager =
                new PublicAnnouncementManager(
                    clock,
                    buildEventBus,
                    consoleListener,
                    buckConfig.getRepository().orElse("unknown"),
                    new RemoteLogBuckConfig(buckConfig),
                    executors.get(ExecutorPool.CPU));
            announcementManager.getAndPostAnnouncements();
          }

          // This needs to be after the registration of the event listener so they can pick it up.
          if (watchmanFreshInstanceAction == WatchmanWatcher.FreshInstanceAction.NONE) {
            LOG.debug("new Buck daemon");
            buildEventBus.post(DaemonEvent.newDaemonInstance());
          }


          VersionControlBuckConfig vcBuckConfig = new VersionControlBuckConfig(buckConfig);
          VersionControlStatsGenerator vcStatsGenerator =
              new VersionControlStatsGenerator(
                  new DelegatingVersionControlCmdLineInterface(
                      rootCell.getFilesystem().getRootPath(),
                      new PrintStreamProcessExecutorFactory(),
                      vcBuckConfig.getHgCmd(),
                      buckConfig.getEnvironment()),
                  vcBuckConfig.getPregeneratedVersionControlStats());
          if (command.subcommand instanceof AbstractCommand
              && !(command.subcommand instanceof DistBuildCommand)) {
            AbstractCommand subcommand = (AbstractCommand) command.subcommand;
            if (!commandMode.equals(CommandMode.TEST)) {
              vcStatsGenerator.generateStatsAsync(
                  subcommand.isSourceControlStatsGatheringEnabled()
                      || vcBuckConfig.shouldGenerateStatistics(),
                  diskIoExecutorService.get(),
                  buildEventBus);
            }
          }
          NetworkInfo.generateActiveNetworkAsync(diskIoExecutorService.get(), buildEventBus);

          ImmutableList<String> remainingArgs =
              args.isEmpty() ? ImmutableList.of() : args.subList(1, args.size());

          CommandEvent.Started startedEvent =
              CommandEvent.started(
                  command.getDeclaredSubCommandName(),
                  remainingArgs,
                  daemon.isPresent(),
                  getBuckPID());
          buildEventBus.post(startedEvent);

          ParserAndCaches parserAndCaches =
              getParserAndCaches(
                  context,
                  watchmanFreshInstanceAction,
                  filesystem,
                  buckConfig,
                  watchman,
                  knownBuildRuleTypesProvider,
                  rootCell,
                  daemon,
                  buildEventBus,
                  executableFinder);

          // Because the Parser is potentially constructed before the CounterRegistry,
          // we need to manually register its counters after it's created.
          //
          // The counters will be unregistered once the counter registry is closed.
          counterRegistry.registerCounters(
              parserAndCaches.getParser().getPermState().getCounters());

          Optional<ProcessManager> processManager;
          if (platform == Platform.WINDOWS) {
            processManager = Optional.empty();
          } else {
            processManager = Optional.of(new PkillProcessManager(processExecutor));
          }

          // At this point, we have parsed options but haven't started
          // running the command yet.  This is a good opportunity to
          // augment the event bus with our serialize-to-file
          // event-listener.
          if (command.subcommand instanceof AbstractCommand) {
            AbstractCommand subcommand = (AbstractCommand) command.subcommand;
            Optional<Path> eventsOutputPath = subcommand.getEventsOutputPath();
            if (eventsOutputPath.isPresent()) {
              BuckEventListener listener =
                  new FileSerializationEventBusListener(eventsOutputPath.get());
              buildEventBus.register(listener);
            }
          }

          buildEventBus.post(
              new BuckInitializationDurationEvent(
                  TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - initTimestamp)));

          try {
            exitCode =
                command.run(
                    CommandRunnerParams.of(
                        console,
                        stdIn,
                        rootCell,
                        parserAndCaches.getVersionedTargetGraphCache(),
                        artifactCacheFactory,
                        parserAndCaches.getTypeCoercerFactory(),
                        parserAndCaches.getParser(),
                        buildEventBus,
                        platform,
                        clientEnvironment,
                        rootCell
                            .getBuckConfig()
                            .getView(JavaBuckConfig.class)
                            .createDefaultJavaPackageFinder(),
                        clock,
                        vcStatsGenerator,
                        processManager,
                        webServer,
                        persistentWorkerPools,
                        buckConfig,
                        fileHashCache,
                        executors,
                        scheduledExecutorPool.get(),
                        buildEnvironmentDescription,
                        parserAndCaches.getActionGraphCache(),
                        knownBuildRuleTypesProvider,
                        storeManager,
                        Optional.of(invocationInfo),
                        parserAndCaches.getDefaultRuleKeyFactoryCacheRecycler(),
                        projectFilesystemFactory,
                        ruleKeyConfiguration,
                        processExecutor,
                        executableFinder,
                        pluginManager,
                        moduleManager,
                        getForkJoinPoolSupplier(buckConfig)));
          } catch (InterruptedException | ClosedByInterruptException e) {
            buildEventBus.post(CommandEvent.interrupted(startedEvent, ExitCode.SIGNAL_INTERRUPT));
            throw e;
          }
          buildEventBus.post(
              new CacheStatsEvent(
                  "versioned_target_graph_cache",
                  parserAndCaches.getVersionedTargetGraphCache().getCacheStats()));
          buildEventBus.post(CommandEvent.finished(startedEvent, exitCode));
        } finally {
          // signal nailgun that we are not interested in client disconnect events anymore
          context.ifPresent(c -> c.removeAllClientListeners());

          if (daemon.isPresent() && shouldCleanUpTrash) {
            // Clean up the trash in the background if this was a buckd
            // read-write command. (We don't bother waiting for it to
            // complete; the cleaner will ensure subsequent cleans are
            // serialized with this one.)
            TRASH_CLEANER.startCleaningDirectory(filesystem.getBuckPaths().getTrashDir());
          }

          // Exit Nailgun earlier if command succeeded to now block the client while performing
          // telemetry upload in background
          // For failures, always do it synchronously because exitCode in fact may be overridden up
          // the stack
          // TODO(buck_team): refactor this as in case of exception exitCode is reported incorrectly
          // to the CommandEvent listener
          if (exitCode == ExitCode.SUCCESS
              && context.isPresent()
              && !rootCell.getBuckConfig().getFlushEventsBeforeExit()) {
            context.get().in.close(); // Avoid client exit triggering client disconnection handling.
            context.get().exit(exitCode.getCode());
          }

          // TODO(buck_team): refactor eventListeners for RAII
          flushAndCloseEventListeners(console, buildId, eventListeners);
        }
      }
    }
    return exitCode;
  }

  /** Struct for the multiple values returned by {@link #getParserAndCaches}. */
  @Value.Immutable(copy = false, builder = false)
  @BuckStyleTuple
  abstract static class AbstractParserAndCaches {
    public abstract Parser getParser();

    public abstract TypeCoercerFactory getTypeCoercerFactory();

    public abstract InstrumentedVersionedTargetGraphCache getVersionedTargetGraphCache();

    public abstract ActionGraphCache getActionGraphCache();

    public abstract Optional<RuleKeyCacheRecycler<RuleKey>> getDefaultRuleKeyFactoryCacheRecycler();
  }

  private static ParserAndCaches getParserAndCaches(
      Optional<NGContext> context,
      FreshInstanceAction watchmanFreshInstanceAction,
      ProjectFilesystem filesystem,
      BuckConfig buckConfig,
      Watchman watchman,
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider,
      Cell rootCell,
      Optional<Daemon> daemonOptional,
      BuckEventBus buildEventBus,
      ExecutableFinder executableFinder)
      throws IOException, InterruptedException {
    WatchmanWatcher watchmanWatcher = null;
    if (daemonOptional.isPresent() && watchman.getTransportPath().isPresent()) {
      Daemon daemon = daemonOptional.get();
      try {
        watchmanWatcher =
            new WatchmanWatcher(
                watchman,
                daemon.getFileEventBus(),
                ImmutableSet.<PathOrGlobMatcher>builder()
                    .addAll(filesystem.getIgnorePaths())
                    .addAll(DEFAULT_IGNORE_GLOBS)
                    .build(),
                daemon.getWatchmanCursor(),
                buckConfig.getNumThreads());
      } catch (WatchmanWatcherException e) {
        buildEventBus.post(
            ConsoleEvent.warning(
                "Watchman threw an exception while parsing file changes.\n%s", e.getMessage()));
      }
    }

    ParserConfig parserConfig = rootCell.getBuckConfig().getView(ParserConfig.class);
    if (parserConfig.shouldIgnoreEnvironmentVariablesChanges()
        && parserConfig.isParserCacheMutationWarningEnabled()) {
      buildEventBus.post(
          ConsoleEvent.warning(
              "WARNING: Environment variable changes won't discard the parser state."));
    }

    // Create or get Parser and invalidate cached command parameters.
    ParserAndCaches parserAndCaches;
    if (watchmanWatcher != null) {
      // Note that watchmanWatcher is non-null only when daemon.isPresent().
      Daemon daemon = daemonOptional.get();
      registerClientDisconnectedListener(context.get(), daemon);
      daemon.watchFileSystem(buildEventBus, watchmanWatcher, watchmanFreshInstanceAction);
      Optional<RuleKeyCacheRecycler<RuleKey>> defaultRuleKeyFactoryCacheRecycler;
      if (buckConfig.getRuleKeyCaching()) {
        LOG.debug("Using rule key calculation caching");
        defaultRuleKeyFactoryCacheRecycler =
            Optional.of(daemon.getDefaultRuleKeyFactoryCacheRecycler());
      } else {
        defaultRuleKeyFactoryCacheRecycler = Optional.empty();
      }
      parserAndCaches =
          ParserAndCaches.of(
              daemon.getParser(),
              daemon.getTypeCoercerFactory(),
              new InstrumentedVersionedTargetGraphCache(
                  daemon.getVersionedTargetGraphCache(), new InstrumentingCacheStatsTracker()),
              daemon.getActionGraphCache(),
              defaultRuleKeyFactoryCacheRecycler);
    } else {
      TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
      parserAndCaches =
          ParserAndCaches.of(
              new DefaultParser(
                  parserConfig,
                  typeCoercerFactory,
                  new ConstructorArgMarshaller(typeCoercerFactory),
                  knownBuildRuleTypesProvider,
                  executableFinder,
                  new TargetSpecResolver()),
              typeCoercerFactory,
              new InstrumentedVersionedTargetGraphCache(
                  new VersionedTargetGraphCache(), new InstrumentingCacheStatsTracker()),
              new ActionGraphCache(buckConfig.getMaxActionGraphCacheEntries()),
              /* defaultRuleKeyFactoryCacheRecycler */ Optional.empty());
    }
    return parserAndCaches;
  }

  private static void registerClientDisconnectedListener(NGContext context, Daemon daemon) {
    Thread mainThread = Thread.currentThread();
    context.addClientListener(
        reason -> {
          LOG.info("Nailgun client disconnected with " + reason);
          if (Main.isSessionLeader && Main.commandSemaphoreNgClient.orElse(null) == context) {
            // Process no longer wants work done on its behalf.
            LOG.debug("Killing background processes on client disconnect");
            BgProcessKiller.interruptBgProcesses();
          }

          if (reason != NGClientDisconnectReason.SESSION_SHUTDOWN) {
            LOG.debug("Killing all Buck jobs on client disconnect by interrupting the main thread");
            // signal daemon to complete required tasks and interrupt main thread
            // this will hopefully trigger InterruptedException and program shutdown
            daemon.interruptOnClientExit(mainThread);
          }
        });
  }

  private Console makeCustomConsole(
      Optional<NGContext> context, Verbosity verbosity, BuckConfig buckConfig) {
    Optional<String> color;
    if (context.isPresent() && (context.get().getEnv() != null)) {
      String colorString = context.get().getEnv().getProperty(BUCKD_COLOR_DEFAULT_ENV_VAR);
      color = Optional.ofNullable(colorString);
    } else {
      color = Optional.empty();
    }
    return new Console(verbosity, stdOut, stdErr, buckConfig.createAnsi(color));
  }

  private void flushAndCloseEventListeners(
      Console console, BuildId buildId, ImmutableList<BuckEventListener> eventListeners)
      throws InterruptedException, IOException {
    for (BuckEventListener eventListener : eventListeners) {
      try {
        eventListener.outputTrace(buildId);
        if (eventListener instanceof Closeable) {
          ((Closeable) eventListener).close();
        }
      } catch (RuntimeException e) {
        PrintStream stdErr = console.getStdErr();
        stdErr.println("Ignoring non-fatal error!  The stack trace is below:");
        e.printStackTrace(stdErr);
      }
    }
  }

  private static void moveToTrash(
      ProjectFilesystem filesystem, Console console, BuildId buildId, Path... pathsToMove)
      throws IOException {
    Path trashPath = filesystem.getBuckPaths().getTrashDir().resolve(buildId.toString());
    filesystem.mkdirs(trashPath);
    for (Path pathToMove : pathsToMove) {
      try {
        // Technically this might throw AtomicMoveNotSupportedException,
        // but we're moving a path within buck-out, so we don't expect this
        // to throw.
        //
        // If it does throw, we'll complain loudly and synchronously delete
        // the file instead.
        filesystem.move(
            pathToMove,
            trashPath.resolve(pathToMove.getFileName()),
            StandardCopyOption.ATOMIC_MOVE);
      } catch (NoSuchFileException e) {
        LOG.verbose(e, "Ignoring missing path %s", pathToMove);
      } catch (AtomicMoveNotSupportedException e) {
        console
            .getStdErr()
            .format("Atomic moves not supported, falling back to synchronous delete: %s", e);
        MostFiles.deleteRecursivelyIfExists(pathToMove);
      }
    }
  }

  private static final Watchman buildWatchman(
      Optional<NGContext> context,
      ParserConfig parserConfig,
      ImmutableSet<Path> projectWatchList,
      ImmutableMap<String, String> clientEnvironment,
      Console console,
      Clock clock)
      throws InterruptedException {
    Watchman watchman;
    if (context.isPresent() || parserConfig.getGlobHandler() == ParserConfig.GlobHandler.WATCHMAN) {
      WatchmanFactory watchmanFactory = new WatchmanFactory();
      watchman =
          watchmanFactory.build(
              projectWatchList,
              clientEnvironment,
              console,
              clock,
              parserConfig.getWatchmanQueryTimeoutMs());

      LOG.debug(
          "Watchman capabilities: %s Project watches: %s Glob handler config: %s "
              + "Query timeout ms config: %s",
          watchman.getCapabilities(),
          watchman.getProjectWatches(),
          parserConfig.getGlobHandler(),
          parserConfig.getWatchmanQueryTimeoutMs());

    } else {
      watchman = WatchmanFactory.NULL_WATCHMAN;
      LOG.debug(
          "Not using Watchman, context present: %s, glob handler: %s",
          context.isPresent(), parserConfig.getGlobHandler());
    }
    return watchman;
  }

  /**
   * RAII wrapper which does not really close any object but waits for all events in given event bus
   * to complete. We want to have it this way to safely start deinitializing event listeners
   */
  private static CloseableWrapper<BuckEventBus> getWaitEventsWrapper(BuckEventBus buildEventBus) {
    return CloseableWrapper.of(
        buildEventBus,
        eventBus -> {
          // wait for event bus to process all pending events
          if (!eventBus.waitEvents(EVENT_BUS_TIMEOUT_SECONDS * 1000)) {
            LOG.warn(
                "Event bus did not complete all events within timeout; event listener's data"
                    + "may be incorrect");
          }
        });
  }

  private static <T extends ExecutorService>
      ThrowingCloseableWrapper<T, InterruptedException> getExecutorWrapper(
          T executor, String executorName, long closeTimeoutSeconds) {
    return ThrowingCloseableWrapper.of(
        executor,
        service -> {
          executor.shutdown();
          LOG.info(
              "Awaiting termination of %s executor service. Waiting for all jobs to complete, "
                  + "or up to maximum of %s seconds...",
              executorName, closeTimeoutSeconds);
          executor.awaitTermination(closeTimeoutSeconds, TimeUnit.SECONDS);
          if (!executor.isTerminated()) {
            LOG.warn(
                "%s executor service is still running after shutdown request and "
                    + "%s second timeout. Shutting down forcefully..",
                executorName, closeTimeoutSeconds);
            executor.shutdownNow();
          } else {
            LOG.info("Successfully terminated %s executor service.", executorName);
          }
        });
  }

  private static ListeningExecutorService getHttpWriteExecutorService(
      ArtifactCacheBuckConfig buckConfig, boolean isUsingDistributedBuild) {
    if (isUsingDistributedBuild || buckConfig.hasAtLeastOneWriteableRemoteCache()) {
      // Distributed builds need to upload from the local cache to the remote cache.
      ExecutorService executorService =
          MostExecutors.newMultiThreadExecutor(
              "HTTP Write", buckConfig.getHttpMaxConcurrentWrites());
      return listeningDecorator(executorService);
    } else {
      return newDirectExecutorService();
    }
  }

  private static ListeningExecutorService getHttpFetchExecutorService(
      String prefix, int fetchConcurrency) {
    return listeningDecorator(
        MostExecutors.newMultiThreadExecutor(
            new ThreadFactoryBuilder().setNameFormat(prefix + "-cache-fetch-%d").build(),
            fetchConcurrency));
  }

  private static ConsoleHandlerState.Writer createWriterForConsole(
      AbstractConsoleEventBusListener console) {
    return new ConsoleHandlerState.Writer() {
      @Override
      public void write(String line) {
        console.printSevereWarningDirectly(line);
      }

      @Override
      public void flush() {
        // Intentional no-op.
      }

      @Override
      public void close() {
        // Intentional no-op.
      }
    };
  }

  /**
   * @return the client environment, which is either the process environment or the environment sent
   *     to the daemon by the Nailgun client. This method should always be used in preference to
   *     System.getenv() and should be the only call to System.getenv() within the Buck codebase to
   *     ensure that the use of the Buck daemon is transparent. This also scrubs NG environment
   *     variables if no context is actually present.
   */
  @SuppressWarnings({"unchecked", "rawtypes"}) // Safe as Property is a Map<String, String>.
  private static ImmutableMap<String, String> getClientEnvironment(Optional<NGContext> context) {
    if (context.isPresent()) {
      return ImmutableMap.<String, String>copyOf((Map) context.get().getEnv());
    } else {

      Builder<String, String> builder = ImmutableMap.builder();
      System.getenv()
          .entrySet()
          .stream()
          .filter(
              e ->
                  !NAILGUN_STDOUT_ISTTY_ENV.equals(e.getKey())
                      && !NAILGUN_STDERR_ISTTY_ENV.equals(e.getKey()))
          .forEach(builder::put);
      return builder.build();
    }
  }

  private ImmutableList<ProjectFileHashCache> getFileHashCachesFromDaemon(Daemon daemon) {
    return daemon.getFileHashCaches();
  }

  private void loadListenersFromBuckConfig(
      ImmutableList.Builder<BuckEventListener> eventListeners,
      ProjectFilesystem projectFilesystem,
      BuckConfig config) {
    ImmutableSet<String> paths = config.getListenerJars();
    if (paths.isEmpty()) {
      return;
    }

    URL[] urlsArray = new URL[paths.size()];
    try {
      int i = 0;
      for (String path : paths) {
        String urlString = "file://" + projectFilesystem.resolve(Paths.get(path));
        urlsArray[i] = new URL(urlString);
        i++;
      }
    } catch (MalformedURLException e) {
      throw new HumanReadableException(e.getMessage());
    }

    // This ClassLoader is disconnected to allow searching the JARs (and just the JARs) for classes.
    ClassLoader isolatedClassLoader = URLClassLoader.newInstance(urlsArray, null);

    ImmutableSet<ClassPath.ClassInfo> classInfos;
    try {
      ClassPath classPath = ClassPath.from(isolatedClassLoader);
      classInfos = classPath.getTopLevelClasses();
    } catch (IOException e) {
      throw new HumanReadableException(e.getMessage());
    }

    // This ClassLoader will actually work, because it is joined to the parent ClassLoader.
    URLClassLoader workingClassLoader = URLClassLoader.newInstance(urlsArray);

    for (ClassPath.ClassInfo classInfo : classInfos) {
      String className = classInfo.getName();
      try {
        Class<?> aClass = Class.forName(className, true, workingClassLoader);
        if (BuckEventListener.class.isAssignableFrom(aClass)) {
          BuckEventListener listener = aClass.asSubclass(BuckEventListener.class).newInstance();
          eventListeners.add(listener);
        }
      } catch (ReflectiveOperationException e) {
        throw new HumanReadableException(
            "Error loading event listener class '%s': %s: %s",
            className, e.getClass(), e.getMessage());
      }
    }
  }

  /**
   * Try to acquire global semaphore if needed to do so. Attach closer to acquired semaphore in a
   * form of a wrapper object so it can be used with try-with-resources.
   *
   * @return Semaphore wrapper object if semaphore is acquired, null otherwise
   */
  private @Nullable CloseableWrapper<Semaphore> getSemaphoreWrapper(BuckCommand command) {
    // we can execute read-only commands (query, targets, etc) in parallel
    if (command.isReadOnly()) {
      // using nullable instead of Optional<> to use the object with try-with-resources
      return null;
    }

    if (!commandSemaphore.tryAcquire()) {
      return null;
    }

    commandSemaphoreNgClient = context;

    return CloseableWrapper.of(
        commandSemaphore,
        commandSemaphore -> {
          commandSemaphoreNgClient = Optional.empty();
          // TODO(buck_team): have background process killer have its own lifetime management
          BgProcessKiller.disarm();
          commandSemaphore.release();
        });
  }


  @SuppressWarnings("PMD.PrematureDeclaration")
  private ImmutableList<BuckEventListener> addEventListeners(
      BuckEventBus buckEventBus,
      Optional<EventBus> fileEventBus,
      ProjectFilesystem projectFilesystem,
      InvocationInfo invocationInfo,
      BuckConfig buckConfig,
      Optional<WebServer> webServer,
      Clock clock,
      AbstractConsoleEventBusListener consoleEventBusListener,
      CounterRegistry counterRegistry,
      Iterable<BuckEventListener> commandSpecificEventListeners
      ) {
    ImmutableList.Builder<BuckEventListener> eventListenersBuilder =
        ImmutableList.<BuckEventListener>builder()
            .add(consoleEventBusListener)
            .add(new LoggingBuildListener());

    if (buckConfig.getBooleanValue("log", "jul_build_log", false)) {
      eventListenersBuilder.add(new JavaUtilsLoggingBuildListener(projectFilesystem));
    }

    ChromeTraceBuckConfig chromeTraceConfig = buckConfig.getView(ChromeTraceBuckConfig.class);
    if (chromeTraceConfig.isChromeTraceCreationEnabled()) {
      try {
        ChromeTraceBuildListener chromeTraceBuildListener =
            new ChromeTraceBuildListener(
                projectFilesystem, invocationInfo, clock, chromeTraceConfig);
        eventListenersBuilder.add(chromeTraceBuildListener);
        fileEventBus.ifPresent(bus -> bus.register(chromeTraceBuildListener));
      } catch (IOException e) {
        LOG.error("Unable to create ChromeTrace listener!");
      }
    } else {
      LOG.warn("::: ChromeTrace listener disabled");
    }
    if (webServer.isPresent()) {
      eventListenersBuilder.add(webServer.get().createListener());
    }

    loadListenersFromBuckConfig(eventListenersBuilder, projectFilesystem, buckConfig);
    ArtifactCacheBuckConfig artifactCacheConfig = new ArtifactCacheBuckConfig(buckConfig);


    eventListenersBuilder.add(
        new LogUploaderListener(
            chromeTraceConfig,
            invocationInfo.getLogFilePath(),
            invocationInfo.getLogDirectoryPath()));
    if (buckConfig.isRuleKeyLoggerEnabled()) {
      eventListenersBuilder.add(
          new RuleKeyLoggerListener(
              projectFilesystem,
              invocationInfo,
              MostExecutors.newSingleThreadExecutor(
                  new CommandThreadFactory(getClass().getName()))));
    }

    eventListenersBuilder.add(
        new RuleKeyDiagnosticsListener(
            projectFilesystem,
            invocationInfo,
            MostExecutors.newSingleThreadExecutor(new CommandThreadFactory(getClass().getName()))));

    if (buckConfig.isMachineReadableLoggerEnabled()) {
      try {
        eventListenersBuilder.add(
            new MachineReadableLoggerListener(
                invocationInfo,
                projectFilesystem,
                MostExecutors.newSingleThreadExecutor(
                    new CommandThreadFactory(getClass().getName())),
                artifactCacheConfig.getArtifactCacheModes()));
      } catch (FileNotFoundException e) {
        LOG.warn("Unable to open stream for machine readable log file.");
      }
    }

    eventListenersBuilder.add(new ParserProfilerLoggerListener(invocationInfo, projectFilesystem));


    eventListenersBuilder.add(new LoadBalancerEventsListener(counterRegistry));
    eventListenersBuilder.add(new CacheRateStatsListener(buckEventBus));
    eventListenersBuilder.add(new WatchmanDiagnosticEventListener(buckEventBus));
    if (buckConfig.isCriticalPathAnalysisEnabled()) {
      eventListenersBuilder.add(
          new BuildTargetDurationListener(
              invocationInfo,
              projectFilesystem,
              MostExecutors.newSingleThreadExecutor(
                  new CommandThreadFactory(BuildTargetDurationListener.class.getName()))));
    }
    eventListenersBuilder.addAll(commandSpecificEventListeners);

    ImmutableList<BuckEventListener> eventListeners = eventListenersBuilder.build();
    eventListeners.forEach(buckEventBus::register);

    return eventListeners;
  }

  private BuildEnvironmentDescription getBuildEnvironmentDescription(
      ExecutionEnvironment executionEnvironment,
      BuckConfig buckConfig) {
    ImmutableMap.Builder<String, String> environmentExtraData = ImmutableMap.builder();

    return BuildEnvironmentDescription.of(
        executionEnvironment,
        new ArtifactCacheBuckConfig(buckConfig).getArtifactCacheModesRaw(),
        environmentExtraData.build());
  }

  private AbstractConsoleEventBusListener createConsoleEventListener(
      Clock clock,
      SuperConsoleConfig config,
      Console console,
      TestResultSummaryVerbosity testResultSummaryVerbosity,
      ExecutionEnvironment executionEnvironment,
      Locale locale,
      Path testLogPath,
      Optional<BuildId> buildId) {
    if (config.isEnabled(console, Platform.detect())) {
      SuperConsoleEventBusListener superConsole =
          new SuperConsoleEventBusListener(
              config,
              console,
              clock,
              testResultSummaryVerbosity,
              executionEnvironment,
              locale,
              testLogPath,
              TimeZone.getDefault(),
              buildId);
      superConsole.startRenderScheduler(
          SUPER_CONSOLE_REFRESH_RATE.toMillis(), TimeUnit.MILLISECONDS);
      return superConsole;
    }
    return new SimpleConsoleEventBusListener(
        console,
        clock,
        testResultSummaryVerbosity,
        config.getHideSucceededRulesInLogMode(),
        config.getNumberOfSlowRulesToShow(),
        config.shouldShowSlowRulesInConsole(),
        locale,
        testLogPath,
        executionEnvironment,
        buildId);
  }

  /**
   * A helper method to retrieve the process ID of Buck. The return value from the JVM has to match
   * the following pattern: {PID}@{Hostname}. It it does not match the return value is 0.
   *
   * @return the PID or 0L.
   */
  private static long getBuckPID() {
    String pid = ManagementFactory.getRuntimeMXBean().getName();
    return (pid != null && pid.matches("^\\d+@.*$")) ? Long.parseLong(pid.split("@")[0]) : 0L;
  }

  private static BuildId getBuildId(Optional<NGContext> context) {
    String specifiedBuildId;
    if (context.isPresent()) {
      specifiedBuildId = context.get().getEnv().getProperty(BUCK_BUILD_ID_ENV_VAR);
    } else {
      specifiedBuildId = System.getenv().get(BUCK_BUILD_ID_ENV_VAR);
    }
    if (specifiedBuildId == null) {
      specifiedBuildId = UUID.randomUUID().toString();
    }
    return new BuildId(specifiedBuildId);
  }

  /**
   * @param buckConfig the configuration for resources
   * @return a memoized supplier for a ForkJoinPool that will be closed properly if initialized
   */
  @VisibleForTesting
  static CloseableMemoizedSupplier<ForkJoinPool> getForkJoinPoolSupplier(BuckConfig buckConfig) {
    ResourcesConfig resource = buckConfig.getView(ResourcesConfig.class);
    return CloseableMemoizedSupplier.of(
        () ->
            MostExecutors.forkJoinPoolWithThreadLimit(
                resource.getMaximumResourceAmounts().getCpu(), 16),
        ForkJoinPool::shutdownNow);
  }

  private static void installUncaughtExceptionHandler(Optional<NGContext> context) {
    // Override the default uncaught exception handler for background threads to log
    // to java.util.logging then exit the JVM with an error code.
    //
    // (We do this because the default is to just print to stderr and not exit the JVM,
    // which is not safe in a multithreaded environment if the thread held a lock or
    // resource which other threads need.)
    Thread.setDefaultUncaughtExceptionHandler(
        (t, e) -> {
          ExitCode exitCode = ExitCode.FATAL_GENERIC;
          if (e instanceof OutOfMemoryError) {
            exitCode = ExitCode.FATAL_OOM;
          } else if (e instanceof IOException) {
            exitCode =
                e.getMessage().startsWith("No space left on device")
                    ? ExitCode.FATAL_DISK_FULL
                    : ExitCode.FATAL_IO;
          }

          // Do not log anything in case we do not have space on the disk
          if (exitCode != ExitCode.FATAL_DISK_FULL) {
            LOG.error(e, "Uncaught exception from thread %s", t);
          }

          if (context.isPresent()) {
            // Shut down the Nailgun server and make sure it stops trapping System.exit().
            // We pass false for exitVM because otherwise Nailgun exits with code 0.
            context.get().getNGServer().shutdown(/* exitVM */ false);
          }

          NON_REENTRANT_SYSTEM_EXIT.shutdownSoon(exitCode.getCode());
        });
  }

  public static void main(String[] args) {
    new Main(System.out, System.err, System.in, Optional.empty())
        .runMainThenExit(args, System.nanoTime());
  }

  private static void markFdCloseOnExec(int fd) {
    int fdFlags;
    fdFlags = Libc.INSTANCE.fcntl(fd, Libc.Constants.rFGETFD);
    if (fdFlags == -1) {
      throw new LastErrorException(Native.getLastError());
    }
    fdFlags |= Libc.Constants.rFDCLOEXEC;
    if (Libc.INSTANCE.fcntl(fd, Libc.Constants.rFSETFD, fdFlags) == -1) {
      throw new LastErrorException(Native.getLastError());
    }
  }

  private static void daemonizeIfPossible() {
    String osName = System.getProperty("os.name");
    Libc.OpenPtyLibrary openPtyLibrary;
    Platform platform = Platform.detect();
    if (platform == Platform.LINUX) {
      Libc.Constants.rTIOCSCTTY = Libc.Constants.LINUX_TIOCSCTTY;
      Libc.Constants.rFDCLOEXEC = Libc.Constants.LINUX_FD_CLOEXEC;
      Libc.Constants.rFGETFD = Libc.Constants.LINUX_F_GETFD;
      Libc.Constants.rFSETFD = Libc.Constants.LINUX_F_SETFD;
      openPtyLibrary = Native.loadLibrary("libutil", Libc.OpenPtyLibrary.class);
    } else if (platform == Platform.MACOS) {
      Libc.Constants.rTIOCSCTTY = Libc.Constants.DARWIN_TIOCSCTTY;
      Libc.Constants.rFDCLOEXEC = Libc.Constants.DARWIN_FD_CLOEXEC;
      Libc.Constants.rFGETFD = Libc.Constants.DARWIN_F_GETFD;
      Libc.Constants.rFSETFD = Libc.Constants.DARWIN_F_SETFD;
      openPtyLibrary =
          Native.loadLibrary(com.sun.jna.Platform.C_LIBRARY_NAME, Libc.OpenPtyLibrary.class);
    } else {
      LOG.info("not enabling process killing on nailgun exit: unknown OS %s", osName);
      return;
    }

    // Making ourselves a session leader with setsid disconnects us from our controlling terminal
    int ret = Libc.INSTANCE.setsid();
    if (ret < 0) {
      LOG.warn("cannot enable background process killing: %s", Native.getLastError());
      return;
    }

    LOG.info("enabling background process killing for buckd");

    IntByReference master = new IntByReference();
    IntByReference slave = new IntByReference();

    if (openPtyLibrary.openpty(master, slave, Pointer.NULL, Pointer.NULL, Pointer.NULL) != 0) {
      throw new RuntimeException("Failed to open pty");
    }

    // Deliberately leak the file descriptors for the lifetime of this process; NuProcess can
    // sometimes leak file descriptors to children, so make sure these FDs are marked close-on-exec.
    markFdCloseOnExec(master.getValue());
    markFdCloseOnExec(slave.getValue());

    // Make the pty our controlling terminal; works because we disconnected above with setsid.
    if (Libc.INSTANCE.ioctl(slave.getValue(), Pointer.createConstant(Libc.Constants.rTIOCSCTTY), 0)
        == -1) {
      throw new RuntimeException("Failed to set pty");
    }

    LOG.info("enabled background process killing for buckd");
    isSessionLeader = true;
  }

  public static final class DaemonBootstrap {
    private static final int AFTER_COMMAND_AUTO_GC_DELAY_MS = 5000;
    private static final int SUBSEQUENT_GC_DELAY_MS = 10000;
    private static @Nullable DaemonKillers daemonKillers;
    private static AtomicInteger activeTasks = new AtomicInteger(0);

    /** Single thread for running short-lived tasks outside the command context. */
    private static final ScheduledExecutorService housekeepingExecutorService =
        Executors.newSingleThreadScheduledExecutor();

    private static final boolean isCMS =
        ManagementFactory.getGarbageCollectorMXBeans()
            .stream()
            .filter(GarbageCollectorMXBean::isValid)
            .map(GarbageCollectorMXBean::getName)
            .anyMatch(Predicate.isEqual("ConcurrentMarkSweep"));

    public static void main(String[] args) {
      try {
        daemonizeIfPossible();
        if (isSessionLeader) {
          BgProcessKiller.init();
          LOG.info("initialized bg session killer");
        }
      } catch (Throwable ex) {
        System.err.println(String.format("buckd: fatal error %s", ex));
        System.exit(1);
      }

      if (args.length != 2) {
        System.err.println("Usage: buckd socketpath heartbeatTimeout");
        return;
      }

      String socketPath = args[0];
      int heartbeatTimeout = Integer.parseInt(args[1]);
      // Strip out optional local: prefix.  This server only use domain sockets.
      if (socketPath.startsWith("local:")) {
        socketPath = socketPath.substring("local:".length());
      }
      NGServer server =
          new NGServer(
              new NGListeningAddress(socketPath),
              NGServer.DEFAULT_SESSIONPOOLSIZE,
              heartbeatTimeout);
      daemonKillers = new DaemonKillers(housekeepingExecutorService, server, Paths.get(socketPath));
      server.run();
    }

    static DaemonKillers getDaemonKillers() {
      return Preconditions.checkNotNull(daemonKillers, "Daemon killers should be initialized.");
    }

    static void commandStarted() {
      activeTasks.incrementAndGet();
    }

    static void commandFinished() {
      // Concurrent Mark and Sweep (CMS) garbage collector releases memory to operating system
      // in multiple steps, even given that full collection is performed at each step. So if CMS
      // collector is used we call System.gc() up to 4 times with some interval, and call it
      // just once for any other major collector.
      // With Java 9 we could just use -XX:-ShrinkHeapInSteps flag.
      int nTimes = isCMS ? 4 : 1;

      housekeepingExecutorService.schedule(
          () -> collectGarbage(nTimes), AFTER_COMMAND_AUTO_GC_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void collectGarbage(int nTimes) {
      int tasks = activeTasks.decrementAndGet();
      if (tasks > 0) {
        return;
      }
      // Potentially there is a race condition - new command comes exactly at this point and got
      // under GC right away. Unlucky. We ignore that.
      System.gc();

      // schedule next collection to release more memory to operating system if garbage collector
      // releases it in steps
      if (nTimes > 1) {
        activeTasks.incrementAndGet();
        housekeepingExecutorService.schedule(
            () -> collectGarbage(nTimes - 1), SUBSEQUENT_GC_DELAY_MS, TimeUnit.MILLISECONDS);
      }
    }
  }

  private static class DaemonKillers {
    private final NGServer server;
    private final IdleKiller idleKiller;
    private final SocketLossKiller unixDomainSocketLossKiller;

    DaemonKillers(ScheduledExecutorService executorService, NGServer server, Path socketPath) {
      this.server = server;
      this.idleKiller = new IdleKiller(executorService, DAEMON_SLAYER_TIMEOUT, this::killServer);
      this.unixDomainSocketLossKiller =
          Platform.detect() == Platform.WINDOWS
              ? null
              : new SocketLossKiller(
                  executorService, socketPath.toAbsolutePath(), this::killServer);
    }

    IdleKiller.CommandExecutionScope newCommandExecutionScope() {
      if (unixDomainSocketLossKiller != null) {
        unixDomainSocketLossKiller.arm(); // Arm the socket loss killer also.
      }
      return idleKiller.newCommandExecutionScope();
    }

    private void killServer() {
      server.shutdown(true);
    }
  }

  /**
   * To prevent 'buck kill' from deleting resources from underneath a 'live' buckd we hold on to the
   * FileLock for the entire lifetime of the process. We depend on the fact that on Linux and MacOS
   * Java FileLock is implemented using the same mechanism as the Python fcntl.lockf method. Should
   * this not be the case we'll simply have a small race between buckd start and `buck kill`.
   */
  private static void obtainResourceFileLock() {
    if (resourcesFileLock != null) {
      return;
    }
    String resourceLockFilePath = System.getProperties().getProperty("buck.resource_lock_path");
    if (resourceLockFilePath == null) {
      // Running from ant, no resource lock needed.
      return;
    }
    try {
      // R+W+A is equivalent to 'a+' in Python (which is how the lock file is opened in Python)
      // because WRITE in Java does not imply truncating the file.
      FileChannel fileChannel =
          FileChannel.open(
              Paths.get(resourceLockFilePath),
              StandardOpenOption.READ,
              StandardOpenOption.WRITE,
              StandardOpenOption.CREATE);
      resourcesFileLock = fileChannel.tryLock(0L, Long.MAX_VALUE, true);
    } catch (IOException | OverlappingFileLockException e) {
      LOG.debug(e, "Error when attempting to acquire resources file lock.");
    }
  }

  /**
   * When running as a daemon in the NailGun server, {@link #nailMain(NGContext)} is called instead
   * of {@link #main(String[])} so that the given context can be used to listen for client
   * disconnections and interrupt command processing when they occur.
   */
  public static void nailMain(NGContext context) {
    obtainResourceFileLock();
    try (IdleKiller.CommandExecutionScope ignored =
        DaemonBootstrap.getDaemonKillers().newCommandExecutionScope()) {
      DaemonBootstrap.commandStarted();
      new Main(context.out, context.err, context.in, Optional.of(context))
          .runMainThenExit(context.getArgs(), System.nanoTime());
    } finally {
      // Reclaim memory after a command finishes.
      DaemonBootstrap.commandFinished();
    }
  }

  /** Used to clean up the daemon after running integration tests that exercise it. */
  @VisibleForTesting
  static void resetDaemon() {
    daemonLifecycleManager.resetDaemon();
  }
}
