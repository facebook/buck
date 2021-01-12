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

package com.facebook.buck.cli;

import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;

import com.facebook.buck.artifact_cache.ArtifactCaches;
import com.facebook.buck.artifact_cache.ClientCertificateHandler;
import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig.Executor;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.command.config.ConfigDifference;
import com.facebook.buck.command.config.ConfigDifference.ConfigChange;
import com.facebook.buck.core.build.engine.cache.manager.BuildInfoStoreManager;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellName;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.InvalidCellOverrideException;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.cell.impl.LocalCellProviderFactory;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.exceptions.HumanReadableExceptionAugmentor;
import com.facebook.buck.core.exceptions.ThrowableCauseIterable;
import com.facebook.buck.core.exceptions.config.ErrorHandlingBuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.config.DepsAwareExecutorConfig;
import com.facebook.buck.core.graph.transformation.executor.factory.DepsAwareExecutorFactory;
import com.facebook.buck.core.graph.transformation.executor.factory.DepsAwareExecutorType;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationSerializer;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphFactory;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProvider;
import com.facebook.buck.core.model.impl.JsonTargetConfigurationSerializer;
import com.facebook.buck.core.model.tc.factory.TargetConfigurationFactory;
import com.facebook.buck.core.module.BuckModuleManager;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.resources.ResourcesConfig;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.core.rules.config.impl.PluginBasedKnownConfigurationDescriptionsFactory;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypesFactory;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.toolchain.ToolchainProviderFactory;
import com.facebook.buck.core.toolchain.impl.DefaultToolchainProviderFactory;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.counters.CounterBuckConfig;
import com.facebook.buck.counters.CounterRegistry;
import com.facebook.buck.counters.CounterRegistryImpl;
import com.facebook.buck.doctor.DefaultDefectReporter;
import com.facebook.buck.doctor.config.DoctorConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.BuckInitializationDurationEvent;
import com.facebook.buck.event.CacheStatsEvent;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.DaemonEvent;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.event.ExperimentEvent;
import com.facebook.buck.event.chrome_trace.ChromeTraceBuckConfig;
import com.facebook.buck.event.listener.AbstractConsoleEventBusListener;
import com.facebook.buck.event.listener.CacheRateStatsListener;
import com.facebook.buck.event.listener.ChromeTraceBuildListener;
import com.facebook.buck.event.listener.CriticalPathEventListener;
import com.facebook.buck.event.listener.FileSerializationEventBusListener;
import com.facebook.buck.event.listener.JavaUtilsLoggingBuildListener;
import com.facebook.buck.event.listener.LoadBalancerEventsListener;
import com.facebook.buck.event.listener.LogUploaderListener;
import com.facebook.buck.event.listener.LoggingBuildListener;
import com.facebook.buck.event.listener.MachineReadableLoggerListener;
import com.facebook.buck.event.listener.ParserProfilerLoggerListener;
import com.facebook.buck.event.listener.PublicAnnouncementManager;
import com.facebook.buck.event.listener.RenderingConsole;
import com.facebook.buck.event.listener.RuleKeyDiagnosticsListener;
import com.facebook.buck.event.listener.RuleKeyLoggerListener;
import com.facebook.buck.event.listener.SilentConsoleEventBusListener;
import com.facebook.buck.event.listener.SimpleConsoleEventBusListener;
import com.facebook.buck.event.listener.SuperConsoleConfig;
import com.facebook.buck.event.listener.SuperConsoleEventBusListener;
import com.facebook.buck.event.listener.interfaces.AdditionalConsoleLineProvider;
import com.facebook.buck.event.listener.util.ProgressEstimator;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.AsynchronousDirectoryContentsCleaner;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ExactPathMatcher;
import com.facebook.buck.io.filesystem.FileExtensionMatcher;
import com.facebook.buck.io.filesystem.GlobPatternMatcher;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanDiagnosticEventListener;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.io.watchman.WatchmanWatcher;
import com.facebook.buck.io.watchman.WatchmanWatcher.FreshInstanceAction;
import com.facebook.buck.io.watchman.WatchmanWatcherException;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.log.ConsoleHandlerState;
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.log.LogConfig;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserFactory;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.TargetSpecResolver;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.remoteexecution.MetadataProviderFactory;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.event.RemoteExecutionStatsProvider;
import com.facebook.buck.remoteexecution.event.listener.RemoteExecutionConsoleLineProvider;
import com.facebook.buck.remoteexecution.event.listener.RemoteExecutionEventListener;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.remoteexecution.util.MultiThreadedBlobUploader;
import com.facebook.buck.remoteexecution.util.RemoteExecutionUtil;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.keys.config.impl.ConfigRuleKeyConfigurationFactory;
import com.facebook.buck.rules.modern.config.ModernBuildRuleBuildStrategy;
import com.facebook.buck.rules.modern.config.ModernBuildRuleConfig;
import com.facebook.buck.rules.modern.config.ModernBuildRuleStrategyConfig;
import com.facebook.buck.sandbox.SandboxExecutionStrategyFactory;
import com.facebook.buck.sandbox.impl.PlatformSandboxExecutionStrategyFactory;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.support.bgtasks.TaskManagerCommandScope;
import com.facebook.buck.support.build.report.BuildReportConfig;
import com.facebook.buck.support.build.report.BuildReportFileUploader;
import com.facebook.buck.support.build.report.BuildReportUpload;
import com.facebook.buck.support.build.report.BuildReportUtils;
import com.facebook.buck.support.build.report.RuleKeyLogFileUploader;
import com.facebook.buck.support.cli.args.BuckArgsMethods;
import com.facebook.buck.support.cli.args.GlobalCliOptions;
import com.facebook.buck.support.cli.config.BuckConfigWriter;
import com.facebook.buck.support.cli.config.CliConfig;
import com.facebook.buck.support.exceptions.handler.ExceptionHandlerRegistryFactory;
import com.facebook.buck.support.fix.BuckFixSpec;
import com.facebook.buck.support.fix.FixBuckConfig;
import com.facebook.buck.support.jvm.GCNotificationEventEmitter;
import com.facebook.buck.support.log.LogBuckConfig;
import com.facebook.buck.support.state.BuckGlobalState;
import com.facebook.buck.support.state.BuckGlobalStateLifecycleManager;
import com.facebook.buck.support.state.BuckGlobalStateLifecycleManager.LifecycleStatus;
import com.facebook.buck.test.config.TestBuckConfig;
import com.facebook.buck.test.config.TestResultSummaryVerbosity;
import com.facebook.buck.util.AbstractCloseableWrapper;
import com.facebook.buck.util.BgProcessKiller;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.CloseableWrapper;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.DuplicatingConsole;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.ErrorLogger.LogImpl;
import com.facebook.buck.util.ExitCode;
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
import com.facebook.buck.util.concurrent.CommandThreadFactory;
import com.facebook.buck.util.concurrent.CommonThreadFactoryState;
import com.facebook.buck.util.concurrent.ExecutorPool;
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
import com.facebook.buck.util.randomizedtrial.RandomizedTrial;
import com.facebook.buck.util.shutdown.NonReentrantSystemExit;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.timing.NanosAdjustedClock;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.versioncontrol.DelegatingVersionControlCmdLineInterface;
import com.facebook.buck.util.versioncontrol.FullVersionControlStats;
import com.facebook.buck.util.versioncontrol.VersionControlBuckConfig;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.facebook.buck.versions.InstrumentedVersionedTargetGraphCache;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.nailgun.NGClientDisconnectReason;
import com.facebook.nailgun.NGContext;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sun.jna.Native;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.kohsuke.args4j.CmdLineException;
import org.pf4j.PluginManager;

/**
 * Responsible for running the commands logic of buck after {@link MainWithNailgun} and {@link
 * MainWithoutNailgun} have completed the initial bootstrapping of resources and state.
 *
 * <p>One instance of {@link MainRunner} exists per command run.
 */
public final class MainRunner {
  /**
   * Force JNA to be initialized early to avoid deadlock race condition.
   *
   * <p>See: https://github.com/java-native-access/jna/issues/652
   */
  public static final int JNA_POINTER_SIZE = Native.POINTER_SIZE;

  private static final Optional<String> BUCKD_LAUNCH_TIME_NANOS =
      Optional.ofNullable(System.getProperty("buck.buckd_launch_time_nanos"));

  private static final String BUCKD_COLOR_DEFAULT_ENV_VAR = "BUCKD_COLOR_DEFAULT";

  private static final Duration HANG_DETECTOR_TIMEOUT = Duration.ofMinutes(5);

  /** Path to a directory of static content that should be served by the {@link WebServer}. */
  private static final int DISK_IO_STATS_TIMEOUT_SECONDS = 10;

  private static final int EXECUTOR_SERVICES_TIMEOUT_SECONDS = 60;
  private static final int EVENT_BUS_TIMEOUT_SECONDS = 15;
  private static final int COUNTER_AGGREGATOR_SERVICE_TIMEOUT_SECONDS = 20;

  private static final int COMMAND_PATH_MAX_LENGTH = 150;

  private static final String CRITICAL_PATH_FILE_NAME = "critical_path.log";

  private final InputStream stdIn;

  private final Architecture architecture;

  private static final Semaphore commandSemaphore = new Semaphore(1);
  private static final AtomicReference<ImmutableList<String>> activeCommandArgs =
      new AtomicReference<>();

  private static volatile Optional<NGContext> commandSemaphoreNgClient = Optional.empty();

  private static final BuckGlobalStateLifecycleManager buckGlobalStateLifecycleManager =
      new BuckGlobalStateLifecycleManager();

  // Ensure we only have one instance of this, so multiple trash cleaning
  // operations are serialized on one queue.
  private static final AsynchronousDirectoryContentsCleaner TRASH_CLEANER =
      new AsynchronousDirectoryContentsCleaner();

  private DuplicatingConsole printConsole;

  private Optional<NGContext> context;

  // Ignore changes to generated Xcode project files and editors' backup files
  // so we don't dump buckd caches on every command.
  private static final ImmutableSet<PathMatcher> DEFAULT_IGNORE_GLOBS =
      ImmutableSet.of(
          FileExtensionMatcher.of("pbxproj"),
          FileExtensionMatcher.of("xcscheme"),
          FileExtensionMatcher.of("xcworkspacedata"),
          // Various editors' temporary files
          GlobPatternMatcher.of("**/*~"),
          // Emacs
          GlobPatternMatcher.of("**/#*#"),
          GlobPatternMatcher.of("**/.#*"),
          // Vim
          FileExtensionMatcher.of("swo"),
          FileExtensionMatcher.of("swp"),
          FileExtensionMatcher.of("swpx"),
          FileExtensionMatcher.of("un~"),
          FileExtensionMatcher.of("netrhwist"),
          // Eclipse
          ExactPathMatcher.of(".idea"),
          ExactPathMatcher.of(".iml"),
          FileExtensionMatcher.of("pydevproject"),
          ExactPathMatcher.of(".project"),
          ExactPathMatcher.of(".metadata"),
          FileExtensionMatcher.of("tmp"),
          FileExtensionMatcher.of("bak"),
          FileExtensionMatcher.of("nib"),
          ExactPathMatcher.of(".classpath"),
          ExactPathMatcher.of(".settings"),
          ExactPathMatcher.of(".loadpath"),
          ExactPathMatcher.of(".externalToolBuilders"),
          ExactPathMatcher.of(".cproject"),
          ExactPathMatcher.of(".buildpath"),
          // Mac OS temp files
          ExactPathMatcher.of(".DS_Store"),
          ExactPathMatcher.of(".AppleDouble"),
          ExactPathMatcher.of(".LSOverride"),
          ExactPathMatcher.of(".Spotlight-V100"),
          ExactPathMatcher.of(".Trashes"),
          // Windows
          ExactPathMatcher.of("$RECYCLE.BIN"),
          // Sublime
          FileExtensionMatcher.of("sublime-workspace"));

  private static final Logger LOG = Logger.get(MainRunner.class);

  private static final HangMonitor.AutoStartInstance HANG_MONITOR =
      new HangMonitor.AutoStartInstance(
          (input) ->
              LOG.info(
                  "No recent activity, dumping thread stacks (`tr , '\\n'` to decode): %s", input),
          HANG_DETECTOR_TIMEOUT);

  private static final NonReentrantSystemExit NON_REENTRANT_SYSTEM_EXIT =
      new NonReentrantSystemExit();

  public interface KnownRuleTypesFactoryFactory {

    KnownNativeRuleTypesFactory create(
        ProcessExecutor executor,
        PluginManager pluginManager,
        SandboxExecutionStrategyFactory sandboxExecutionStrategyFactory,
        ImmutableList<ConfigurationRuleDescription<?, ?>> knownConfigurationDescriptions);
  }

  private final KnownRuleTypesFactoryFactory knownRuleTypesFactoryFactory;
  private final ImmutableMap<String, String> clientEnvironment;
  private final Platform platform;
  private final Path projectRoot;

  private final PluginManager pluginManager;
  private final BuckModuleManager moduleManager;

  private final BuildId buildId;

  private final CommandMode commandMode;

  private final BackgroundTaskManager bgTaskManager;

  private Optional<BuckConfig> parsedRootConfig = Optional.empty();

  private Optional<StackedFileHashCache> stackedFileHashCache = Optional.empty();

  static {
    MacIpv6BugWorkaround.apply();
  }

  /**
   * This constructor allows integration tests to add/remove/modify known build rules (aka
   * descriptions).
   *
   * @param console the {@link Console} to print to for this command
   * @param stdIn the input stream to the command being ran
   * @param knownRuleTypesFactoryFactory the known rule types for this command
   * @param buildId the {@link BuildId} for this command
   * @param clientEnvironment the environment variable map for this command
   * @param platform the current running {@link Platform}
   * @param projectRoot the project root of the current command
   * @param pluginManager the {@link PluginManager} for this command
   * @param moduleManager the {@link BuckModuleManager} for this command
   * @param context the {@link NGContext} from nailgun for this command
   */
  @VisibleForTesting
  public MainRunner(
      Console console,
      InputStream stdIn,
      KnownRuleTypesFactoryFactory knownRuleTypesFactoryFactory,
      BuildId buildId,
      ImmutableMap<String, String> clientEnvironment,
      Platform platform,
      Path projectRoot,
      PluginManager pluginManager,
      BuckModuleManager moduleManager,
      BackgroundTaskManager bgTaskManager,
      CommandMode commandMode,
      Optional<NGContext> context) {
    this.printConsole = new DuplicatingConsole(console);
    this.stdIn = stdIn;
    this.knownRuleTypesFactoryFactory = knownRuleTypesFactoryFactory;
    this.projectRoot = projectRoot;
    this.pluginManager = pluginManager;
    this.moduleManager = moduleManager;
    this.bgTaskManager = bgTaskManager;
    this.architecture = Architecture.detect();
    this.buildId = buildId;
    this.clientEnvironment = clientEnvironment;
    this.platform = platform;
    this.context = context;
    this.commandMode = commandMode;
  }

  /* Define all error handling surrounding main command */
  void runMainThenExit(String[] args, long initTimestamp) {

    ExitCode exitCode = ExitCode.SUCCESS;

    try {
      installUncaughtExceptionHandler(context);

      // Only post an overflow event if Watchman indicates a fresh instance event
      // after our initial query.
      WatchmanWatcher.FreshInstanceAction watchmanFreshInstanceAction =
          buckGlobalStateLifecycleManager.hasStoredBuckGlobalState()
              ? WatchmanWatcher.FreshInstanceAction.POST_OVERFLOW_EVENT
              : WatchmanWatcher.FreshInstanceAction.NONE;

      exitCode =
          runMainWithExitCode(
              watchmanFreshInstanceAction, initTimestamp, ImmutableList.copyOf(args));
    } catch (Throwable t) {
      try {
        HumanReadableExceptionAugmentor augmentor;
        try {
          augmentor =
              new HumanReadableExceptionAugmentor(
                  parsedRootConfig
                      .map(buckConfig -> buckConfig.getView(ErrorHandlingBuckConfig.class))
                      .map(ErrorHandlingBuckConfig::getErrorMessageAugmentations)
                      .orElse(ImmutableMap.of()));
        } catch (HumanReadableException e) {
          printConsole.printErrorText(e.getHumanReadableErrorMessage());
          augmentor = new HumanReadableExceptionAugmentor(ImmutableMap.of());
        }
        ErrorLogger logger =
            new ErrorLogger(
                new LogImpl() {
                  @Override
                  public void logUserVisible(String message) {
                    printConsole.printFailure(message);
                  }

                  @Override
                  public void logUserVisibleInternalError(String message) {
                    printConsole.printFailure(
                        linesToText("Buck encountered an internal error", message));
                  }

                  @Override
                  public void logVerbose(Throwable e) {
                    String message = "Command failed:";
                    if (e instanceof InterruptedException
                        || e instanceof ClosedByInterruptException) {
                      message = "Command was interrupted:";
                    }
                    LOG.warn(e, message);
                  }
                },
                augmentor);
        logger.logException(t);

        if (MultiThreadedBlobUploader.CorruptArtifactException.isCause(ThrowableCauseIterable.of(t))
            && stackedFileHashCache.isPresent()) {
          LOG.error(
              "Command failed due to CorruptArtifactException."
                  + "\nThis indicates something outside of buck's control has modified files in an unexpected way."
                  + "\nThe hash for file %s was invalid."
                  + "\nPurging file hash caches. Try re-running the build.",
              (MultiThreadedBlobUploader.CorruptArtifactException.getDescription(
                      ThrowableCauseIterable.of(t)))
                  .orElse(""));
          stackedFileHashCache.get().invalidateAll();
        }
      } catch (Throwable ignored) {
        // In some very rare cases error processing may error itself
        // One example is logger implementation to throw while trying to log the error message
        // Even for this case, we want proper exit code to be emitted, so we print the original
        // exception to stderr as a last resort
        System.err.println(t.getMessage());
        t.printStackTrace();
      }
      exitCode = ExceptionHandlerRegistryFactory.create().handleException(t);
    } finally {
      LOG.debug("Done.");
      LogConfig.flushLogs();
      // Exit explicitly so that non-daemon threads (of which we use many) don't
      // keep the VM alive.
      System.exit(exitCode.getCode());
    }
  }

  private void setupLogging(BuckCommand command, ImmutableList<String> args) throws IOException {
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

  private ImmutableMap<CellName, AbsPath> getCellMapping(AbsPath canonicalRootPath)
      throws IOException {
    return DefaultCellPathResolver.bootstrapPathMapping(
        canonicalRootPath, Configs.createDefaultConfig(canonicalRootPath.getPath()));
  }

  private Config setupDefaultConfig(
      ImmutableMap<CellName, AbsPath> cellMapping, BuckCommand command) throws IOException {
    AbsPath rootPath =
        Objects.requireNonNull(
            cellMapping.get(CellName.ROOT_CELL_NAME), "Root cell should be implicitly added");
    RawConfig rootCellConfigOverrides;

    try {
      ImmutableMap<AbsPath, RawConfig> overridesByPath =
          command.getConfigOverrides(cellMapping).getOverridesByPath(cellMapping);
      rootCellConfigOverrides =
          Optional.ofNullable(overridesByPath.get(rootPath)).orElse(RawConfig.of());
    } catch (InvalidCellOverrideException exception) {
      rootCellConfigOverrides =
          command.getConfigOverrides(cellMapping).getForCell(CellName.ROOT_CELL_NAME);
    }
    if (commandMode == CommandMode.TEST) {
      // test mode: we skip looking into /etc and /home for config files for determinism
      return Configs.createDefaultConfig(
          rootPath.getPath(),
          Configs.getRepoConfigurationFiles(rootPath.getPath()),
          rootCellConfigOverrides);
    }

    return Configs.createDefaultConfig(rootPath.getPath(), rootCellConfigOverrides);
  }

  private ImmutableSet<AbsPath> getProjectWatchList(
      AbsPath canonicalRootPath, BuckConfig buckConfig, DefaultCellPathResolver cellPathResolver) {
    return ImmutableSet.<AbsPath>builder()
        .add(canonicalRootPath)
        .addAll(
            buckConfig.getView(ParserConfig.class).getWatchCells()
                ? cellPathResolver.getPathMapping().values()
                : ImmutableList.of())
        .build();
  }

  /**
   * @param initTimestamp Value of System.nanoTime() when process got main()/nailMain() invoked.
   * @param unexpandedCommandLineArgs command line arguments
   * @return an ExitCode representing the result of the command
   */
  @SuppressWarnings("PMD.PrematureDeclaration")
  public ExitCode runMainWithExitCode(
      WatchmanWatcher.FreshInstanceAction watchmanFreshInstanceAction,
      long initTimestamp,
      ImmutableList<String> unexpandedCommandLineArgs)
      throws Exception {

    // Set initial exitCode value to FATAL. This will eventually get reassigned unless an exception
    // happens
    ExitCode exitCode = ExitCode.FATAL_GENERIC;

    // Setup filesystem and buck config.
    AbsPath canonicalRootPath = AbsPath.of(projectRoot.toRealPath()).normalize();
    ImmutableMap<CellName, AbsPath> rootCellMapping = getCellMapping(canonicalRootPath);
    ImmutableList<String> args =
        BuckArgsMethods.expandAtFiles(unexpandedCommandLineArgs, rootCellMapping);

    // Filter out things like --command-args-file from the arguments lists that we log
    ImmutableList<String> filteredUnexpandedArgsForLogging =
        filterArgsForLogging(unexpandedCommandLineArgs);
    ImmutableList<String> filteredArgsForLogging = filterArgsForLogging(args);

    // Parse command line arguments
    BuckCommand command = new BuckCommand();
    command.setPluginManager(pluginManager);
    // Parse the command line args.
    AdditionalOptionsCmdLineParser cmdLineParser =
        new AdditionalOptionsCmdLineParser(pluginManager, command);
    try {
      cmdLineParser.parseArgument(args);
    } catch (CmdLineException e) {
      throw new CommandLineException(e, e.getLocalizedMessage() + "\nFor help see 'buck --help'.");
    }

    // Return help strings fast if the command is a help request.
    Optional<ExitCode> result = command.runHelp(printConsole.getStdOut());
    if (result.isPresent()) {
      return result.get();
    }

    // If this command is not read only, acquire the command semaphore to become the only executing
    // read/write command. Early out will also help to not rotate log on each BUSY status which
    // happens in setupLogging().
    ImmutableList.Builder<String> previousCommandArgsBuilder = new ImmutableList.Builder<>();
    try (CloseableWrapper<Semaphore> semaphore =
        getSemaphoreWrapper(command, unexpandedCommandLineArgs, previousCommandArgsBuilder)) {
      if (!command.isReadOnly() && semaphore == null) {
        // buck_tool will set BUCK_BUSY_DISPLAYED if it already displayed the busy error
        if (!clientEnvironment.containsKey("BUCK_BUSY_DISPLAYED")) {
          String activeCommandLine = "buck " + String.join(" ", previousCommandArgsBuilder.build());
          if (activeCommandLine.length() > COMMAND_PATH_MAX_LENGTH) {
            String ending = "...";
            activeCommandLine =
                activeCommandLine.substring(0, COMMAND_PATH_MAX_LENGTH - ending.length()) + ending;
          }

          System.err.println(
              String.format("Buck Daemon is busy executing '%s'.", activeCommandLine));
          LOG.info(
              "Buck server was busy executing '%s'. Maybe retrying later will help.",
              activeCommandLine);
        }
        return ExitCode.BUSY;
      }

      // statically configure Buck logging environment based on Buck config, usually buck-x.log
      // files
      setupLogging(command, filteredArgsForLogging);

      ProjectFilesystemFactory projectFilesystemFactory = new DefaultProjectFilesystemFactory();
      UnconfiguredBuildTargetViewFactory buildTargetFactory =
          new ParsingUnconfiguredBuildTargetViewFactory();

      Config currentConfig = setupDefaultConfig(rootCellMapping, command);
      Config config;
      ProjectFilesystem filesystem;
      DefaultCellPathResolver cellPathResolver;
      BuckConfig buckConfig;

      boolean reusePreviousConfig =
          isReuseCurrentConfigPropertySet(command)
              && buckGlobalStateLifecycleManager.hasStoredBuckGlobalState();
      if (reusePreviousConfig) {
        printWarnMessage(UIMessagesFormatter.reuseConfigPropertyProvidedMessage());
        buckConfig =
            buckGlobalStateLifecycleManager
                .getBuckConfig()
                .orElseThrow(
                    () -> new IllegalStateException("Daemon is present but config is missing."));
        config = buckConfig.getConfig();
        filesystem = buckConfig.getFilesystem();
        cellPathResolver = DefaultCellPathResolver.create(filesystem.getRootPath(), config);

        Map<String, ConfigChange> configDiff = ConfigDifference.compare(config, currentConfig);
        UIMessagesFormatter.reusedConfigWarning(configDiff).ifPresent(this::printWarnMessage);
      } else {
        config = currentConfig;
        filesystem =
            projectFilesystemFactory.createProjectFilesystem(
                CanonicalCellName.rootCell(),
                canonicalRootPath,
                config,
                BuckPaths.getBuckOutIncludeTargetConfigHashFromRootCellConfig(config));
        cellPathResolver = DefaultCellPathResolver.create(filesystem.getRootPath(), config);
        buckConfig =
            new BuckConfig(
                config,
                filesystem,
                architecture,
                platform,
                clientEnvironment,
                buildTargetName ->
                    buildTargetFactory.create(
                        buildTargetName, cellPathResolver.getCellNameResolver()));
      }

      // Set so that we can use some settings when we print out messages to users
      parsedRootConfig = Optional.of(buckConfig);
      CliConfig cliConfig = buckConfig.getView(CliConfig.class);
      // if we are reusing previous configuration then no need to warn about config override
      if (!reusePreviousConfig) {
        warnAboutConfigFileOverrides(filesystem.getRootPath(), cliConfig);
      }

      ImmutableSet<AbsPath> projectWatchList =
          getProjectWatchList(canonicalRootPath, buckConfig, cellPathResolver);

      Verbosity verbosity = VerbosityParser.parse(args);

      // Setup the printConsole.
      printConsole = makeCustomConsole(context, verbosity, buckConfig);

      ExecutionEnvironment executionEnvironment =
          new DefaultExecutionEnvironment(clientEnvironment, System.getProperties());

      // Automatically use distributed build for supported repositories and users, unless
      // Remote Execution is in use. All RE builds should not use distributed build.
      final boolean isRemoteExecutionBuild =
          isRemoteExecutionBuild(command, buckConfig, executionEnvironment.getUsername());
      Optional<String> projectPrefix = Optional.empty();
      if (command.subcommand instanceof BuildCommand) {
        BuildCommand subcommand = (BuildCommand) command.subcommand;
        executionEnvironment.getUsername();

        projectPrefix =
            RemoteExecutionUtil.getCommonProjectPrefix(subcommand.getArguments(), buckConfig);
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
                    ^ buckConfig.getView(BuildBuckConfig.class).getBuckOutCompatLink()))) {
          // Migrate any version-dependent directories (which might be huge) to a trash directory
          // so we can delete it asynchronously after the command is done.
          moveToTrash(
              filesystem,
              printConsole,
              buildId,
              filesystem.getBuckPaths().getAnnotationDir(),
              filesystem.getBuckPaths().getGenDir(),
              filesystem.getBuckPaths().getScratchDir(),
              filesystem.getBuckPaths().getResDir());
          filesystem.mkdirs(filesystem.getBuckPaths().getCurrentVersionFile().getParent());
          filesystem.writeContentsToPath(
              ruleKeyConfiguration.getCoreKey(), filesystem.getBuckPaths().getCurrentVersionFile());
        }
      } else {
        previousBuckCoreKey = "";
      }

      LOG.verbose("Buck core key from the previous Buck instance: %s", previousBuckCoreKey);

      ProcessExecutor processExecutor = new DefaultProcessExecutor(printConsole);

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
          buildWatchman(
              context, parserConfig, projectWatchList, clientEnvironment, printConsole, clock);

      ImmutableList<ConfigurationRuleDescription<?, ?>> knownConfigurationDescriptions =
          PluginBasedKnownConfigurationDescriptionsFactory.createFromPlugins(pluginManager);

      DefaultCellPathResolver rootCellCellPathResolver =
          DefaultCellPathResolver.create(filesystem.getRootPath(), buckConfig.getConfig());

      TargetConfigurationFactory targetConfigurationFactory =
          new TargetConfigurationFactory(buildTargetFactory, cellPathResolver);

      Optional<TargetConfiguration> targetConfiguration =
          createTargetConfiguration(command, buckConfig, targetConfigurationFactory);
      Optional<TargetConfiguration> hostConfiguration =
          createHostConfiguration(command, buckConfig, targetConfigurationFactory);

      // NOTE: This new KnownUserDefinedRuleTypes is only used if BuckGlobals need to be invalidated
      // Otherwise, everything should use the KnownUserDefinedRuleTypes object from BuckGlobals
      KnownRuleTypesProvider knownRuleTypesProvider =
          new KnownRuleTypesProvider(
              knownRuleTypesFactoryFactory.create(
                  processExecutor,
                  pluginManager,
                  sandboxExecutionStrategyFactory,
                  knownConfigurationDescriptions));

      ExecutableFinder executableFinder = new ExecutableFinder();

      ToolchainProviderFactory toolchainProviderFactory =
          new DefaultToolchainProviderFactory(
              pluginManager, clientEnvironment, processExecutor, executableFinder);

      Cells cells =
          new Cells(
              LocalCellProviderFactory.create(
                      filesystem,
                      buckConfig,
                      command.getConfigOverrides(rootCellMapping),
                      rootCellCellPathResolver.getPathMapping(),
                      rootCellCellPathResolver,
                      moduleManager,
                      toolchainProviderFactory,
                      projectFilesystemFactory,
                      buildTargetFactory)
                  .getCellByPath(filesystem.getRootPath()));

      TargetConfigurationSerializer targetConfigurationSerializer =
          new JsonTargetConfigurationSerializer(
              targetName ->
                  buildTargetFactory.create(targetName, cells.getRootCell().getCellNameResolver()));

      Pair<BuckGlobalState, LifecycleStatus> buckGlobalStateRequest =
          buckGlobalStateLifecycleManager.getBuckGlobalState(
              cells,
              knownRuleTypesProvider,
              watchman,
              printConsole,
              clock,
              buildTargetFactory,
              targetConfigurationSerializer);

      BuckGlobalState buckGlobalState = buckGlobalStateRequest.getFirst();
      LifecycleStatus stateLifecycleStatus = buckGlobalStateRequest.getSecond();

      if (!context.isPresent()) {
        // Clean up the trash on a background thread if this was a
        // non-buckd read-write command. (We don't bother waiting
        // for it to complete; the thread is a daemon thread which
        // will just be terminated at shutdown time.)
        TRASH_CLEANER.startCleaningDirectory(
            filesystem.resolve(filesystem.getBuckPaths().getTrashDir()));
      }

      ImmutableList<BuckEventListener> eventListeners = ImmutableList.of();

      ImmutableList.Builder<ProjectFileHashCache> allCaches = ImmutableList.builder();

      // Build up the hash cache, which is a collection of the stateful cell cache and some
      // per-run caches.
      //
      // TODO(coneko, ruibm, agallagher): Determine whether we can use the existing filesystem
      // object that is in scope instead of creating a new rootCellProjectFilesystem. The primary
      // difference appears to be that filesystem is created with a Config that is used to produce
      // ImmutableSet<PathMatcher> and BuckPaths for the ProjectFilesystem, whereas this one
      // uses the defaults.
      ProjectFilesystem rootCellProjectFilesystem =
          projectFilesystemFactory.createOrThrow(
              CanonicalCellName.rootCell(),
              cells.getRootCell().getFilesystem().getRootPath(),
              BuckPaths.getBuckOutIncludeTargetConfigHashFromRootCellConfig(config));
      BuildBuckConfig buildBuckConfig =
          cells.getRootCell().getBuckConfig().getView(BuildBuckConfig.class);
      allCaches.addAll(buckGlobalState.getFileHashCaches());

      cells
          .getAllCells()
          .forEach(
              cell -> {
                if (cell.getCanonicalName() != CanonicalCellName.rootCell()) {
                  allCaches.add(
                      DefaultFileHashCache.createBuckOutFileHashCache(
                          cell.getFilesystem(), buildBuckConfig.getFileHashCacheMode()));
                }
              });

      // A cache which caches hashes of cell-relative paths which may have been ignore by
      // the main cell cache, and only serves to prevent rehashing the same file multiple
      // times in a single run.
      allCaches.add(
          DefaultFileHashCache.createDefaultFileHashCache(
              rootCellProjectFilesystem, buildBuckConfig.getFileHashCacheMode()));
      allCaches.addAll(
          DefaultFileHashCache.createOsRootDirectoriesCaches(
              projectFilesystemFactory, buildBuckConfig.getFileHashCacheMode()));

      StackedFileHashCache fileHashCache = new StackedFileHashCache(allCaches.build());
      stackedFileHashCache = Optional.of(fileHashCache);

      Optional<WebServer> webServer = buckGlobalState.getWebServer();
      ConcurrentMap<String, WorkerProcessPool> persistentWorkerPools =
          buckGlobalState.getPersistentWorkerPools();
      TestBuckConfig testConfig = buckConfig.getView(TestBuckConfig.class);
      ArtifactCacheBuckConfig cacheBuckConfig = new ArtifactCacheBuckConfig(buckConfig);

      SuperConsoleConfig superConsoleConfig = new SuperConsoleConfig(buckConfig);

      // Eventually, we'll want to get allow websocket and/or nailgun clients to specify locale
      // when connecting. For now, we'll use the default from the server environment.
      Locale locale = Locale.getDefault();

      InvocationInfo invocationInfo =
          InvocationInfo.of(
              buildId,
              superConsoleConfig.isEnabled(printConsole.getAnsi(), printConsole.getVerbosity()),
              context.isPresent(),
              command.getSubCommandNameForLogging(),
              filteredArgsForLogging,
              filteredUnexpandedArgsForLogging,
              filesystem.getBuckPaths().getLogDir(),
              isRemoteExecutionBuild,
              cacheBuckConfig.getRepository(),
              watchman.getVersion());

      RemoteExecutionConfig remoteExecutionConfig = buckConfig.getView(RemoteExecutionConfig.class);
      if (isRemoteExecutionBuild) {
        remoteExecutionConfig.validateCertificatesOrThrow();
      }

      Optional<RemoteExecutionEventListener> remoteExecutionListener =
          remoteExecutionConfig.isConsoleEnabled()
              ? Optional.of(new RemoteExecutionEventListener())
              : Optional.empty();
      MetadataProvider metadataProvider =
          MetadataProviderFactory.minimalMetadataProviderForBuild(
              buildId,
              executionEnvironment.getUsername(),
              cacheBuckConfig.getRepository(),
              cacheBuckConfig.getScheduleType(),
              remoteExecutionConfig.getReSessionLabel(),
              remoteExecutionConfig.getTenantId(),
              remoteExecutionConfig.getAuxiliaryBuildTag(),
              projectPrefix.orElse(""),
              executionEnvironment);

      LogBuckConfig logBuckConfig = buckConfig.getView(LogBuckConfig.class);

      try (TaskManagerCommandScope managerScope =
              bgTaskManager.getNewScope(
                  buildId,
                  !context.isPresent()
                      || cells
                          .getRootCell()
                          .getBuckConfig()
                          .getView(CliConfig.class)
                          .getFlushEventsBeforeExit());
          GlobalStateManager.LoggerIsMappedToThreadScope loggerThreadMappingScope =
              GlobalStateManager.singleton()
                  .setupLoggers(
                      invocationInfo,
                      printConsole.getStdErr(),
                      printConsole.getStdErr().getRawStream(),
                      verbosity);
          DefaultBuckEventBus buildEventBus = new DefaultBuckEventBus(clock, buildId);
          ) {
        BuckConfigWriter.writeConfig(
            filesystem.getRootPath().getPath(), invocationInfo, buckConfig);

        CommonThreadFactoryState commonThreadFactoryState =
            GlobalStateManager.singleton().getThreadToCommandRegister();

        Optional<Exception> exceptionForFix = Optional.empty();
        Path simpleConsoleLog =
            invocationInfo
                .getLogDirectoryPath()
                .resolve(BuckConstant.BUCK_SIMPLE_CONSOLE_LOG_FILE_NAME);

        PrintStream simpleConsolePrintStream = new PrintStream(simpleConsoleLog.toFile());
        Console simpleLogConsole =
            new Console(
                Verbosity.STANDARD_INFORMATION,
                simpleConsolePrintStream,
                simpleConsolePrintStream,
                printConsole.getAnsi());
        printConsole.setDuplicatingConsole(Optional.of(simpleLogConsole));
        Path testLogPath = filesystem.getBuckPaths().getLogDir().resolve("test.log");
        try (ThrowingCloseableWrapper<ExecutorService, InterruptedException> diskIoExecutorService =
                getExecutorWrapper(
                    MostExecutors.newSingleThreadExecutor("Disk I/O"),
                    "Disk IO",
                    DISK_IO_STATS_TIMEOUT_SECONDS);
            ThrowingCloseableWrapper<ListeningExecutorService, InterruptedException>
                httpWriteExecutorService =
                    getExecutorWrapper(
                        getHttpWriteExecutorService(cacheBuckConfig),
                        "HTTP Write",
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
                            new CommandThreadFactory(
                                "CounterAggregatorThread", commonThreadFactoryState)),
                        "CounterAggregatorExecutor",
                        COUNTER_AGGREGATOR_SERVICE_TIMEOUT_SECONDS);
            ThrowingCloseableWrapper<ScheduledExecutorService, InterruptedException>
                scheduledExecutorPool =
                    getExecutorWrapper(
                        Executors.newScheduledThreadPool(
                            buckConfig
                                .getView(BuildBuckConfig.class)
                                .getNumThreadsForSchedulerPool(),
                            new CommandThreadFactory(
                                getClass().getName() + "SchedulerThreadPool",
                                commonThreadFactoryState)),
                        "ScheduledExecutorService",
                        EXECUTOR_SERVICES_TIMEOUT_SECONDS);
            // Create a cached thread pool for cpu intensive tasks
            ThrowingCloseableWrapper<ListeningExecutorService, InterruptedException>
                cpuExecutorService =
                    getExecutorWrapper(
                        listeningDecorator(Executors.newCachedThreadPool()),
                        ExecutorPool.CPU.toString(),
                        EXECUTOR_SERVICES_TIMEOUT_SECONDS);
            // Create a cached thread pool for cpu intensive tasks
            ThrowingCloseableWrapper<ListeningExecutorService, InterruptedException>
                graphCpuExecutorService =
                    getExecutorWrapper(
                        listeningDecorator(
                            MostExecutors.newMultiThreadExecutor(
                                "graph-cpu",
                                buckConfig.getView(BuildBuckConfig.class).getNumThreads())),
                        ExecutorPool.GRAPH_CPU.toString(),
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
                                "Project",
                                buckConfig.getView(BuildBuckConfig.class).getNumThreads())),
                        ExecutorPool.PROJECT.toString(),
                        EXECUTOR_SERVICES_TIMEOUT_SECONDS);
            BuildInfoStoreManager storeManager = new BuildInfoStoreManager();
            AbstractConsoleEventBusListener fileLoggerConsoleListener =
                new SimpleConsoleEventBusListener(
                    new RenderingConsole(clock, simpleLogConsole),
                    clock,
                    testConfig.getResultSummaryVerbosity(),
                    superConsoleConfig.getHideSucceededRulesInLogMode(),
                    superConsoleConfig.getNumberOfSlowRulesToShow(),
                    true, // Always show slow rules in File Logger Console
                    locale,
                    testLogPath,
                    executionEnvironment,
                    buildId,
                    logBuckConfig.isLogBuildIdToConsoleEnabled(),
                    logBuckConfig.getBuildDetailsTemplate(),
                    logBuckConfig.getBuildDetailsCommands(),
                    isRemoteExecutionBuild
                        ? Optional.of(
                            remoteExecutionConfig.getDebugURLString(
                                metadataProvider.get().getReSessionId()))
                        : Optional.empty(),
                    createAdditionalConsoleLinesProviders(
                        remoteExecutionListener, remoteExecutionConfig, metadataProvider));
            AbstractConsoleEventBusListener consoleListener =
                createConsoleEventListener(
                    clock,
                    superConsoleConfig,
                    printConsole,
                    testConfig.getResultSummaryVerbosity(),
                    executionEnvironment,
                    locale,
                    testLogPath,
                    logBuckConfig.isLogBuildIdToConsoleEnabled(),
                    logBuckConfig.getBuildDetailsTemplate(),
                    logBuckConfig.getBuildDetailsCommands(),
                    createAdditionalConsoleLinesProviders(
                        remoteExecutionListener, remoteExecutionConfig, metadataProvider),
                    isRemoteExecutionBuild ? Optional.of(remoteExecutionConfig) : Optional.empty(),
                    metadataProvider);
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
                    buckConfig
                        .getView(CounterBuckConfig.class)
                        .getCountersFirstFlushIntervalMillis(),
                    buckConfig.getView(CounterBuckConfig.class).getCountersFlushIntervalMillis());
            PerfStatsTracking perfStatsTracking =
                new PerfStatsTracking(buildEventBus, invocationInfo);
            ProcessTracker processTracker =
                logBuckConfig.isProcessTrackerEnabled() && platform != Platform.WINDOWS
                    ? new ProcessTracker(
                        buildEventBus,
                        invocationInfo,
                        context.isPresent(),
                        logBuckConfig.isProcessTrackerDeepEnabled())
                    : null;
            ArtifactCaches artifactCacheFactory =
                new ArtifactCaches(
                    cacheBuckConfig,
                    buildEventBus,
                    target ->
                        buildTargetFactory.create(target, cellPathResolver.getCellNameResolver()),
                    targetConfigurationSerializer,
                    filesystem,
                    executionEnvironment.getWifiSsid(),
                    httpWriteExecutorService.get(),
                    httpFetchExecutorService.get(),
                    getDirCacheStoreExecutor(cacheBuckConfig, diskIoExecutorService),
                    managerScope,
                    getArtifactProducerId(executionEnvironment),
                    executionEnvironment.getHostname(),
                    ClientCertificateHandler.fromConfiguration(cacheBuckConfig));

            // Once command completes it should be safe to not wait for executors and other stateful
            // objects to terminate and release semaphore right away. It will help to retry
            // command faster if user terminated with Ctrl+C.
            // Ideally, we should come up with a better lifecycle management strategy for the
            // semaphore object
            CloseableWrapper<Optional<CloseableWrapper<Semaphore>>> semaphoreCloser =
                CloseableWrapper.of(
                    Optional.ofNullable(semaphore),
                    s -> {
                      s.ifPresent(AbstractCloseableWrapper::close);
                    });
            CloseableMemoizedSupplier<DepsAwareExecutor<? super ComputeResult, ?>>
                depsAwareExecutorSupplier =
                    getDepsAwareExecutorSupplier(buckConfig, buildEventBus);

            // This will get executed first once it gets out of try block and just wait for
            // event bus to dispatch all pending events before we proceed to termination
            // procedures
            CloseableWrapper<BuckEventBus> waitEvents = getWaitEventsWrapper(buildEventBus)) {

          LOG.debug(invocationInfo.toLogLine());

          buildEventBus.register(HANG_MONITOR.getHangMonitor());

          if (logBuckConfig.isJavaGCEventLoggingEnabled()) {
            // Register for GC events to be published to the event bus.
            GCNotificationEventEmitter.register(buildEventBus);
          }

          ImmutableMap<ExecutorPool, ListeningExecutorService> executors =
              ImmutableMap.of(
                  ExecutorPool.CPU,
                  cpuExecutorService.get(),
                  ExecutorPool.GRAPH_CPU,
                  graphCpuExecutorService.get(),
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
              && (command.performsBuild()
                  || command.subcommand instanceof ProjectCommand
                  || command.subcommand instanceof AbstractQueryCommand)) {
            boolean persistent = !(command.subcommand instanceof AbstractQueryCommand);
            ProgressEstimator progressEstimator =
                new ProgressEstimator(
                    persistent
                        ? Optional.of(
                            filesystem
                                .resolve(filesystem.getBuckPaths().getBuckOut())
                                .resolve(ProgressEstimator.PROGRESS_ESTIMATIONS_JSON))
                        : Optional.empty(),
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

          if (isRemoteExecutionBuild) {
            List<BuckEventListener> remoteExecutionsListeners = Lists.newArrayList();
            remoteExecutionListener.ifPresent(remoteExecutionsListeners::add);


            commandEventListeners =
                new ImmutableList.Builder<BuckEventListener>()
                    .addAll(commandEventListeners)
                    .addAll(remoteExecutionsListeners)
                    .build();
          }

          eventListeners =
              addEventListeners(
                  buildEventBus,
                  cells.getRootCell().getFilesystem(),
                  invocationInfo,
                  cells.getRootCell().getBuckConfig(),
                  webServer,
                  clock,
                  executionEnvironment,
                  counterRegistry,
                  commandEventListeners,
                  remoteExecutionListener.isPresent()
                      ? Optional.of(remoteExecutionListener.get())
                      : Optional.empty(),
                  managerScope);
          consoleListener.register(buildEventBus);
          fileLoggerConsoleListener.register(buildEventBus);

          if (logBuckConfig.isBuckConfigLocalWarningEnabled()
              && !printConsole.getVerbosity().isSilent()) {
            ImmutableList<AbsPath> localConfigFiles =
                cells.getAllCells().stream()
                    .map(
                        cell ->
                            cell.getRoot().resolve(Configs.DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME))
                    .filter(path -> Files.isRegularFile(path.getPath()))
                    .collect(ImmutableList.toImmutableList());
            if (localConfigFiles.size() > 0) {
              String message =
                  localConfigFiles.size() == 1
                      ? "Using local configuration:"
                      : "Using local configurations:";
              buildEventBus.post(ConsoleEvent.warning(message));
              for (AbsPath localConfigFile : localConfigFiles) {
                buildEventBus.post(ConsoleEvent.warning(String.format("- %s", localConfigFile)));
              }
            }
          }

          if (commandMode == CommandMode.RELEASE && logBuckConfig.isPublicAnnouncementsEnabled()) {
            PublicAnnouncementManager announcementManager =
                new PublicAnnouncementManager(
                    clock,
                    buildEventBus,
                    consoleListener,
                    new RemoteLogBuckConfig(buckConfig),
                    Objects.requireNonNull(executors.get(ExecutorPool.CPU)));
            announcementManager.getAndPostAnnouncements();
          }

          // This needs to be after the registration of the event listener so they can pick it up.
          stateLifecycleStatus
              .getLifecycleStatusString()
              .ifPresent(event -> buildEventBus.post(DaemonEvent.newDaemonInstance(event)));


          ListenableFuture<Optional<FullVersionControlStats>> vcStatsFuture =
              Futures.immediateFuture(Optional.empty());
          boolean shouldUploadBuildReport = BuildReportUtils.shouldUploadBuildReport(buckConfig);

          VersionControlBuckConfig vcBuckConfig = new VersionControlBuckConfig(buckConfig);
          VersionControlStatsGenerator vcStatsGenerator =
              new VersionControlStatsGenerator(
                  new DelegatingVersionControlCmdLineInterface(
                      cells.getRootCell().getFilesystem().getRootPath().getPath(),
                      new PrintStreamProcessExecutorFactory(),
                      vcBuckConfig.getHgCmd(),
                      buckConfig.getEnvironment()),
                  vcBuckConfig.getPregeneratedVersionControlStats());
          if ((vcBuckConfig.shouldGenerateStatistics() || shouldUploadBuildReport)
              && command.subcommand instanceof AbstractCommand) {
            AbstractCommand subcommand = (AbstractCommand) command.subcommand;
            if (!commandMode.equals(CommandMode.TEST)) {

              boolean shouldPreGenerate = !subcommand.isSourceControlStatsGatheringEnabled();
              vcStatsFuture =
                  vcStatsGenerator.generateStatsAsync(
                      shouldUploadBuildReport,
                      shouldPreGenerate,
                      buildEventBus,
                      listeningDecorator(diskIoExecutorService.get()));
            }
          }

          if (command.getSubcommand().isPresent()
              && command.getSubcommand().get() instanceof BuildCommand
              && shouldUploadBuildReport) {
            BuildReportUpload.runBuildReportUpload(
                managerScope, vcStatsFuture, buckConfig, buildId);
          }

          NetworkInfo.generateActiveNetworkAsync(diskIoExecutorService.get(), buildEventBus);

          ImmutableList<String> remainingArgs =
              filteredArgsForLogging.isEmpty()
                  ? ImmutableList.of()
                  : filteredUnexpandedArgsForLogging.subList(
                      1, filteredUnexpandedArgsForLogging.size());

          Path absoluteClientPwd = getClientPwd(cells.getRootCell(), clientEnvironment);
          CommandEvent.Started startedEvent =
              CommandEvent.started(
                  command.getDeclaredSubCommandName(),
                  remainingArgs,
                  cells.getRootCell().getRoot().getPath().relativize(absoluteClientPwd).normalize(),
                  context.isPresent()
                      ? OptionalLong.of(buckGlobalState.getUptime())
                      : OptionalLong.empty(),
                  getBuckPID());
          buildEventBus.post(startedEvent);

          TargetSpecResolver targetSpecResolver =
              getTargetSpecResolver(
                  parserConfig,
                  watchman,
                  cells.getRootCell(),
                  buckGlobalState,
                  buildEventBus,
                  depsAwareExecutorSupplier);

          // This also queries watchman, posts events to global and local event buses and
          // invalidates all related caches
          // TODO (buck_team): extract invalidation from getParserAndCaches()
          ParserAndCaches parserAndCaches =
              getParserAndCaches(
                  context,
                  watchmanFreshInstanceAction,
                  filesystem,
                  buckConfig,
                  watchman,
                  knownRuleTypesProvider,
                  cells.getRootCell(),
                  buckGlobalState,
                  buildEventBus,
                  executors,
                  ruleKeyConfiguration,
                  depsAwareExecutorSupplier,
                  executableFinder,
                  buildTargetFactory,
                  hostConfiguration.orElse(UnconfiguredTargetConfiguration.INSTANCE),
                  targetSpecResolver);

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
                    ImmutableCommandRunnerParams.of(
                        printConsole,
                        stdIn,
                        cells,
                        watchman,
                        parserAndCaches.getVersionedTargetGraphCache(),
                        artifactCacheFactory,
                        parserAndCaches.getTypeCoercerFactory(),
                        buildTargetFactory,
                        targetConfiguration,
                        hostConfiguration,
                        targetConfigurationSerializer,
                        parserAndCaches.getParser(),
                        buildEventBus,
                        platform,
                        clientEnvironment,
                        cells
                            .getRootCell()
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
                        parserAndCaches.getActionGraphProvider(),
                        knownRuleTypesProvider,
                        storeManager,
                        Optional.of(invocationInfo),
                        parserAndCaches.getDefaultRuleKeyFactoryCacheRecycler(),
                        projectFilesystemFactory,
                        ruleKeyConfiguration,
                        processExecutor,
                        executableFinder,
                        pluginManager,
                        moduleManager,
                        depsAwareExecutorSupplier,
                        metadataProvider,
                        buckGlobalState,
                        absoluteClientPwd));
          } catch (InterruptedException | ClosedByInterruptException e) {
            buildEventBus.post(CommandEvent.interrupted(startedEvent, ExitCode.SIGNAL_INTERRUPT));
            throw e;
          } finally {
            buildEventBus.post(CommandEvent.finished(startedEvent, exitCode));
            buildEventBus.post(
                new CacheStatsEvent(
                    "versioned_target_graph_cache",
                    parserAndCaches.getVersionedTargetGraphCache().getCacheStats()));
          }
        } catch (Exception e) {
          exceptionForFix = Optional.of(e);
          throw e;
        } finally {
          if (exitCode != ExitCode.SUCCESS) {
            handleAutoFix(
                filesystem,
                printConsole,
                clientEnvironment,
                command,
                buckConfig,
                buildId,
                exitCode,
                exceptionForFix,
                invocationInfo);
          }

          // signal nailgun that we are not interested in client disconnect events anymore
          context.ifPresent(c -> c.removeAllClientListeners());

          if (context.isPresent()) {
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
              && !cliConfig.getFlushEventsBeforeExit()) {
            context.get().in.close(); // Avoid client exit triggering client disconnection handling.
            context.get().exit(exitCode.getCode());
          }

          // TODO(buck_team): refactor eventListeners for RAII
          flushAndCloseEventListeners(printConsole, eventListeners);
        }
      }
    }
    return exitCode;
  }

  private TargetSpecResolver getTargetSpecResolver(
      ParserConfig parserConfig,
      Watchman watchman,
      Cell rootCell,
      BuckGlobalState buckGlobalState,
      DefaultBuckEventBus buildEventBus,
      CloseableMemoizedSupplier<DepsAwareExecutor<? super ComputeResult, ?>>
          depsAwareExecutorSupplier) {

    ParserConfig.BuildFileSearchMethod searchMethod = parserConfig.getBuildFileSearchMethod();
    switch (searchMethod) {
      case FILESYSTEM_CRAWL:
        return TargetSpecResolver.createWithFileSystemCrawler(
            buildEventBus,
            depsAwareExecutorSupplier.get(),
            rootCell.getCellProvider(),
            buckGlobalState.getDirectoryListCaches(),
            buckGlobalState.getFileTreeCaches());
      case WATCHMAN:
        return TargetSpecResolver.createWithWatchmanCrawler(
            buildEventBus, watchman, depsAwareExecutorSupplier.get(), rootCell.getCellProvider());
    }
    throw new IllegalStateException("Unexpected build file search method: " + searchMethod);
  }

  private Path getClientPwd(Cell rootCell, ImmutableMap<String, String> clientEnvironment) {
    String rawPwd = clientEnvironment.get("BUCK_CLIENT_PWD");
    if (rawPwd == null) {
      throw new IllegalStateException("BUCK_CLIENT_PWD was not sent from the wrapper script");
    }
    Path pwd;
    try {
      pwd = Paths.get(rawPwd);
    } catch (Exception e) {
      throw new IllegalStateException(
          String.format("Received non-path BUCK_CLIENT_PWD %s from wrapper", rawPwd));
    }
    if (!pwd.isAbsolute()) {
      throw new IllegalStateException(
          String.format("Received non-absolute BUCK_CLIENT_PWD %s from wrapper", pwd));
    }
    // Resolution of symlinks is funky on windows. Python doesn't follow symlinks, but java does
    // So: do an extra resolution in java, and see what happens. Yay.
    Path originalPwd = pwd;
    try {
      pwd = pwd.toRealPath().toAbsolutePath();
    } catch (IOException e) {
      // Pass if we can't resolve the path, it'll blow up eventually
    }

    if (!pwd.startsWith(rootCell.getRoot().getPath())) {
      if (originalPwd.equals(pwd)) {
        throw new IllegalStateException(
            String.format(
                "Received BUCK_CLIENT_PWD %s from wrapper that was not a child of %s",
                pwd, rootCell.getRoot()));
      } else {
        throw new IllegalStateException(
            String.format(
                "Received BUCK_CLIENT_PWD %s (resolved to %s) from wrapper that was not a child of %s",
                originalPwd, pwd, rootCell.getRoot()));
      }
    }

    return pwd;
  }

  /**
   * Filters out command line arguments that are provided by the python wrapper.
   *
   * <p>These arguments are generally not useful to users, and we do not want them showing up in
   * logging, as they are not provided by users and their values are not really actionable.
   */
  private ImmutableList<String> filterArgsForLogging(ImmutableList<String> args) {
    ImmutableList.Builder<String> builder = ImmutableList.builderWithExpectedSize(args.size());
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      if (arg.equals(GlobalCliOptions.COMMAND_ARGS_FILE_LONG_ARG)) {
        // Skip --command-args-file and its argument. These are added by the python wrapper
        // and aren't useful to users.
        i++;
        continue;
      }
      builder.add(arg);
    }
    return builder.build();
  }

  private Optional<TargetConfiguration> createTargetConfiguration(
      Command command,
      BuckConfig buckConfig,
      TargetConfigurationFactory targetConfigurationFactory) {
    if (command.getTargetPlatforms().isEmpty()) {
      Optional<String> targetPlatformFromBuckconfig =
          buckConfig.getView(ParserConfig.class).getTargetPlatforms();
      return targetPlatformFromBuckconfig.map(targetConfigurationFactory::create);
    }
    // TODO(nga): provide a better message if more than one platform specified on command line
    return Optional.of(
        targetConfigurationFactory.create(
            Iterators.getOnlyElement(command.getTargetPlatforms().stream().distinct().iterator())));
  }

  private Optional<TargetConfiguration> createHostConfiguration(
      Command command,
      BuckConfig buckConfig,
      TargetConfigurationFactory targetConfigurationFactory) {
    if (command.getHostPlatform().isPresent()) {
      return Optional.of(targetConfigurationFactory.create(command.getHostPlatform().get()));
    }

    Optional<String> hostPlatformFromBuckConfig =
        buckConfig.getView(ParserConfig.class).getHostPlatform();
    if (hostPlatformFromBuckConfig.isPresent()) {
      return Optional.of(targetConfigurationFactory.create(hostPlatformFromBuckConfig.get()));
    }

    return Optional.empty();
  }

  private boolean isReuseCurrentConfigPropertySet(AbstractContainerCommand command) {
    Optional<Command> optionalCommand = command.getSubcommand();
    if (optionalCommand.isPresent()) {
      Command subcommand = optionalCommand.get();
      if (subcommand instanceof AbstractContainerCommand) {
        return isReuseCurrentConfigPropertySet((AbstractContainerCommand) subcommand);
      }

      if (subcommand instanceof AbstractCommand) {
        AbstractCommand abstractCommand = (AbstractCommand) subcommand;
        return abstractCommand.isReuseCurrentConfig();
      }
    }
    return false;
  }

  private void handleAutoFix(
      ProjectFilesystem filesystem,
      Console console,
      ImmutableMap<String, String> environment,
      BuckCommand command,
      BuckConfig buckConfig,
      BuildId buildId,
      ExitCode exitCode,
      Optional<Exception> exceptionForFix,
      InvocationInfo invocationInfo) {
    if (!(command.subcommand instanceof AbstractCommand)) {
      return;
    }
    AbstractCommand subcommand = (AbstractCommand) command.subcommand;
    FixBuckConfig config = buckConfig.getView(FixBuckConfig.class);

    FixCommandHandler fixCommandHandler =
        new FixCommandHandler(
            filesystem,
            console,
            environment,
            config,
            subcommand.getCommandArgsFile(),
            invocationInfo);

    if (!config.shouldRunAutofix(
        console.getAnsi().isAnsiTerminal(), command.getDeclaredSubCommandName())) {
      LOG.info("Auto fixing is not enabled for command %s", command.getDeclaredSubCommandName());
      return;
    }

    Optional<BuckFixSpec> fixSpec =
        fixCommandHandler.writeFixSpec(buildId, exitCode, exceptionForFix);

    // Only log here so that we still return with the correct top level exit code
    try {
      if (fixSpec.isPresent()) {
        fixCommandHandler.run(fixSpec.get());
      } else {
        LOG.warn("Failed to run autofix because fix spec is missing");
      }
    } catch (IOException e) {
      console.printErrorText(
          "Failed to write fix script information to %s", subcommand.getCommandArgsFile());
    } catch (FixCommandHandler.FixCommandHandlerException e) {
      console.printErrorText("Error running auto-fix: %s", e.getMessage());
    }
  }

  private void warnAboutConfigFileOverrides(AbsPath root, CliConfig cliConfig) throws IOException {
    if (!cliConfig.getWarnOnConfigFileOverrides()) {
      return;
    }

    if (!printConsole.getVerbosity().shouldPrintStandardInformation()) {
      return;
    }

    // Useful for filtering out things like system wide buckconfigs in /etc that might be managed
    // by the system. We don't want to warn users about files that they have not necessarily
    // created.
    ImmutableSet<Path> overridesToIgnore = cliConfig.getWarnOnConfigFileOverridesIgnoredFiles();

    // Use the raw stream because otherwise this will stop superconsole from ever printing again
    UIMessagesFormatter.useSpecificOverridesMessage(root, overridesToIgnore)
        .ifPresent(this::printWarnMessage);
  }

  private void printWarnMessage(String message) {
    printConsole.getStdErr().getRawStream().println(printConsole.getAnsi().asWarningText(message));
  }

  private ListeningExecutorService getDirCacheStoreExecutor(
      ArtifactCacheBuckConfig cacheBuckConfig,
      ThrowingCloseableWrapper<ExecutorService, InterruptedException> diskIoExecutorService) {
    Executor dirCacheStoreExecutor = cacheBuckConfig.getDirCacheStoreExecutor();
    switch (dirCacheStoreExecutor) {
      case DISK_IO:
        return listeningDecorator(diskIoExecutorService.get());
      case DIRECT:
        return newDirectExecutorService();
      default:
        throw new IllegalStateException(
            "Executor service for " + dirCacheStoreExecutor + " is not configured.");
    }
  }

  private boolean isRemoteExecutionAutoEnabled(
      BuckCommand command, BuckConfig config, String username) {
    BuildCommand subcommand = (BuildCommand) command.getSubcommand().get();
    if (subcommand.isRemoteExecutionForceDisabled()) {
      return false;
    }

    return config
        .getView(RemoteExecutionConfig.class)
        .isRemoteExecutionAutoEnabled(username, subcommand.getArguments());
  }

  private boolean isRemoteExecutionBuild(BuckCommand command, BuckConfig config, String username) {
    if (!command.getSubcommand().isPresent()
        || !(command.getSubcommand().get() instanceof BuildCommand)
        || ((BuildCommand) command.getSubcommand().get()).isRemoteExecutionForceDisabled()) {
      return false;
    }

    boolean remoteExecutionAutoEnabled = isRemoteExecutionAutoEnabled(command, config, username);

    ModernBuildRuleStrategyConfig strategyConfig =
        config.getView(ModernBuildRuleConfig.class).getDefaultStrategyConfig();
    while (strategyConfig.getBuildStrategy(remoteExecutionAutoEnabled, false)
        == ModernBuildRuleBuildStrategy.HYBRID_LOCAL) {
      strategyConfig = strategyConfig.getHybridLocalConfig().getDelegateConfig();
    }
    return strategyConfig.getBuildStrategy(remoteExecutionAutoEnabled, false)
        == ModernBuildRuleBuildStrategy.REMOTE;
  }


  private ImmutableList<AdditionalConsoleLineProvider> createAdditionalConsoleLinesProviders(
      Optional<RemoteExecutionEventListener> remoteExecutionListener,
      RemoteExecutionConfig remoteExecutionConfig,
      MetadataProvider metadataProvider) {
    if (!remoteExecutionListener.isPresent() || !remoteExecutionConfig.isConsoleEnabled()) {
      return ImmutableList.of();
    }

    return ImmutableList.of(
        new RemoteExecutionConsoleLineProvider(
            remoteExecutionListener.get(),
            remoteExecutionConfig.getDebugURLString(metadataProvider.get().getReSessionId()),
            remoteExecutionConfig.isDebug()));
  }

  /** Struct for the multiple values returned by {@link #getParserAndCaches}. */
  @BuckStyleValue
  public interface ParserAndCaches {

    Parser getParser();

    TypeCoercerFactory getTypeCoercerFactory();

    InstrumentedVersionedTargetGraphCache getVersionedTargetGraphCache();

    ActionGraphProvider getActionGraphProvider();

    Optional<RuleKeyCacheRecycler<RuleKey>> getDefaultRuleKeyFactoryCacheRecycler();
  }

  private static ParserAndCaches getParserAndCaches(
      Optional<NGContext> context,
      FreshInstanceAction watchmanFreshInstanceAction,
      ProjectFilesystem filesystem,
      BuckConfig buckConfig,
      Watchman watchman,
      KnownRuleTypesProvider knownRuleTypesProvider,
      Cell rootCell,
      BuckGlobalState buckGlobalState,
      BuckEventBus buildEventBus,
      ImmutableMap<ExecutorPool, ListeningExecutorService> executors,
      RuleKeyConfiguration ruleKeyConfiguration,
      CloseableMemoizedSupplier<DepsAwareExecutor<? super ComputeResult, ?>>
          depsAwareExecutorSupplier,
      ExecutableFinder executableFinder,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
      TargetConfiguration hostConfiguration,
      TargetSpecResolver targetSpecResolver)
      throws IOException, InterruptedException {
    Optional<WatchmanWatcher> watchmanWatcher = Optional.empty();
    if (watchman.getTransportPath().isPresent()) {
      try {
        watchmanWatcher =
            Optional.of(
                new WatchmanWatcher(
                    watchman,
                    buckGlobalState.getFileEventBus(),
                    ImmutableSet.<PathMatcher>builder()
                        .addAll(filesystem.getIgnorePaths())
                        .addAll(DEFAULT_IGNORE_GLOBS)
                        .build(),
                    buckGlobalState.getWatchmanCursor(),
                    buckConfig.getView(BuildBuckConfig.class).getNumThreads()));
      } catch (WatchmanWatcherException e) {
        buildEventBus.post(
            ConsoleEvent.warning(
                "Watchman threw an exception while parsing file changes.\n%s", e.getMessage()));
      }
    }

    ParserConfig parserConfig = rootCell.getBuckConfig().getView(ParserConfig.class);
    TypeCoercerFactory typeCoercerFactory = buckGlobalState.getTypeCoercerFactory();
    Optional<RuleKeyCacheRecycler<RuleKey>> defaultRuleKeyFactoryCacheRecycler = Optional.empty();

    // Create or get Parser and invalidate cached command parameters.
    if (context.isPresent()) {
      // Note that watchmanWatcher is non-null only when context.isPresent().
      registerClientDisconnectedListener(context.get(), buckGlobalState);
      if (watchmanWatcher.isPresent()) {
        buckGlobalState.watchFileSystem(
            buildEventBus, watchmanWatcher.get(), watchmanFreshInstanceAction);
      }
      if (buckConfig.getView(BuildBuckConfig.class).getRuleKeyCaching()) {
        LOG.debug("Using rule key calculation caching");
        defaultRuleKeyFactoryCacheRecycler =
            Optional.of(buckGlobalState.getDefaultRuleKeyFactoryCacheRecycler());
      } else {
        defaultRuleKeyFactoryCacheRecycler = Optional.empty();
      }
    }

    return ImmutableParserAndCaches.of(
        ParserFactory.create(
            typeCoercerFactory,
            new DefaultConstructorArgMarshaller(),
            knownRuleTypesProvider,
            new ParserPythonInterpreterProvider(parserConfig, executableFinder),
            buckGlobalState.getDaemonicParserState(),
            targetSpecResolver,
            watchman,
            buildEventBus,
            unconfiguredBuildTargetFactory,
            hostConfiguration,
            BuildBuckConfig.of(buckConfig).shouldBuckOutIncludeTargetConfigHash()),
        buckGlobalState.getTypeCoercerFactory(),
        new InstrumentedVersionedTargetGraphCache(
            buckGlobalState.getVersionedTargetGraphCache(), new InstrumentingCacheStatsTracker()),
        new ActionGraphProvider(
            buildEventBus,
            ActionGraphFactory.create(
                buildEventBus,
                rootCell.getCellProvider(),
                executors,
                depsAwareExecutorSupplier,
                buckConfig),
            buckGlobalState.getActionGraphCache(),
            ruleKeyConfiguration,
            buckConfig),
        defaultRuleKeyFactoryCacheRecycler);
  }

  private static void registerClientDisconnectedListener(
      NGContext context, BuckGlobalState buckGlobalState) {
    Thread mainThread = Thread.currentThread();
    context.addClientListener(
        reason -> {
          LOG.info("Nailgun client disconnected with " + reason);
          if (commandSemaphoreNgClient.orElse(null) == context) {
            // Process no longer wants work done on its behalf.
            LOG.debug("Killing background processes on client disconnect");
            BgProcessKiller.interruptBgProcesses();
          }

          if (reason != NGClientDisconnectReason.SESSION_SHUTDOWN) {
            LOG.debug("Killing all Buck jobs on client disconnect by interrupting the main thread");
            // signal daemon to complete required tasks and interrupt main thread
            // this will hopefully trigger InterruptedException and program shutdown
            buckGlobalState.interruptOnClientExit(mainThread);
          }
        });
  }

  private DuplicatingConsole makeCustomConsole(
      Optional<NGContext> context, Verbosity verbosity, BuckConfig buckConfig) {
    Optional<String> color;
    if (context.isPresent() && (context.get().getEnv() != null)) {
      String colorString = context.get().getEnv().getProperty(BUCKD_COLOR_DEFAULT_ENV_VAR);
      color = Optional.ofNullable(colorString);
    } else {
      color = Optional.empty();
    }
    return new DuplicatingConsole(
        new Console(
            verbosity,
            printConsole.getStdOut().getRawStream(),
            printConsole.getStdErr().getRawStream(),
            buckConfig.getView(CliConfig.class).createAnsi(color)));
  }

  private void flushAndCloseEventListeners(
      Console console, ImmutableList<BuckEventListener> eventListeners) throws IOException {
    for (BuckEventListener eventListener : eventListeners) {
      try {
        eventListener.close();
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
      } catch (AccessDeniedException e) {
        String moveFrom = pathToMove.toAbsolutePath().toString();
        String moveTo = trashPath.resolve(pathToMove.getFileName()).toAbsolutePath().toString();
        if (Platform.detect() == Platform.WINDOWS) {
          throw new HumanReadableException(
              "Can't move %s to %s: Access Denied.\n"
                  + "Usually this happens because some process is holding open file in %s.\n"
                  + "You can use Handle (https://docs.microsoft.com/en-us/sysinternals/downloads/handle) to know which process is it.\n"
                  + "Run it with: 'handle.exe /accepteula %s'",
              moveFrom, moveTo, pathToMove, pathToMove);
        } else {
          throw new HumanReadableException("Can't move %s to %s: Access Denied", moveFrom, moveTo);
        }
      }
    }
  }

  private static Watchman buildWatchman(
      Optional<NGContext> context,
      ParserConfig parserConfig,
      ImmutableSet<AbsPath> projectWatchList,
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
      ArtifactCacheBuckConfig buckConfig) {
    if (buckConfig.hasAtLeastOneWriteableRemoteCache()) {
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
   * Try to acquire global semaphore if needed to do so. Attach closer to acquired semaphore in a
   * form of a wrapper object so it can be used with try-with-resources.
   *
   * @return (semaphore, previous args) If we successfully acquire the semaphore, return (semaphore,
   *     null). If there is already a command running but the command to run is readonly, return
   *     (null, null) and allow the execution. Otherwise, return (null, previously running command
   *     args) and block this command.
   */
  private @Nullable CloseableWrapper<Semaphore> getSemaphoreWrapper(
      BuckCommand command,
      ImmutableList<String> currentArgs,
      ImmutableList.Builder<String> previousArgs) {
    // we can execute read-only commands (query, targets, etc) in parallel
    if (command.isReadOnly()) {
      // using nullable instead of Optional<> to use the object with try-with-resources
      return null;
    }

    while (!commandSemaphore.tryAcquire()) {
      ImmutableList<String> activeCommandArgsCopy = activeCommandArgs.get();
      if (activeCommandArgsCopy != null) {
        // Keep retrying until we either 1) successfully acquire the semaphore or 2) failed to
        // acquire the semaphore and obtain a valid list of args for on going command.
        // In theory, this can stuck in a loop if it never observes such state if other commands
        // winning the race, but I consider this to be a rare corner case.
        previousArgs.addAll(activeCommandArgsCopy);
        return null;
      }

      // Avoid hogging CPU
      Thread.yield();
    }

    commandSemaphoreNgClient = context;

    // Keep track of command that is in progress
    activeCommandArgs.set(currentArgs);

    return CloseableWrapper.of(
        commandSemaphore,
        commandSemaphore -> {
          activeCommandArgs.set(null);
          commandSemaphoreNgClient = Optional.empty();
          // TODO(buck_team): have background process killer have its own lifetime management
          BgProcessKiller.disarm();
          commandSemaphore.release();
        });
  }


  @SuppressWarnings("PMD.PrematureDeclaration")
  private ImmutableList<BuckEventListener> addEventListeners(
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      InvocationInfo invocationInfo,
      BuckConfig buckConfig,
      Optional<WebServer> webServer,
      Clock clock,
      ExecutionEnvironment executionEnvironment,
      CounterRegistry counterRegistry,
      Iterable<BuckEventListener> commandSpecificEventListeners,
      Optional<RemoteExecutionStatsProvider> reStatsProvider,
      TaskManagerCommandScope managerScope)
      throws IOException {
    ImmutableList.Builder<BuckEventListener> eventListenersBuilder =
        ImmutableList.<BuckEventListener>builder().add(new LoggingBuildListener());

    LogBuckConfig logBuckConfig = buckConfig.getView(LogBuckConfig.class);
    if (logBuckConfig.isJavaUtilsLoggingEnabled()) {
      eventListenersBuilder.add(new JavaUtilsLoggingBuildListener(projectFilesystem));
    }

    Path logDirectoryPath = invocationInfo.getLogDirectoryPath();
    Path criticalPathDir = projectFilesystem.resolve(logDirectoryPath);
    Path criticalPathLog = criticalPathDir.resolve(CRITICAL_PATH_FILE_NAME);
    projectFilesystem.mkdirs(criticalPathDir);
    CriticalPathEventListener criticalPathEventListener =
        new CriticalPathEventListener(criticalPathLog);
    buckEventBus.register(criticalPathEventListener);

    ChromeTraceBuckConfig chromeTraceConfig = buckConfig.getView(ChromeTraceBuckConfig.class);
    if (chromeTraceConfig.isChromeTraceCreationEnabled()) {
      try {
        ChromeTraceBuildListener chromeTraceBuildListener =
            new ChromeTraceBuildListener(
                projectFilesystem,
                invocationInfo,
                clock,
                chromeTraceConfig,
                managerScope,
                reStatsProvider,
                criticalPathEventListener);
        eventListenersBuilder.add(chromeTraceBuildListener);
      } catch (IOException e) {
        LOG.error("Unable to create ChromeTrace listener!");
      }
    } else {
      LOG.info("::: ChromeTrace listener disabled");
    }
    webServer.map(WebServer::createListener).ifPresent(eventListenersBuilder::add);

    ArtifactCacheBuckConfig artifactCacheConfig = new ArtifactCacheBuckConfig(buckConfig);


    CommonThreadFactoryState commonThreadFactoryState =
        GlobalStateManager.singleton().getThreadToCommandRegister();

    eventListenersBuilder.add(
        new LogUploaderListener(
            chromeTraceConfig,
            invocationInfo.getLogFilePath(),
            invocationInfo.getLogDirectoryPath(),
            invocationInfo.getBuildId(),
            managerScope,
            "build_log"));
    eventListenersBuilder.add(
        new LogUploaderListener(
            chromeTraceConfig,
            invocationInfo.getSimpleConsoleOutputFilePath(),
            invocationInfo.getLogDirectoryPath(),
            invocationInfo.getBuildId(),
            managerScope,
            "simple_console_output"));
    eventListenersBuilder.add(
        new LogUploaderListener(
            chromeTraceConfig,
            criticalPathLog,
            invocationInfo.getLogDirectoryPath(),
            invocationInfo.getBuildId(),
            managerScope,
            "critical_path_log"));

    if (logBuckConfig.isRuleKeyLoggerEnabled()) {

      Optional<RuleKeyLogFileUploader> keyLogFileUploader =
          createRuleKeyLogFileUploader(
              buckConfig, projectFilesystem, buckEventBus, clock, executionEnvironment, buildId);

      eventListenersBuilder.add(
          new RuleKeyLoggerListener(
              projectFilesystem,
              invocationInfo,
              MostExecutors.newSingleThreadExecutor(
                  new CommandThreadFactory(getClass().getName(), commonThreadFactoryState)),
              managerScope,
              keyLogFileUploader));
    }

    Optional<BuildReportFileUploader> buildReportFileUploader =
        createBuildReportFileUploader(buckConfig, buildId);

    eventListenersBuilder.add(
        new RuleKeyDiagnosticsListener(
            projectFilesystem,
            invocationInfo,
            MostExecutors.newSingleThreadExecutor(
                new CommandThreadFactory(getClass().getName(), commonThreadFactoryState)),
            managerScope,
            buildReportFileUploader));

    if (logBuckConfig.isMachineReadableLoggerEnabled()) {
      try {
        eventListenersBuilder.add(
            new MachineReadableLoggerListener(
                invocationInfo,
                projectFilesystem,
                MostExecutors.newSingleThreadExecutor(
                    new CommandThreadFactory(getClass().getName(), commonThreadFactoryState)),
                artifactCacheConfig.getArtifactCacheModes(),
                chromeTraceConfig,
                invocationInfo.getLogFilePath(),
                invocationInfo.getLogDirectoryPath(),
                invocationInfo.getBuildId(),
                managerScope));
      } catch (FileNotFoundException e) {
        LOG.warn("Unable to open stream for machine readable log file.");
      }
    }

    eventListenersBuilder.add(new ParserProfilerLoggerListener(invocationInfo, projectFilesystem));

    eventListenersBuilder.add(new LoadBalancerEventsListener(counterRegistry));
    eventListenersBuilder.add(new CacheRateStatsListener(buckEventBus));
    eventListenersBuilder.add(new WatchmanDiagnosticEventListener(buckEventBus));
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

  private Optional<RuleKeyLogFileUploader> createRuleKeyLogFileUploader(
      BuckConfig buckConfig,
      ProjectFilesystem projectFilesystem,
      BuckEventBus buckEventBus,
      Clock clock,
      ExecutionEnvironment executionEnvironment,
      BuildId buildId) {
    if (BuildReportUtils.shouldUploadBuildReport(buckConfig)) {
      BuildReportConfig buildReportConfig = buckConfig.getView(BuildReportConfig.class);
      return Optional.of(
          new RuleKeyLogFileUploader(
              new DefaultDefectReporter(
                  projectFilesystem, buckConfig.getView(DoctorConfig.class), buckEventBus, clock),
              getBuildEnvironmentDescription(
                  executionEnvironment,
                  buckConfig),
              buildReportConfig.getEndpointUrl().get(),
              buildReportConfig.getEndpointTimeoutMs(),
              buildId));
    }
    return Optional.empty();
  }

  private Optional<BuildReportFileUploader> createBuildReportFileUploader(
      BuckConfig buckConfig, BuildId buildId) {
    if (BuildReportUtils.shouldUploadBuildReport(buckConfig)) {
      BuildReportConfig buildReportConfig = buckConfig.getView(BuildReportConfig.class);
      return Optional.of(
          new BuildReportFileUploader(
              buildReportConfig.getEndpointUrl().get(),
              buildReportConfig.getEndpointTimeoutMs(),
              buildId));
    }
    return Optional.empty();
  }

  private AbstractConsoleEventBusListener createConsoleEventListener(
      Clock clock,
      SuperConsoleConfig config,
      Console console,
      TestResultSummaryVerbosity testResultSummaryVerbosity,
      ExecutionEnvironment executionEnvironment,
      Locale locale,
      Path testLogPath,
      boolean printBuildId,
      Optional<String> buildDetailsTemplate,
      ImmutableSet<String> buildDetailsCommands,
      ImmutableList<AdditionalConsoleLineProvider> additionalConsoleLineProviders,
      Optional<RemoteExecutionConfig> remoteExecutionConfig,
      MetadataProvider metadataProvider) {
    RenderingConsole renderingConsole = new RenderingConsole(clock, console);
    if (config.isEnabled(console.getAnsi(), console.getVerbosity())) {
      return new SuperConsoleEventBusListener(
          config,
          renderingConsole,
          clock,
          testResultSummaryVerbosity,
          executionEnvironment,
          locale,
          testLogPath,
          buildId,
          printBuildId,
          buildDetailsTemplate,
          buildDetailsCommands,
          additionalConsoleLineProviders,
          remoteExecutionConfig.isPresent()
              ? remoteExecutionConfig.get().getStrategyConfig().getMaxConcurrentExecutions()
              : 0);
    }
    if (renderingConsole.getVerbosity().isSilent()) {
      return new SilentConsoleEventBusListener(
          renderingConsole, clock, locale, executionEnvironment);
    }
    return new SimpleConsoleEventBusListener(
        renderingConsole,
        clock,
        testResultSummaryVerbosity,
        config.getHideSucceededRulesInLogMode(),
        config.getNumberOfSlowRulesToShow(),
        config.shouldShowSlowRulesInConsole(),
        locale,
        testLogPath,
        executionEnvironment,
        buildId,
        printBuildId,
        buildDetailsTemplate,
        buildDetailsCommands,
        remoteExecutionConfig.isPresent()
            ? Optional.of(
                remoteExecutionConfig
                    .get()
                    .getDebugURLString(metadataProvider.get().getReSessionId()))
            : Optional.empty(),
        additionalConsoleLineProviders);
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

  private static String getArtifactProducerId(ExecutionEnvironment executionEnvironment) {
    String artifactProducerId = "user://" + executionEnvironment.getUsername();
    return artifactProducerId;
  }

  static CloseableMemoizedSupplier<DepsAwareExecutor<? super ComputeResult, ?>>
      getDepsAwareExecutorSupplier(BuckConfig config, BuckEventBus eventBus) {
    Map<DepsAwareExecutorType, Double> executorType =
        config.getView(DepsAwareExecutorConfig.class).getExecutorType();
    DepsAwareExecutorType resolvedExecutorType;

    if (executorType.isEmpty()) {
      resolvedExecutorType =
          RandomizedTrial.getGroup(
              "depsaware_executor", eventBus.getBuildId().toString(), DepsAwareExecutorType.class);
    } else {
      resolvedExecutorType =
          RandomizedTrial.getGroup(
              "depsaware_executor", eventBus.getBuildId().toString(), executorType);
    }
    eventBus.post(
        new ExperimentEvent("depsaware_executor", resolvedExecutorType.toString(), "", null, null));
    return getDepsAwareExecutorSupplier(
        resolvedExecutorType,
        config.getView(ResourcesConfig.class).getMaximumResourceAmounts().getCpu());
  }

  static CloseableMemoizedSupplier<DepsAwareExecutor<? super ComputeResult, ?>>
      getDepsAwareExecutorSupplier(DepsAwareExecutorType executorType, int parallelism) {
    return CloseableMemoizedSupplier.of(
        (Supplier<DepsAwareExecutor<? super ComputeResult, ?>>)
            () -> DepsAwareExecutorFactory.create(executorType, parallelism),
        DepsAwareExecutor::close);
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

          // Shut down the Nailgun server and make sure it stops trapping System.exit().
          context.ifPresent(ngContext -> ngContext.getNGServer().shutdown());

          NON_REENTRANT_SYSTEM_EXIT.shutdownSoon(exitCode.getCode());
        });
  }

  /**
   * Used to clean up the {@link BuckGlobalState} after running integration tests that exercise it.
   */
  @VisibleForTesting
  static void resetBuckGlobalState() {
    buckGlobalStateLifecycleManager.resetBuckGlobalState();
  }
}
