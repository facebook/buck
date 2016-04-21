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

import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.android.DefaultAndroidDirectoryResolver;
import com.facebook.buck.android.NoAndroidSdkException;
import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.ArtifactCacheBuckConfig;
import com.facebook.buck.artifact_cache.ArtifactCaches;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.config.Config;
import com.facebook.buck.config.Configs;
import com.facebook.buck.counters.CounterRegistry;
import com.facebook.buck.counters.CounterRegistryImpl;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.listener.AbstractConsoleEventBusListener;
import com.facebook.buck.event.listener.ChromeTraceBuildListener;
import com.facebook.buck.event.listener.FileSerializationEventBusListener;
import com.facebook.buck.event.listener.JavaUtilsLoggingBuildListener;
import com.facebook.buck.event.listener.LoadBalancerEventsListener;
import com.facebook.buck.event.listener.LoggingBuildListener;
import com.facebook.buck.event.listener.ProgressEstimator;
import com.facebook.buck.event.listener.RemoteLogUploaderEventListener;
import com.facebook.buck.event.listener.SimpleConsoleEventBusListener;
import com.facebook.buck.event.listener.SuperConsoleConfig;
import com.facebook.buck.event.listener.SuperConsoleEventBusListener;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.AsynchronousDirectoryContentsCleaner;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.TempDirectoryCreator;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.log.CommandThreadAssociation;
import com.facebook.buck.log.LogConfig;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.KnownBuildRuleTypesFactory;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.test.TestConfig;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.timing.NanosAdjustedClock;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.AnsiEnvironmentChecking;
import com.facebook.buck.util.AsyncCloseable;
import com.facebook.buck.util.BgProcessKiller;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultPropertyFinder;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.InterruptionFailedException;
import com.facebook.buck.util.Libc;
import com.facebook.buck.util.MoreFunctions;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.PkillProcessManager;
import com.facebook.buck.util.PrintStreamProcessExecutorFactory;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.PropertyFinder;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.WatchmanWatcher;
import com.facebook.buck.util.WatchmanWatcherException;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.facebook.buck.util.cache.WatchedFileHashCache;
import com.facebook.buck.util.concurrent.MoreExecutors;
import com.facebook.buck.util.concurrent.TimeSpan;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.environment.EnvironmentFilter;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.network.RemoteLoggerFactory;
import com.facebook.buck.util.versioncontrol.DefaultVersionControlCmdLineInterfaceFactory;
import com.facebook.buck.util.versioncontrol.VersionControlBuckConfig;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.common.reflect.ClassPath;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ServiceManager;
import com.martiansoftware.nailgun.NGClientListener;
import com.martiansoftware.nailgun.NGContext;
import com.martiansoftware.nailgun.NGServer;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import org.kohsuke.args4j.CmdLineException;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

public final class Main {

  /**
   * Trying again won't help.
   */
  public static final int FAIL_EXIT_CODE = 1;

  /**
   * Trying again later might work.
   */
  public static final int BUSY_EXIT_CODE = 2;

  private static final Optional<String> BUCKD_LAUNCH_TIME_NANOS =
    Optional.fromNullable(System.getProperty("buck.buckd_launch_time_nanos"));
  private static final String BUCK_BUILD_ID_ENV_VAR = "BUCK_BUILD_ID";

  private static final String BUCKD_COLOR_DEFAULT_ENV_VAR = "BUCKD_COLOR_DEFAULT";

  private static final TimeSpan DAEMON_SLAYER_TIMEOUT = new TimeSpan(2, TimeUnit.HOURS);

  private static final TimeSpan SUPER_CONSOLE_REFRESH_RATE =
      new TimeSpan(100, TimeUnit.MILLISECONDS);

  private static final TimeSpan HANG_DETECTOR_TIMEOUT =
      new TimeSpan(5, TimeUnit.MINUTES);

  /**
   * Path to a directory of static content that should be served by the {@link WebServer}.
   */
  private static final String STATIC_CONTENT_DIRECTORY = System.getProperty(
      "buck.path_to_static_content", "webserver/static");
  private static final int DISK_IO_STATS_TIMEOUT_SECONDS = 10;
  private static final int EXECUTOR_SERVICES_TIMEOUT_SECONDS = 60;

  private final InputStream stdIn;
  private final PrintStream stdOut;
  private final PrintStream stdErr;
  private final ImmutableList<BuckEventListener> externalEventsListeners;

  private final Architecture architecture;

  private static final Semaphore commandSemaphore = new Semaphore(1);
  private static volatile Optional<NGContext> commandSemaphoreNgClient = Optional.absent();

  // Ensure we only have one instance of this, so multiple trash cleaning
  // operations are serialized on one queue.
  private static final AsynchronousDirectoryContentsCleaner TRASH_CLEANER =
      new AsynchronousDirectoryContentsCleaner(BuckConstant.getTrashPath());

  private final Platform platform;

  // It's important to re-use this object for perf:
  // http://wiki.fasterxml.com/JacksonBestPracticesPerformance
  private final ObjectMapper objectMapper;

  // This is a hack to work around a perf issue where generated Xcode IDE files
  // trip WatchmanWatcher, causing buck project to take a long time to run.
  private static final ImmutableSet<String> DEFAULT_IGNORE_GLOBS =
      ImmutableSet.of("*.pbxproj", "*.xcscheme", "*.xcworkspacedata");

  private static final Logger LOG = Logger.get(Main.class);

  private static boolean isSessionLeader;

  private static final HangMonitor.AutoStartInstance HANG_MONITOR =
      new HangMonitor.AutoStartInstance(
          new Function<String, Void>() {
            @Nullable
            @Override
            public Void apply(String input) {
              LOG.info(
                  "No recent activity, dumping thread stacks (`tr , '\\n'` to decode): %s",
                  input);
              return null;
            }
          },
          HANG_DETECTOR_TIMEOUT);

  /**
   * Daemon used to monitor the file system and cache build rules between Main() method
   * invocations is static so that it can outlive Main() objects and survive for the lifetime
   * of the potentially long running Buck process.
   */
  private static final class Daemon implements Closeable {

    private final Cell cell;
    private final Parser parser;
    private final DefaultFileHashCache hashCache;
    private final DefaultFileHashCache buckOutHashCache;
    private final EventBus fileEventBus;
    private final Optional<WebServer> webServer;
    private final UUID watchmanQueryUUID;
    private final ActionGraphCache actionGraphCache;

    public Daemon(
        Cell cell,
        ObjectMapper objectMapper,
        Optional<WebServer> webServerToReuse)
        throws IOException, InterruptedException {
      this.cell = cell;
      this.hashCache = new WatchedFileHashCache(cell.getFilesystem());
      this.buckOutHashCache =
          new DefaultFileHashCache(
              new ProjectFilesystem(
                  cell.getFilesystem().getRootPath(),
                  Optional.of(ImmutableSet.of(BuckConstant.getBuckOutputPath())),
                  ImmutableSet.<ProjectFilesystem.PathOrGlobMatcher>of()));
      this.fileEventBus = new EventBus("file-change-events");

      actionGraphCache = new ActionGraphCache();

      TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory(objectMapper);
      this.parser = new Parser(
          new ParserConfig(cell.getBuckConfig()),
          typeCoercerFactory,
          new ConstructorArgMarshaller(typeCoercerFactory));
      fileEventBus.register(parser);
      fileEventBus.register(actionGraphCache);
      fileEventBus.register(hashCache);

      if (webServerToReuse.isPresent()) {
        webServer = webServerToReuse;
      } else {
        webServer = createWebServer(cell.getBuckConfig(), cell.getFilesystem(), objectMapper);
      }
      if (!initWebServer()) {
        LOG.warn("Can't start web server");
      }
      watchmanQueryUUID = UUID.randomUUID();
      JavaUtilsLoggingBuildListener.ensureLogFileIsWritten(cell.getFilesystem());
    }

    private Optional<WebServer> createWebServer(
        BuckConfig config,
        ProjectFilesystem filesystem,
        ObjectMapper objectMapper) {
      Optional<Integer> port = getValidWebServerPort(config);
      if (port.isPresent()) {
        WebServer webServer = new WebServer(
            port.get(),
            filesystem,
            STATIC_CONTENT_DIRECTORY,
            objectMapper);
        return Optional.of(webServer);
      } else {
        return Optional.absent();
      }
    }

    /**
     * If the return value is not absent, then the port is a nonnegative integer. This means that
     * specifying a port of -1 effectively disables the WebServer.
     */
    public static Optional<Integer> getValidWebServerPort(BuckConfig config) {
      // Enable the web httpserver if it is given by command line parameter or specified in
      // .buckconfig. The presence of a nonnegative port number is sufficient.
      Optional<String> serverPort =
          Optional.fromNullable(System.getProperty("buck.httpserver.port"));
      if (!serverPort.isPresent()) {
        serverPort = config.getValue("httpserver", "port");
      }

      if (!serverPort.isPresent() || serverPort.get().isEmpty()) {
        return Optional.absent();
      }

      String rawPort = serverPort.get();
      int port;
      try {
        port = Integer.parseInt(rawPort, 10);
        LOG.debug("Starting up web server on port %d.", port);
      } catch (NumberFormatException e) {
        LOG.error("Could not parse port for httpserver: %s.", rawPort);
        return Optional.absent();
      }

      return port >= 0 ? Optional.of(port) : Optional.<Integer>absent();
    }

    public Optional<WebServer> getWebServer() {
      return webServer;
    }

    private Parser getParser() {
      return parser;
    }

    private ActionGraphCache getActionGraphCache() {
      return actionGraphCache;
    }

    private DefaultFileHashCache getFileHashCache() {
      return hashCache;
    }

    private DefaultFileHashCache getBuckOutHashCache() {
      return buckOutHashCache;
    }

    private void watchClient(final NGContext context) {
      context.addClientListener(new NGClientListener() {
        @Override
        public void clientDisconnected() throws InterruptedException {
          if (isSessionLeader && commandSemaphoreNgClient.orNull() == context) {
            LOG.info("killing background processes on client disconnection");
            // Process no longer wants work done on its behalf.
            BgProcessKiller.killBgProcesses();
          }

          // Synchronize on parser object so that the main command processing thread is not
          // interrupted mid way through a Parser cache update by the Thread.interrupt() call
          // triggered by System.exit(). The Parser cache will be reused by subsequent commands
          // so needs to be left in a consistent state even if the current command is interrupted
          // due to a client disconnection.
          synchronized (parser) {
            LOG.info("Client disconnected.");
            // Client should no longer be connected, but printing helps detect false disconnections.
            context.err.println("Client disconnected.");

            throw new InterruptedException("Client disconnected.");
          }
        }
      });
    }

    private void watchFileSystem(
        CommandEvent commandEvent,
        BuckEventBus eventBus,
        WatchmanWatcher watchmanWatcher) throws IOException, InterruptedException {

      // Synchronize on parser object so that all outstanding watch events are processed
      // as a single, atomic Parser cache update and are not interleaved with Parser cache
      // invalidations triggered by requests to parse build files or interrupted by client
      // disconnections.
      synchronized (parser) {
        parser.recordParseStartTime(eventBus);
        fileEventBus.post(commandEvent);
        ImmutableSet.Builder<String> encounteredWatchmanWarningsBuilder = ImmutableSet.builder();
        watchmanWatcher.postEvents(eventBus, encounteredWatchmanWarningsBuilder);

        // TODO(bhamiltoncx): Pass encountered Watchman warnings to parser so Watchman glob can
        // ignore them.
      }
    }

    /** @return true if the web server was started successfully. */
    private boolean initWebServer() {
      if (webServer.isPresent()) {
        Optional<ArtifactCache> servedCache = ArtifactCaches.newServedCache(
            new ArtifactCacheBuckConfig(cell.getBuckConfig()),
            cell.getFilesystem());
        try {
          webServer.get().updateAndStartIfNeeded(servedCache);
          return true;
        } catch (WebServer.WebServerException e) {
          LOG.error(e);
        }
      }
      return false;
    }

    public EventBus getFileEventBus() {
      return fileEventBus;
    }

    public UUID getWatchmanQueryUUID() {
      return watchmanQueryUUID;
    }

    @Override
    public void close() throws IOException {
      shutdownWebServer();
    }

    private void shutdownWebServer() {
      if (webServer.isPresent()) {
        try {
          webServer.get().stop();
        } catch (WebServer.WebServerException e) {
          LOG.error(e);
        }
      }
    }
  }

  @Nullable private static volatile Daemon daemon;

  /**
   * Get or create Daemon.
   */
  @VisibleForTesting
  static Daemon getDaemon(
      Cell cell,
      ObjectMapper objectMapper)
      throws IOException, InterruptedException  {
    Path rootPath = cell.getFilesystem().getRootPath();
    Optional<WebServer> webServer = Optional.absent();
    if (daemon == null) {
      LOG.debug("Starting up daemon for project root [%s]", rootPath);
      daemon = new Daemon(cell, objectMapper, webServer);
    } else {
      // Buck daemons cache build files within a single project root, changing to a different
      // project root is not supported and will likely result in incorrect builds. The buck and
      // buckd scripts attempt to enforce this, so a change in project root is an error that
      // should be reported rather than silently worked around by invalidating the cache and
      // creating a new daemon object.
      Path parserRoot = cell.getFilesystem().getRootPath();
      if (!rootPath.equals(parserRoot)) {
        throw new HumanReadableException(String.format("Unsupported root path change from %s to %s",
            rootPath, parserRoot));
      }

      // If Buck config or the AndroidDirectoryResolver has changed, invalidate the cache and
      // create a new daemon.
      if (!daemon.cell.equals(cell)) {
        LOG.warn(
            "Shutting down and restarting daemon on config or directory resolver change (%s != %s)",
            daemon.cell,
            cell);
        if (shouldReuseWebServer(cell)) {
          webServer = daemon.getWebServer();
          LOG.info("Reusing web server");
        } else {
          daemon.close();
        }
        daemon = new Daemon(cell, objectMapper, webServer);
      }
    }
    return daemon;
  }

  private static boolean shouldReuseWebServer(Cell newCell) {
    if (newCell == null || daemon == null || daemon.cell == null) {
      return false;
    }
    Optional<Integer> portFromOldConfig =
        Daemon.getValidWebServerPort(daemon.cell.getBuckConfig());
    Optional<Integer> portFromUpdatedConfig =
        Daemon.getValidWebServerPort(newCell.getBuckConfig());

    return portFromOldConfig.equals(portFromUpdatedConfig);
  }

  @VisibleForTesting
  @SuppressWarnings("PMD.EmptyCatchBlock")
  static void resetDaemon() {
    if (daemon != null) {
      try {
        LOG.info("Closing daemon on reset request.");
        daemon.close();
      } catch (IOException e) {
        // Swallow exceptions while closing daemon.
      }
    }
    daemon = null;
  }

  @VisibleForTesting
  public Main(PrintStream stdOut, PrintStream stdErr, InputStream stdIn) {
    this(stdOut, stdErr, stdIn, ImmutableList.<BuckEventListener>of());
  }

  @VisibleForTesting
  public Main(
      PrintStream stdOut,
      PrintStream stdErr,
      InputStream stdIn,
      List<BuckEventListener> externalEventsListeners) {
    this.stdOut = stdOut;
    this.stdErr = stdErr;
    this.stdIn = stdIn;
    this.architecture = Architecture.detect();
    this.platform = Platform.detect();
    this.objectMapper = ObjectMappers.newDefaultInstance();
    this.externalEventsListeners = ImmutableList.copyOf(externalEventsListeners);
  }

  private void flushEventListeners(
      Console console,
      BuildId buildId,
      ImmutableList<BuckEventListener> eventListeners)
      throws InterruptedException {
    for (BuckEventListener eventListener : eventListeners) {
      try {
        eventListener.outputTrace(buildId);
      } catch (RuntimeException e) {
        PrintStream stdErr = console.getStdErr();
        stdErr.println("Ignoring non-fatal error!  The stack trace is below:");
        e.printStackTrace(stdErr);
      }
    }
  }

  private static Optional<Path> getTestTempDirOverride(
      BuckConfig config,
      ImmutableMap<String, String> clientEnvironment,
      String tmpDirName) {
    Optional<ImmutableList<String>> testTempDirEnvVars = config.getTestTempDirEnvVars();
    if (!testTempDirEnvVars.isPresent()) {
      return Optional.absent();
    } else {
      for (String envVar : testTempDirEnvVars.get()) {
        String val = clientEnvironment.get(envVar);
        if (val != null) {
          return Optional.of(Paths.get(val).resolve(tmpDirName));
        }
      }
      return Optional.of(Paths.get("/tmp").resolve(tmpDirName));
    }
  }

  private static void moveToTrash(
      ProjectFilesystem filesystem,
      Console console,
      BuildId buildId,
      Path... pathsToMove) throws IOException {
    Path trashPath = BuckConstant.getTrashPath().resolve(buildId.toString());
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
        console.getStdErr().format(
            "Atomic moves not supported, falling back to synchronous delete: %s",
            e);
        MoreFiles.deleteRecursivelyIfExists(pathToMove);
      }
    }
  }

  private static final Watchman buildWatchman(
      Optional<NGContext> context,
      ParserConfig parserConfig,
      Path projectRoot,
      ImmutableMap<String, String> clientEnvironment,
      Console console,
      Clock clock) throws InterruptedException, IOException {
    Watchman watchman;
    if (context.isPresent() || parserConfig.getGlobHandler() == ParserConfig.GlobHandler.WATCHMAN) {
      watchman = Watchman.build(projectRoot, clientEnvironment, console, clock);

      LOG.debug(
          "Watchman capabilities: %s Watch root: %s Project prefix: %s Glob handler config: %s ",
          watchman.getCapabilities(),
          watchman.getWatchRoot(),
          watchman.getProjectPrefix(),
          parserConfig.getGlobHandler());

    } else {
      watchman = Watchman.NULL_WATCHMAN;
    }
    return watchman;
  }

  /**
   *
   * @param buildId an identifier for this command execution.
   * @param context an optional NGContext that is present if running inside a Nailgun server.
   * @param args command line arguments
   * @return an exit code or {@code null} if this is a process that should not exit
   */
  @SuppressWarnings("PMD.PrematureDeclaration")
  public int runMainWithExitCode(
      BuildId buildId,
      Path projectRoot,
      Optional<NGContext> context,
      ImmutableMap<String, String> clientEnvironment,
      String... args)
      throws IOException, InterruptedException {

    Verbosity verbosity = VerbosityParser.parse(args);
    Optional<String> color;
    if (context.isPresent() && (context.get().getEnv() != null)) {
      String colorString = context.get().getEnv().getProperty(BUCKD_COLOR_DEFAULT_ENV_VAR);
      color = Optional.fromNullable(colorString);
    } else {
      color = Optional.absent();
    }

    // We need a BuckConfig to create a Console, but we get BuckConfig from Cell, and we need
    // a Console to create a Cell. To break this bootstrapping loop, create a temporary
    // BuckConfig.
    // TODO(oconnor663): We probably shouldn't rely on BuckConfig to instantiate Console.
    // Begin ugly bootstrapping code

    BuckCommand command = new BuckCommand();
    AdditionalOptionsCmdLineParser cmdLineParser = new AdditionalOptionsCmdLineParser(command);
    try {
      cmdLineParser.parseArgument(args);
    } catch (CmdLineException e) {
      // Can't go through the console for prettification since that needs the BuckConfig, and that
      // needs to be created with the overrides, which are parsed from the command line here, which
      // required the console to print the message that parsing has failed. So just write to stderr
      // and be done with it.
      stdErr.println(e.getLocalizedMessage());
      return 1;
    }

    Path canonicalRootPath = projectRoot.toRealPath().normalize();
    Config config = Configs.createDefaultConfig(canonicalRootPath, command.getConfigOverrides());
    ProjectFilesystem filesystem = new ProjectFilesystem(canonicalRootPath, config);
    BuckConfig buckConfig = new BuckConfig(
        config,
        filesystem,
        architecture,
        platform,
        clientEnvironment);
    // End ugly bootstrapping code.

    final Console console = new Console(
        verbosity,
        stdOut,
        stdErr,
        buckConfig.createAnsi(color));

    // No more early outs: if this command is not read only, acquire the command semaphore to
    // become the only executing read/write command.
    // This must happen immediately before the try block to ensure that the semaphore is released.
    boolean commandSemaphoreAcquired = false;
    boolean shouldCleanUpTrash = false;
    if (!command.isReadOnly()) {
      commandSemaphoreAcquired = commandSemaphore.tryAcquire();
      if (!commandSemaphoreAcquired) {
        return BUSY_EXIT_CODE;
      }
    }

    try {

      if (commandSemaphoreAcquired) {
        commandSemaphoreNgClient = context;
      }

      if (!command.isReadOnly()) {

        Optional<String> currentVersion =
            filesystem.readFileIfItExists(BuckConstant.getCurrentVersionFile());
        if (!currentVersion.isPresent() || !currentVersion.get().equals(BuckVersion.getVersion())) {
          // Migrate any version-dependent directories (which might be
          // huge) to a trash directory so we can delete it
          // asynchronously after the command is done.
          moveToTrash(
              filesystem,
              console,
              buildId,
              BuckConstant.getAnnotationPath(),
              BuckConstant.getGenPath(),
              BuckConstant.getScratchPath(),
              BuckConstant.getResPath());
          shouldCleanUpTrash = true;
          filesystem.mkdirs(BuckConstant.getCurrentVersionFile().getParent());
          filesystem.writeContentsToPath(
              BuckVersion.getVersion(),
              BuckConstant.getCurrentVersionFile());
        }
      }

      PropertyFinder propertyFinder = new DefaultPropertyFinder(
          filesystem,
          clientEnvironment);
      AndroidBuckConfig androidBuckConfig = new AndroidBuckConfig(buckConfig, platform);
      AndroidDirectoryResolver androidDirectoryResolver =
          new DefaultAndroidDirectoryResolver(
              filesystem,
              androidBuckConfig.getBuildToolsVersion(),
              androidBuckConfig.getNdkVersion(),
              propertyFinder);

      ProcessExecutor processExecutor = new ProcessExecutor(console);

      Optional<Path> testTempDirOverride = getTestTempDirOverride(
          buckConfig,
          clientEnvironment,
          "buck-" + buildId.toString());
      if (testTempDirOverride.isPresent()) {
        // We'll create this directory a little later using TempDirectoryCreator.
        LOG.debug("Using test temp dir override %s", testTempDirOverride.get());
      }

      Clock clock;
      if (BUCKD_LAUNCH_TIME_NANOS.isPresent()) {
        long nanosEpoch = Long.parseLong(BUCKD_LAUNCH_TIME_NANOS.get(), 10);
        LOG.verbose("Using nanos epoch: %d", nanosEpoch);
        clock = new NanosAdjustedClock(nanosEpoch);
      } else {
        clock = new DefaultClock();
      }

      ParserConfig parserConfig = new ParserConfig(buckConfig);
      try (Watchman watchman =
          buildWatchman(context, parserConfig, projectRoot, clientEnvironment, console, clock)) {
        final boolean isDaemon = context.isPresent() && (watchman != Watchman.NULL_WATCHMAN);

        if (!isDaemon && shouldCleanUpTrash) {
          // Clean up the trash on a background thread if this was a
          // non-buckd read-write command. (We don't bother waiting
          // for it to complete; the thread is a daemon thread which
          // will just be terminated at shutdown time.)
          TRASH_CLEANER.startCleaningDirectory();
        }

        KnownBuildRuleTypesFactory factory = new KnownBuildRuleTypesFactory(
            processExecutor,
            androidDirectoryResolver,
            testTempDirOverride);

        Cell rootCell = Cell.createCell(
            filesystem,
            console,
            watchman,
            buckConfig,
            factory,
            androidDirectoryResolver,
            clock);

        int exitCode;
        ImmutableList<BuckEventListener> eventListeners = ImmutableList.of();
        ExecutionEnvironment executionEnvironment = new DefaultExecutionEnvironment(
            clientEnvironment,
            // TODO(bhamiltoncx): Thread through properties from client environment.
            System.getProperties());

        ProjectFileHashCache cellHashCache;
        ProjectFileHashCache buckOutHashCache;
        if (isDaemon) {
          cellHashCache = getFileHashCacheFromDaemon(rootCell);
          buckOutHashCache = getBuckOutFileHashCacheFromDaemon(rootCell);
        } else {
          cellHashCache = new DefaultFileHashCache(rootCell.getFilesystem());
          buckOutHashCache =
              new DefaultFileHashCache(
                  new ProjectFilesystem(
                      rootCell.getFilesystem().getRootPath(),
                      Optional.of(ImmutableSet.of(BuckConstant.getBuckOutputPath())),
                      ImmutableSet.<ProjectFilesystem.PathOrGlobMatcher>of()));
        }

        // Build up the hash cache, which is a collection of the stateful cell cache and some
        // per-run caches.

        ImmutableList.Builder<FileHashCache> allCaches = ImmutableList.builder();
        allCaches.add(cellHashCache);
        allCaches.add(buckOutHashCache);
        // A cache which caches hashes of cell-relative paths which may have been ignore by
        // the main cell cache, and only serves to prevent rehashing the same file multiple
        // times in a single run.
        allCaches.add(new DefaultFileHashCache(
                new ProjectFilesystem(rootCell.getFilesystem().getRootPath())));

        for (Path root : FileSystems.getDefault().getRootDirectories()) {
          if (!root.toFile().exists()) {
            // On Windows, it is possible that the system will have a
            // drive for something that does not exist such as a floppy
            // disk or SD card.  The drive exists, but it does not
            // contain anything useful, so Buck should not consider it
            // as a cacheable location.
            continue;
          }
          // A cache which caches hashes of absolute paths which my be accessed by certain
          // rules (e.g. /usr/bin/gcc), and only serves to prevent rehashing the same file
          // multiple times in a single run.
          allCaches.add(new DefaultFileHashCache(new ProjectFilesystem(root)));
        }

        FileHashCache fileHashCache = new StackedFileHashCache(allCaches.build());

        Optional<WebServer> webServer = getWebServerIfDaemon(
            context,
            rootCell);

        TestConfig testConfig = new TestConfig(buckConfig);
        ArtifactCacheBuckConfig cacheBuckConfig = new ArtifactCacheBuckConfig(buckConfig);

        ExecutorService diskIoExecutorService = MoreExecutors.newSingleThreadExecutor("Disk I/O");
        ListeningExecutorService httpWriteExecutorService =
            getHttpWriteExecutorService(cacheBuckConfig);
        VersionControlStatsGenerator vcStatsGenerator = null;

        // Eventually, we'll want to get allow websocket and/or nailgun clients to specify locale
        // when connecting. For now, we'll use the default from the server environment.
        Locale locale = Locale.getDefault();

        // create a cached thread pool for cpu intensive tasks
        Map<ExecutionContext.ExecutorPool, ListeningExecutorService> executors = new HashMap<>();
        executors.put(ExecutionContext.ExecutorPool.CPU, listeningDecorator(
                Executors.newCachedThreadPool()));

        // The order of resources in the try-with-resources block is important: the BuckEventBus
        // must be the last resource, so that it is closed first and can deliver its queued events
        // to the other resources before they are closed.
        try (ConsoleLogLevelOverrider consoleLogLevelOverrider =
            new ConsoleLogLevelOverrider(buildId.toString(), verbosity);
             ConsoleHandlerRedirector consoleHandlerRedirector =
            new ConsoleHandlerRedirector(
                buildId.toString(),
                console.getStdErr(),
                Optional.<OutputStream>of(stdErr));
             AbstractConsoleEventBusListener consoleListener =
            createConsoleEventListener(
                clock,
                new SuperConsoleConfig(buckConfig),
                console,
                testConfig.getResultSummaryVerbosity(),
                executionEnvironment,
                webServer,
                locale,
                BuckConstant.getLogPath().resolve("test.log"));
             TempDirectoryCreator tempDirectoryCreator =
            new TempDirectoryCreator(testTempDirOverride);
             AsyncCloseable asyncCloseable = new AsyncCloseable(diskIoExecutorService);
             BuckEventBus buildEventBus = new BuckEventBus(clock, buildId);
             // NOTE: This will only run during the lifetime of the process and will flush on close.
             CounterRegistry counterRegistry = new CounterRegistryImpl(
                MoreExecutors.newSingleThreadScheduledExecutor("CounterAggregatorThread"),
                buildEventBus,
                buckConfig.getCountersFirstFlushIntervalMillis(),
                buckConfig.getCountersFlushIntervalMillis())) {

          buildEventBus.register(HANG_MONITOR.getHangMonitor());

          ArtifactCache artifactCache = asyncCloseable.closeAsync(
              ArtifactCaches.newInstance(
                  cacheBuckConfig,
                  buildEventBus,
                  filesystem,
                  executionEnvironment.getWifiSsid(),
                  httpWriteExecutorService));

          ProgressEstimator progressEstimator = new ProgressEstimator(
              filesystem.getRootPath(),
              buildEventBus,
              objectMapper);
          consoleListener.setProgressEstimator(progressEstimator);

          BuildEnvironmentDescription buildEnvironmentDescription =
              getBuildEnvironmentDescription(
                  executionEnvironment,
                  buckConfig);

          eventListeners = addEventListeners(
              buildEventBus,
              rootCell.getFilesystem(),
              buildId,
              rootCell.getBuckConfig(),
              webServer,
              clock,
              buildEnvironmentDescription,
              console,
              consoleListener,
              rootCell.getKnownBuildRuleTypes(),
              clientEnvironment,
              counterRegistry);

          VersionControlBuckConfig vcBuckConfig = new VersionControlBuckConfig(buckConfig);

          if (!command.isReadOnly() && vcBuckConfig.shouldGenerateStatistics()) {
            vcStatsGenerator = new VersionControlStatsGenerator(
                diskIoExecutorService,
                new DefaultVersionControlCmdLineInterfaceFactory(
                    rootCell.getFilesystem().getRootPath(),
                    new PrintStreamProcessExecutorFactory(),
                    vcBuckConfig,
                    buckConfig.getEnvironment()),
                buildEventBus
            );

            vcStatsGenerator.generateStatsAsync();
          }

          ImmutableList<String> remainingArgs = args.length > 1
              ? ImmutableList.copyOf(Arrays.copyOfRange(args, 1, args.length))
              : ImmutableList.<String>of();

          CommandEvent.Started startedEvent = CommandEvent.started(
              args.length > 0 ? args[0] : "",
              remainingArgs,
              isDaemon);
          buildEventBus.post(startedEvent);

          // Create or get Parser and invalidate cached command parameters.
          Parser parser = null;

          if (isDaemon && watchman != Watchman.NULL_WATCHMAN) {
            try {
              Daemon daemon = getDaemon(rootCell, objectMapper);
              WatchmanWatcher watchmanWatcher = new WatchmanWatcher(
                  watchman.getWatchRoot().or(canonicalRootPath.toString()),
                  daemon.getFileEventBus(),
                  filesystem.getIgnorePaths(),
                  DEFAULT_IGNORE_GLOBS,
                  watchman,
                  daemon.getWatchmanQueryUUID());
              parser = getParserFromDaemon(
                  context,
                  rootCell,
                  startedEvent,
                  buildEventBus,
                  watchmanWatcher);
            } catch (WatchmanWatcherException | IOException e) {
              buildEventBus.post(
                  ConsoleEvent.warning(
                      "Watchman threw an exception while parsing file changes.\n%s",
                      e.getMessage()));
            }
          }

          if (parser == null) {
            TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory(objectMapper);
            parser = new Parser(
                new ParserConfig(rootCell.getBuckConfig()),
                typeCoercerFactory,
                new ConstructorArgMarshaller(typeCoercerFactory));
          }

          ActionGraphCache actionGraphCache = getActionGraphCacheFromDaemon(context, rootCell);

          // Because the Parser is potentially constructed before the CounterRegistry,
          // we need to manually register its counters after it's created.
          //
          // The counters will be unregistered once the counter registry is closed.
          counterRegistry.registerCounters(parser.getCounters());

          // Because the ActionGraphCache is potentially constructed before the CounterRegistry,
          // we need to manually register its counters after it's created. We register the counters
          // of the ActionGraphCache only if we run the daemon.
          if (context.isPresent()) {
            counterRegistry.registerCounters(actionGraphCache.getCounters());
          }

          JavaUtilsLoggingBuildListener.ensureLogFileIsWritten(rootCell.getFilesystem());

          Optional<ProcessManager> processManager;
          if (platform == Platform.WINDOWS) {
            processManager = Optional.absent();
          } else {
            processManager = Optional.<ProcessManager>of(new PkillProcessManager(processExecutor));
          }
          Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier =
              createAndroidPlatformTargetSupplier(
                  androidDirectoryResolver,
                  androidBuckConfig,
                  buildEventBus);

          // At this point, we have parsed options but haven't started
          // running the command yet.  This is a good opportunity to
          // augment the event bus with our serialize-to-file
          // event-listener.
          if (command.subcommand instanceof AbstractCommand) {
            AbstractCommand subcommand = (AbstractCommand) command.subcommand;
            Optional<Path> eventsOutputPath = subcommand.getEventsOutputPath();
            if (eventsOutputPath.isPresent()) {
              BuckEventListener listener =
                  new FileSerializationEventBusListener(eventsOutputPath.get(), objectMapper);
              buildEventBus.register(listener);
            }
          }

          exitCode = command.run(
              new CommandRunnerParams(
                  console,
                  stdIn,
                  rootCell,
                  androidPlatformTargetSupplier,
                  artifactCache,
                  buildEventBus,
                  parser,
                  platform,
                  clientEnvironment,
                  rootCell.getBuckConfig().createDefaultJavaPackageFinder(),
                  objectMapper,
                  clock,
                  processManager,
                  webServer,
                  buckConfig,
                  fileHashCache,
                  executors,
                  buildEnvironmentDescription,
                  actionGraphCache));
          // Wait for HTTP writes to complete.
          closeHttpExecutorService(
              cacheBuckConfig, Optional.of(buildEventBus), httpWriteExecutorService);
          buildEventBus.post(CommandEvent.finished(startedEvent, exitCode));
        } catch (Throwable t) {
          LOG.debug(t, "Failing build on exception.");
          closeHttpExecutorService(
              cacheBuckConfig, Optional.<BuckEventBus>absent(), httpWriteExecutorService);
          closeDiskIoExecutorService(diskIoExecutorService);
          flushEventListeners(console, buildId, eventListeners);
          throw t;
        } finally {
          if (commandSemaphoreAcquired) {
            commandSemaphoreNgClient = Optional.absent();
            BgProcessKiller.disarm();
            commandSemaphore.release(); // Allow another command to execute while outputting traces.
            commandSemaphoreAcquired = false;
          }
          if (isDaemon && shouldCleanUpTrash) {
            // Clean up the trash in the background if this was a buckd
            // read-write command. (We don't bother waiting for it to
            // complete; the cleaner will ensure subsequent cleans are
            // serialized with this one.)
            TRASH_CLEANER.startCleaningDirectory();
          }
          // shut down the cached thread pools
          for (ExecutionContext.ExecutorPool p: executors.keySet()) {
            closeExecutorService(p.toString(), executors.get(p), EXECUTOR_SERVICES_TIMEOUT_SECONDS);
          }
        }
        if (context.isPresent() && !rootCell.getBuckConfig().getFlushEventsBeforeExit()) {
          context.get().in.close(); // Avoid client exit triggering client disconnection handling.
          context.get().exit(exitCode); // Allow nailgun client to exit while outputting traces.
        }

        closeDiskIoExecutorService(diskIoExecutorService);
        flushEventListeners(console, buildId, eventListeners);
        return exitCode;
      }
    } finally {
      if (commandSemaphoreAcquired) {
        commandSemaphoreNgClient = Optional.absent();
        BgProcessKiller.disarm();
        commandSemaphore.release();
      }
    }
  }

  private static void closeExecutorService(
      String executorName,
      ExecutorService executorService,
      long timeoutSeconds) throws InterruptedException {
    executorService.shutdown();
    LOG.info(
        "Awaiting termination of %s executor service. Waiting for all jobs to complete, " +
            "or up to maximum of %s seconds...",
        executorName,
        timeoutSeconds);
    executorService.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
    if (!executorService.isTerminated()) {
      LOG.warn(
          "%s executor service is still running after shutdown request and " +
              "%s second timeout. Shutting down forcefully..",
          executorName,
          timeoutSeconds);
      executorService.shutdownNow();
    }
  }

  private static void closeDiskIoExecutorService(
      ExecutorService diskIoExecutorService) throws InterruptedException {
    closeExecutorService("Disk IO", diskIoExecutorService, DISK_IO_STATS_TIMEOUT_SECONDS);
  }

  private static void closeHttpExecutorService(
      ArtifactCacheBuckConfig buckConfig,
      Optional<BuckEventBus> eventBus,
      ListeningExecutorService httpWriteExecutorService) throws InterruptedException {
    closeExecutorService(
        "HTTP Write",
        httpWriteExecutorService,
        buckConfig.getHttpWriterShutdownTimeout());

    if (eventBus.isPresent()) {
      eventBus.get().post(HttpArtifactCacheEvent.newShutdownEvent());
    }
  }

  private static ListeningExecutorService getHttpWriteExecutorService(
      ArtifactCacheBuckConfig buckConfig) {
    if (buckConfig.hasAtLeastOneWriteableCache()) {
      ExecutorService executorService = MoreExecutors.newMultiThreadExecutor(
          "HTTP Write",
          buckConfig.getHttpMaxConcurrentWrites());

      return listeningDecorator(executorService);
    } else {
      return newDirectExecutorService();
    }
  }

  @VisibleForTesting
  static Supplier<AndroidPlatformTarget> createAndroidPlatformTargetSupplier(
      final AndroidDirectoryResolver androidDirectoryResolver,
      final AndroidBuckConfig androidBuckConfig,
      final BuckEventBus eventBus) {
    // TODO(bolinfest): Only one such Supplier should be created per Cell per Buck execution.
    // Currently, only one Supplier is created per Buck execution because Main creates the Supplier
    // and passes it from above all the way through, but it is not parameterized by Cell.
    //
    // TODO(bolinfest): Every build rule that uses AndroidPlatformTarget must include the result
    // of its getName() method in its RuleKey.
    return new Supplier<AndroidPlatformTarget>() {

      @Nullable
      private AndroidPlatformTarget androidPlatformTarget;

      @Nullable
      private NoAndroidSdkException exception;

      @Override
      public AndroidPlatformTarget get() {
        if (androidPlatformTarget != null) {
          return androidPlatformTarget;
        } else if (exception != null) {
          throw exception;
        }

        Optional<Path> androidSdkDirOption = androidDirectoryResolver.findAndroidSdkDirSafe();
        if (!androidSdkDirOption.isPresent()) {
          exception = new NoAndroidSdkException();
          throw exception;
        }

        String androidPlatformTargetId;
        Optional<String> target = androidBuckConfig.getAndroidTarget();
        if (target.isPresent()) {
          androidPlatformTargetId = target.get();
        } else {
          androidPlatformTargetId = AndroidPlatformTarget.DEFAULT_ANDROID_PLATFORM_TARGET;
          eventBus.post(ConsoleEvent.warning(
              "No Android platform target specified. Using default: %s",
              androidPlatformTargetId));
        }

        Optional<AndroidPlatformTarget> androidPlatformTargetOptional = AndroidPlatformTarget
            .getTargetForId(
                androidPlatformTargetId,
                androidDirectoryResolver,
                androidBuckConfig.getAaptOverride());
        if (androidPlatformTargetOptional.isPresent()) {
          androidPlatformTarget = androidPlatformTargetOptional.get();
          return androidPlatformTarget;
        } else {
          exception = NoAndroidSdkException.createExceptionForPlatformThatCannotBeFound(
              androidPlatformTargetId);
          throw exception;
        }
      }
    };
  }

  /**
   * @return the client environment, which is either the process environment or the
   * environment sent to the daemon by the Nailgun client. This method should always be used
   * in preference to System.getenv() and should be the only call to System.getenv() within the
   * Buck codebase to ensure that the use of the Buck daemon is transparent.
   */
  @SuppressWarnings({"unchecked", "rawtypes"}) // Safe as Property is a Map<String, String>.
  private static ImmutableMap<String, String> getClientEnvironment(Optional<NGContext> context) {
    ImmutableMap<String, String> env;
    if (context.isPresent()) {
      env = ImmutableMap.<String, String>copyOf((Map) context.get().getEnv());
    } else {
      env = ImmutableMap.copyOf(System.getenv());
    }
    return EnvironmentFilter.filteredEnvironment(env, Platform.detect());
  }

  private Parser getParserFromDaemon(
      Optional<NGContext> context,
      Cell cell,
      CommandEvent commandEvent,
      BuckEventBus eventBus,
      WatchmanWatcher watchmanWatcher) throws IOException, InterruptedException {
    // Wire up daemon to new client and get cached Parser.
    Daemon daemon = getDaemon(cell, objectMapper);
    daemon.watchClient(context.get());
    daemon.watchFileSystem(commandEvent, eventBus, watchmanWatcher);
    return daemon.getParser();
  }

  private DefaultFileHashCache getFileHashCacheFromDaemon(Cell cell)
      throws IOException, InterruptedException {
    Daemon daemon = getDaemon(cell, objectMapper);
    return daemon.getFileHashCache();
  }

  private DefaultFileHashCache getBuckOutFileHashCacheFromDaemon(Cell cell)
      throws IOException, InterruptedException {
    Daemon daemon = getDaemon(cell, objectMapper);
    return daemon.getBuckOutHashCache();
  }

  private Optional<WebServer> getWebServerIfDaemon(
      Optional<NGContext> context,
      Cell cell)
      throws IOException, InterruptedException {
    if (context.isPresent()) {
      Daemon daemon = getDaemon(cell, objectMapper);
      return daemon.getWebServer();
    }
    return Optional.absent();
  }

  private ActionGraphCache getActionGraphCacheFromDaemon(
      Optional<NGContext> context,
      Cell cell)
      throws IOException, InterruptedException {
    if (context.isPresent()) {
      return getDaemon(cell, objectMapper).getActionGraphCache();
    }
    return new ActionGraphCache();
  }

  private void loadListenersFromBuckConfig(
      ImmutableList.Builder<BuckEventListener> eventListeners,
      ProjectFilesystem projectFilesystem,
      BuckConfig config) {
    final ImmutableSet<String> paths = config.getListenerJars();
    if (paths.isEmpty()) {
      return;
    }

    URL[] urlsArray = new URL[paths.size()];
    try {
      int i = 0;
      for (String path : paths) {
        String urlString = "file://" + projectFilesystem.getAbsolutifier().apply(Paths.get(path));
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
        throw new HumanReadableException("Error loading event listener class '%s': %s: %s",
            className,
            e.getClass(),
            e.getMessage());
      }
    }
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  private ImmutableList<BuckEventListener> addEventListeners(
      BuckEventBus buckEvents,
      ProjectFilesystem projectFilesystem,
      BuildId buildId,
      BuckConfig config,
      Optional<WebServer> webServer,
      Clock clock,
      BuildEnvironmentDescription buildEnvironmentDescription,
      Console console,
      AbstractConsoleEventBusListener consoleEventBusListener,
      KnownBuildRuleTypes knownBuildRuleTypes,
      ImmutableMap<String, String> environment,
      CounterRegistry counterRegistry) throws InterruptedException {
    ImmutableList.Builder<BuckEventListener> eventListenersBuilder =
        ImmutableList.<BuckEventListener>builder()
            .add(new JavaUtilsLoggingBuildListener())
            .add(consoleEventBusListener)
            .add(new LoggingBuildListener());
    try {
      eventListenersBuilder.add(new ChromeTraceBuildListener(
          projectFilesystem,
          buildId,
          clock,
          objectMapper,
          config.getMaxTraces(),
          config.getCompressTraces()));
    } catch (IOException e) {
      LOG.error("Unable to create ChromeTrace listener!");
    }
    if (webServer.isPresent()) {
      eventListenersBuilder.add(webServer.get().createListener());
    }

    loadListenersFromBuckConfig(eventListenersBuilder, projectFilesystem, config);


    Optional<URI> remoteLogUrl = config.getRemoteLogUrl();
    boolean shouldSample = config.getRemoteLogSampleRate()
        .transform(BuildIdSampler.CREATE_FUNCTION)
        .transform(MoreFunctions.<BuildId, Boolean>applyFunction(buildId))
        .or(true);
    if (remoteLogUrl.isPresent() && shouldSample) {
      eventListenersBuilder.add(
          new RemoteLogUploaderEventListener(
              objectMapper,
              RemoteLoggerFactory.create(remoteLogUrl.get(), objectMapper),
              buildEnvironmentDescription));
    }


    JavaBuckConfig javaBuckConfig = new JavaBuckConfig(config);
    if (!javaBuckConfig.getSkipCheckingMissingDeps()) {
      JavacOptions javacOptions = javaBuckConfig.getDefaultJavacOptions();
      eventListenersBuilder.add(MissingSymbolsHandler.createListener(
              projectFilesystem,
              knownBuildRuleTypes.getAllDescriptions(),
              config,
              buckEvents,
              console,
              javacOptions,
              environment));
    }

    eventListenersBuilder.add(new LoadBalancerEventsListener(counterRegistry));

    eventListenersBuilder.addAll(externalEventsListeners);

    ImmutableList<BuckEventListener> eventListeners = eventListenersBuilder.build();

    for (BuckEventListener eventListener : eventListeners) {
      buckEvents.register(eventListener);
    }

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
      Optional<WebServer> webServer,
      Locale locale,
      Path testLogPath) {
    Verbosity verbosity = console.getVerbosity();

    if (Platform.WINDOWS != Platform.detect() &&
        console.getAnsi().isAnsiTerminal() &&
        !verbosity.shouldPrintCommand() &&
        verbosity.shouldPrintStandardInformation()) {
      SuperConsoleEventBusListener superConsole = new SuperConsoleEventBusListener(
          config,
          console,
          clock,
          testResultSummaryVerbosity,
          executionEnvironment,
          webServer,
          locale,
          testLogPath,
          TimeZone.getDefault());
      superConsole.startRenderScheduler(SUPER_CONSOLE_REFRESH_RATE.getDuration(),
          SUPER_CONSOLE_REFRESH_RATE.getUnit());
      return superConsole;
    }
    return new SimpleConsoleEventBusListener(
        console,
        clock,
        testResultSummaryVerbosity,
        locale,
        testLogPath);
  }

  @VisibleForTesting
  int tryRunMainWithExitCode(
      BuildId buildId,
      Path projectRoot,
      Optional<NGContext> context,
      ImmutableMap<String, String> clientEnvironment,
      String... args)
      throws IOException, InterruptedException {
    try {

      // Reset logging each time we run a command while daemonized.
      // This will cause us to write a new log per command.
      if (context.isPresent()) {
        LOG.debug("Rotating log.");
        LogConfig.flushLogs();
        LogConfig.setupLogging();
      }

      if (LOG.isDebugEnabled()) {
        Long gitCommitTimestamp = Long.getLong("buck.git_commit_timestamp");
        String buildDateStr;
        if (gitCommitTimestamp == null) {
          buildDateStr = "(unknown)";
        } else {
          buildDateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(
              new Date(TimeUnit.SECONDS.toMillis(gitCommitTimestamp)));
        }
        String buildRev = System.getProperty("buck.git_commit", "(unknown)");
        LOG.debug(
            "Starting up (build date %s, rev %s), args: %s",
            buildDateStr,
            buildRev,
            Arrays.toString(args));
        LOG.debug("System properties: %s", System.getProperties());
      }
      return runMainWithExitCode(buildId, projectRoot, context, clientEnvironment, args);
    } catch (HumanReadableException e) {
      Console console = new Console(Verbosity.STANDARD_INFORMATION,
          stdOut,
          stdErr,
          new Ansi(
              AnsiEnvironmentChecking.environmentSupportsAnsiEscapes(platform, clientEnvironment)));
      console.printBuildFailure(e.getHumanReadableErrorMessage());
      return FAIL_EXIT_CODE;
    } catch (InterruptionFailedException e) { // Command could not be interrupted.
      if (context.isPresent()) {
        context.get().getNGServer().shutdown(true); // Exit process to halt command execution.
      }
      return FAIL_EXIT_CODE;
    } finally {
      final boolean isDaemon = context.isPresent();
      if (isDaemon) {
        System.gc(); // Let VM return memory to OS
      }
      LOG.debug("Done.");
    }
  }

  private static BuildId getBuildId(Optional<NGContext> context) {
    String specifiedBuildId;
    if (context.isPresent()) {
      specifiedBuildId = context.get().getEnv().getProperty(BUCK_BUILD_ID_ENV_VAR);
    } else {
      specifiedBuildId = System.getenv().get(BUCK_BUILD_ID_ENV_VAR);
    }
    if (specifiedBuildId == null) {
      return new BuildId();
    } else {
      return new BuildId(specifiedBuildId);
    }
  }

  private static void installUncaughtExceptionHandler(final Optional<NGContext> context) {
    // Override the default uncaught exception handler for background threads to log
    // to java.util.logging then exit the JVM with an error code.
    //
    // (We do this because the default is to just print to stderr and not exit the JVM,
    // which is not safe in a multithreaded environment if the thread held a lock or
    // resource which other threads need.)
    Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          LOG.error(e, "Uncaught exception from thread %s", t);
          if (context.isPresent()) {
            // Shut down the Nailgun server and make sure it stops trapping System.exit().
            //
            // We pass false for exitVM because otherwise Nailgun exits with code 0.
            context.get().getNGServer().shutdown(/* exitVM */ false);
          }
          System.exit(FAIL_EXIT_CODE);
        }
    });
  }

  private void runMainThenExit(String[] args, Optional<NGContext> context) {
    installUncaughtExceptionHandler(context);

    Path projectRoot = Paths.get(".");
    int exitCode = FAIL_EXIT_CODE;
    BuildId buildId = getBuildId(context);

    // Note that try-with-resources blocks close their resources *before*
    // executing catch or finally blocks. That means we can't use one here,
    // since those blocks may need to log.
    CommandThreadAssociation commandThreadAssociation = null;
    ConsoleHandlerRedirector consoleHandlerRedirector = null;

    // Get the client environment, either from this process or from the Nailgun context.
    ImmutableMap<String, String> clientEnvironment = getClientEnvironment(context);

    try {
      commandThreadAssociation =
        new CommandThreadAssociation(buildId.toString());
      // Redirect console logs to the (possibly remote) stderr stream.
      // We do this for both the daemon and non-daemon case so we can
      // unregister the stream when finished.
      consoleHandlerRedirector = new ConsoleHandlerRedirector(
          buildId.toString(),
          stdErr,
          Optional.<OutputStream>absent() /* originalOutputStream */);
      exitCode = tryRunMainWithExitCode(buildId, projectRoot, context, clientEnvironment, args);
    } catch (Throwable t) {
      LOG.error(t, "Uncaught exception at top level");
    } finally {
      LogConfig.flushLogs();
      if (commandThreadAssociation != null) {
        commandThreadAssociation.stop();
      }
      if (consoleHandlerRedirector != null) {
        consoleHandlerRedirector.close();
      }
      // Exit explicitly so that non-daemon threads (of which we use many) don't
      // keep the VM alive.
      System.exit(exitCode);
    }
  }

  public static void main(String[] args) {
    new Main(System.out, System.err, System.in).runMainThenExit(args, Optional.<NGContext>absent());
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
      openPtyLibrary = (Libc.OpenPtyLibrary)
          Native.loadLibrary("libutil", Libc.OpenPtyLibrary.class);
    } else if (platform == Platform.MACOS) {
      Libc.Constants.rTIOCSCTTY = Libc.Constants.DARWIN_TIOCSCTTY;
      Libc.Constants.rFDCLOEXEC = Libc.Constants.DARWIN_FD_CLOEXEC;
      Libc.Constants.rFGETFD = Libc.Constants.DARWIN_F_GETFD;
      Libc.Constants.rFSETFD = Libc.Constants.DARWIN_F_SETFD;
      openPtyLibrary = (Libc.OpenPtyLibrary)
          Native.loadLibrary(
              com.sun.jna.Platform.C_LIBRARY_NAME,
              Libc.OpenPtyLibrary.class);
    } else {
      LOG.info("not enabling process killing on nailgun exit: unknown OS %s", osName);
      return;
    }

    // Making ourselves a session leader with setsid disconnects us from our controlling terminal
    int ret = Libc.INSTANCE.setsid();
    if (ret < 0) {
      LOG.warn(
          "cannot enable background process killing: %s",
          Native.getLastError());
      return;
    }

    LOG.info("enabling background process killing for buckd");

    IntByReference master = new IntByReference();
    IntByReference slave = new IntByReference();

    openPtyLibrary.openpty(master, slave, Pointer.NULL, Pointer.NULL, Pointer.NULL);

    // Deliberately leak the file descriptors for the lifetime of this process; NuProcess can
    // sometimes leak file descriptors to children, so make sure these FDs are marked close-on-exec.
    markFdCloseOnExec(master.getValue());
    markFdCloseOnExec(slave.getValue());

    // Make the pty our controlling terminal; works because we disconnected above with setsid.
    Libc.INSTANCE.ioctl(
        slave.getValue(),
        Pointer.createConstant(Libc.Constants.rTIOCSCTTY),
        0);

    LOG.info("enabled background process killing for buckd");
    isSessionLeader = true;

  }

  public static final class DaemonBootstrap {
    public static void main(String[] args) throws Exception {
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
      NGServer.main(args);
    }
  }

  /**
   * When running as a daemon in the NailGun server, {@link #nailMain(NGContext)} is called instead
   * of {@link #main(String[])} so that the given context can be used to listen for client
   * disconnections and interrupt command processing when they occur.
   */
  public static void nailMain(final NGContext context) throws InterruptedException {
    try (DaemonSlayer.ExecuteCommandHandle handle =
            DaemonSlayer.getSlayer(context).executeCommand()) {
      new Main(context.out, context.err, context.in)
          .runMainThenExit(context.getArgs(), Optional.of(context));
    }
  }


  private static final class DaemonSlayer extends AbstractScheduledService {
    private final NGContext context;
    private final TimeSpan slayerTimeout;
    private int runCount;
    private int lastRunCount;
    private boolean executingCommand;

    private static final class DaemonSlayerInstance {
      final DaemonSlayer daemonSlayer;

      private DaemonSlayerInstance(DaemonSlayer daemonSlayer) {
        this.daemonSlayer = daemonSlayer;
      }
    }

    @Nullable private static volatile DaemonSlayerInstance daemonSlayerInstance;

    public static DaemonSlayer getSlayer(NGContext context) {
      if (daemonSlayerInstance == null) {
        synchronized (DaemonSlayer.class) {
          if (daemonSlayerInstance == null) {
            DaemonSlayer slayer = new DaemonSlayer(context);
            ServiceManager manager = new ServiceManager(ImmutableList.of(slayer));
            manager.startAsync();
            daemonSlayerInstance = new DaemonSlayerInstance(slayer);
          }
        }
      }
      return daemonSlayerInstance.daemonSlayer;
    }

    private DaemonSlayer(NGContext context) {
      this.context = context;
      this.runCount = 0;
      this.lastRunCount = 0;
      this.executingCommand = false;
      this.slayerTimeout = DAEMON_SLAYER_TIMEOUT;
    }

    public class ExecuteCommandHandle implements AutoCloseable {
      private ExecuteCommandHandle() {
        synchronized (DaemonSlayer.this) {
          executingCommand = true;
        }
      }

      @Override
      public void close() {
        synchronized (DaemonSlayer.this) {
          runCount++;
          executingCommand = false;
        }
      }
    }

    public ExecuteCommandHandle executeCommand() {
      return new ExecuteCommandHandle();
    }

    @Override
    protected synchronized void runOneIteration() throws Exception {
      if (!executingCommand && runCount == lastRunCount) {
        context.getNGServer().shutdown(/* exitVM */ true);
      } else {
        lastRunCount = runCount;
      }
    }

    @Override
    protected Scheduler scheduler() {
      return Scheduler.newFixedRateSchedule(
          slayerTimeout.getDuration(),
          slayerTimeout.getDuration(),
          slayerTimeout.getUnit());
    }
  }

}
