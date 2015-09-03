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

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.android.DefaultAndroidDirectoryResolver;
import com.facebook.buck.android.NoAndroidSdkException;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.listener.AbstractConsoleEventBusListener;
import com.facebook.buck.event.listener.ChromeTraceBuildListener;
import com.facebook.buck.event.listener.FileSerializationEventBusListener;
import com.facebook.buck.event.listener.JavaUtilsLoggingBuildListener;
import com.facebook.buck.event.listener.LoggingBuildListener;
import com.facebook.buck.event.listener.RemoteLogUploaderEventListener;
import com.facebook.buck.event.listener.SimpleConsoleEventBusListener;
import com.facebook.buck.event.listener.SuperConsoleEventBusListener;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.java.JavaBuckConfig;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.log.CommandThreadAssociation;
import com.facebook.buck.log.LogConfig;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.test.TestConfig;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.timing.NanosAdjustedClock;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.AnsiEnvironmentChecking;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultPropertyFinder;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.InterruptionFailedException;
import com.facebook.buck.util.MoreFunctions;
import com.facebook.buck.util.PkillProcessManager;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.PropertyFinder;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.WatchmanWatcher;
import com.facebook.buck.util.WatchmanWatcherException;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.MultiProjectFileHashCache;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.concurrent.TimeSpan;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.environment.EnvironmentFilter;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.network.RemoteLoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk7.Jdk7Module;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.common.reflect.ClassPath;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.common.util.concurrent.ServiceManager;
import com.martiansoftware.nailgun.NGClientListener;
import com.martiansoftware.nailgun.NGContext;

import org.kohsuke.args4j.CmdLineException;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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

  private static final int ARTIFACT_CACHE_TIMEOUT_IN_SECONDS = 15;

  private static final TimeSpan DAEMON_SLAYER_TIMEOUT = new TimeSpan(2, TimeUnit.HOURS);

  private static final TimeSpan SUPER_CONSOLE_REFRESH_RATE =
      new TimeSpan(100, TimeUnit.MILLISECONDS);

  /**
   * Path to a directory of static content that should be served by the {@link WebServer}.
   */
  private static final String STATIC_CONTENT_DIRECTORY = System.getProperty(
      "buck.path_to_static_content", "webserver/static");

  private final PrintStream stdOut;
  private final PrintStream stdErr;
  private final ImmutableList<BuckEventListener> externalEventsListeners;

  private static final Semaphore commandSemaphore = new Semaphore(1);

  private final Platform platform;

  // It's important to re-use this object for perf:
  // http://wiki.fasterxml.com/JacksonBestPracticesPerformance
  private final ObjectMapper objectMapper;

  // This is a hack to work around a perf issue where generated Xcode IDE files
  // trip WatchmanWatcher, causing buck project to take a long time to run.
  private static final ImmutableSet<String> DEFAULT_IGNORE_GLOBS =
      ImmutableSet.of("*.pbxproj", "*.xcscheme", "*.xcworkspacedata");

  private static final Logger LOG = Logger.get(Main.class);

  /**
   * Daemon used to monitor the file system and cache build rules between Main() method
   * invocations is static so that it can outlive Main() objects and survive for the lifetime
   * of the potentially long running Buck process.
   */
  private static final class Daemon implements Closeable {

    private final Repository repository;
    private final Parser parser;
    private final DefaultFileHashCache hashCache;
    private final EventBus fileEventBus;
    private final Optional<WebServer> webServer;
    private final UUID watchmanQueryUUID;

    public Daemon(
        Repository repository,
        ParserConfig.GlobHandler globHandler)
        throws IOException, InterruptedException {
      this.repository = repository;
      this.hashCache = new DefaultFileHashCache(repository.getFilesystem());
      this.fileEventBus = new EventBus("file-change-events");

      this.parser = Parser.createBuildFileParser(
          repository,
          globHandler == ParserConfig.GlobHandler.WATCHMAN);
      fileEventBus.register(parser);
      fileEventBus.register(hashCache);

      webServer = createWebServer(repository.getBuckConfig(), repository.getFilesystem());
      watchmanQueryUUID = UUID.randomUUID();
      JavaUtilsLoggingBuildListener.ensureLogFileIsWritten(repository.getFilesystem());
    }

    private Optional<WebServer> createWebServer(BuckConfig config, ProjectFilesystem filesystem) {
      Optional<Integer> port = getValidWebServerPort(config);
      if (port.isPresent()) {
        WebServer webServer = new WebServer(port.get(), filesystem, STATIC_CONTENT_DIRECTORY);
        return Optional.of(webServer);
      } else {
        return Optional.absent();
      }
    }

    /**
     * If the return value is not absent, then the port is a nonnegative integer. This means that
     * specifying a port of -1 effectively disables the WebServer.
     */
    private static Optional<Integer> getValidWebServerPort(BuckConfig config) {
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

    private DefaultFileHashCache getFileHashCache() {
      return hashCache;
    }

    private void watchClient(final NGContext context) {
      context.addClientListener(new NGClientListener() {
        @Override
        public void clientDisconnected() throws InterruptedException {

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
        watchmanWatcher.postEvents(eventBus);
      }
    }

    /** @return true if the web server was started successfully. */
    private boolean initWebServer() {
      if (webServer.isPresent()) {
        try {
          webServer.get().start();
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
      Repository repository,
      ParserConfig.GlobHandler globHandler)
      throws IOException, InterruptedException  {
    Path rootPath = repository.getFilesystem().getRootPath();
    if (daemon == null) {
      LOG.debug("Starting up daemon for project root [%s]", rootPath);
      daemon = new Daemon(repository, globHandler);
    } else {
      // Buck daemons cache build files within a single project root, changing to a different
      // project root is not supported and will likely result in incorrect builds. The buck and
      // buckd scripts attempt to enforce this, so a change in project root is an error that
      // should be reported rather than silently worked around by invalidating the cache and
      // creating a new daemon object.
      Path parserRoot = repository.getFilesystem().getRootPath();
      if (!rootPath.equals(parserRoot)) {
        throw new HumanReadableException(String.format("Unsupported root path change from %s to %s",
            rootPath, parserRoot));
      }

      // If Buck config or the AndroidDirectoryResolver has changed, invalidate the cache and
      // create a new daemon.
      if (!daemon.repository.equals(repository)) {
        LOG.info("Shutting down and restarting daemon on config or directory resolver change.");
        daemon.close();
        daemon = new Daemon(repository, globHandler);
      }
    }
    return daemon;
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
  public Main(PrintStream stdOut, PrintStream stdErr) {
    this(stdOut, stdErr, ImmutableList.<BuckEventListener>of());
  }

  @VisibleForTesting
  public Main(
      PrintStream stdOut,
      PrintStream stdErr,
      List<BuckEventListener> externalEventsListeners) {
    this.stdOut = stdOut;
    this.stdErr = stdErr;
    this.platform = Platform.detect();
    this.objectMapper = new ObjectMapper();
    // Add support for serializing Path and other JDK 7 objects.
    this.objectMapper.registerModule(new Jdk7Module());
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
        stdErr.println("Skipping over non-fatal error");
        e.printStackTrace(stdErr);
      }
    }
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
    final boolean isDaemon = context.isPresent();
    if (isDaemon && (context.get().getEnv() != null)) {
      String colorString = context.get().getEnv().getProperty(BUCKD_COLOR_DEFAULT_ENV_VAR);
      color = Optional.fromNullable(colorString);
    } else {
      color = Optional.absent();
    }

    // We need a BuckConfig to create a Console, but we get BuckConfig from Repository, and we need
    // a Console to create a Repository. To break this bootstrapping loop, create a temporary
    // BuckConfig.
    // TODO(jacko): We probably shouldn't rely on BuckConfig to instantiate Console.
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

    ImmutableMap<String, ImmutableMap<String, String>> configOverrides =
        command.getConfigOverrides();

    Path canonicalRootPath = projectRoot.toRealPath().normalize();

    Config rawConfig = Config.createDefaultConfig(canonicalRootPath, configOverrides);
    ProjectFilesystem filesystem = new ProjectFilesystem(canonicalRootPath, rawConfig);
    BuckConfig buckConfig = new BuckConfig(rawConfig, filesystem, platform, clientEnvironment);
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
    if (!command.isReadOnly()) {
      commandSemaphoreAcquired = commandSemaphore.tryAcquire();
      if (!commandSemaphoreAcquired) {
        return BUSY_EXIT_CODE;
      }
    }

    PropertyFinder propertyFinder = new DefaultPropertyFinder(
        filesystem,
        clientEnvironment);
    AndroidBuckConfig androidBuckConfig = new AndroidBuckConfig(buckConfig, platform);
    AndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            filesystem,
            androidBuckConfig.getNdkVersion(),
            propertyFinder);

    ProcessExecutor processExecutor = new ProcessExecutor(console);

    PythonBuckConfig pythonBuckConfig = new PythonBuckConfig(buckConfig, new ExecutableFinder());
    KnownBuildRuleTypes buildRuleTypes =
        KnownBuildRuleTypes.createInstance(
            buckConfig,
            processExecutor,
            androidDirectoryResolver,
            pythonBuckConfig.getPythonEnvironment(
                processExecutor));

    ParserConfig parserConfig = new ParserConfig(buckConfig);
    Watchman watchman;
    ParserConfig.GlobHandler globHandler;
    if (context.isPresent() || parserConfig.getGlobHandler() == ParserConfig.GlobHandler.WATCHMAN) {
      watchman = Watchman.build(projectRoot, clientEnvironment, console);
      if (parserConfig.getGlobHandler() == ParserConfig.GlobHandler.WATCHMAN &&
          watchman.hasWildmatchGlob()) {
        globHandler = ParserConfig.GlobHandler.WATCHMAN;
      } else {
        globHandler = ParserConfig.GlobHandler.PYTHON;
      }

      LOG.debug(
          "Watchman capabilities: %s Watch root: %s Project prefix: %s Glob handler config: %s " +
          "Final glob handler: %s",
          watchman.getCapabilities(),
          watchman.getWatchRoot(),
          watchman.getProjectPrefix(),
          parserConfig.getGlobHandler(),
          globHandler);

    } else {
      watchman = Watchman.NULL_WATCHMAN;
      globHandler = ParserConfig.GlobHandler.PYTHON;
    }

    Repository rootRepository = new Repository(
        Optional.<String>absent(),
        filesystem,
        watchman,
        buckConfig,
        buildRuleTypes,
        androidDirectoryResolver);

    int exitCode;
    ImmutableList<BuckEventListener> eventListeners = ImmutableList.of();
    Clock clock;
    if (BUCKD_LAUNCH_TIME_NANOS.isPresent()) {
      long nanosEpoch = Long.parseLong(BUCKD_LAUNCH_TIME_NANOS.get(), 10);
      LOG.verbose("Using nanos epoch: %d", nanosEpoch);
      clock = new NanosAdjustedClock(nanosEpoch);
    } else {
      clock = new DefaultClock();
    }
    ExecutionEnvironment executionEnvironment = new DefaultExecutionEnvironment(
        processExecutor,
        clientEnvironment,
        // TODO(user): Thread through properties from client environment.
        System.getProperties());

    ProjectFileHashCache repoHashCache;
    if (isDaemon) {
      repoHashCache = getFileHashCacheFromDaemon(
          rootRepository,
          globHandler);
    } else {
      repoHashCache = new DefaultFileHashCache(rootRepository.getFilesystem());
    }

    // Build up the hash cache, which is a collection of the stateful repo cache and some per-run
    // caches.
    FileHashCache fileHashCache =
        new MultiProjectFileHashCache(
            ImmutableList.of(
                repoHashCache,
                // A cache which caches hashes of repo-relative paths which may have been ignore by
                // the main repo cache, and only serves to prevent rehashing the same file multiple
                // times in a single run.
                new DefaultFileHashCache(
                    new ProjectFilesystem(rootRepository.getFilesystem().getRootPath())),
                // A cache which caches hashes of absolute paths which my be accessed by certain
                // rules (e.g. /usr/bin/gcc), and only serves to prevent rehashing the same file
                // multiple times in a single run.
                new DefaultFileHashCache(new ProjectFilesystem(Paths.get("/")))));

    @Nullable ArtifactCacheFactory artifactCacheFactory = null;
    Optional<WebServer> webServer = getWebServerIfDaemon(
        context,
        rootRepository,
        globHandler);

    TestConfig testConfig = new TestConfig(buckConfig);

    // The order of resources in the try-with-resources block is important: the BuckEventBus must
    // be the last resource, so that it is closed first and can deliver its queued events to the
    // other resources before they are closed.
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
                 console,
                 testConfig.getResultSummaryVerbosity(),
                 executionEnvironment,
                 webServer);
         BuckEventBus buildEventBus = new BuckEventBus(clock, buildId)) {
      artifactCacheFactory = new LoggingArtifactCacheFactory(executionEnvironment, buildEventBus);

      eventListeners = addEventListeners(buildEventBus,
          rootRepository.getFilesystem(),
          buildId,
          rootRepository.getBuckConfig(),
          webServer,
          clock,
          executionEnvironment,
          console,
          consoleListener,
          rootRepository.getKnownBuildRuleTypes(),
          clientEnvironment);

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

      if (isDaemon) {
        try {
          Daemon daemon = getDaemon(rootRepository, globHandler);
          WatchmanWatcher watchmanWatcher = new WatchmanWatcher(
             watchman.getWatchRoot().or(canonicalRootPath.toString()),
             daemon.getFileEventBus(),
             clock,
             objectMapper,
             processExecutor,
             filesystem.getIgnorePaths(),
             DEFAULT_IGNORE_GLOBS,
             watchman,
             daemon.getWatchmanQueryUUID());
         parser = getParserFromDaemon(
              context,
              rootRepository,
              startedEvent,
              buildEventBus,
              watchmanWatcher,
              globHandler);
        } catch (WatchmanWatcherException | IOException e) {
          buildEventBus.post(
              ConsoleEvent.warning(
                  "Watchman threw an exception while parsing file changes.\n%s",
                  e.getMessage()));
        }
      }

      if (parser == null) {
        parser = Parser.createBuildFileParser(
            rootRepository,
            globHandler == ParserConfig.GlobHandler.WATCHMAN);
      }
      JavaUtilsLoggingBuildListener.ensureLogFileIsWritten(rootRepository.getFilesystem());

      Optional<ProcessManager> processManager;
      if (platform == Platform.WINDOWS) {
        processManager = Optional.absent();
      } else {
        processManager = Optional.<ProcessManager>of(new PkillProcessManager(processExecutor));
      }
      Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier =
          createAndroidPlatformTargetSupplier(
              rootRepository.getAndroidDirectoryResolver(),
              androidBuckConfig,
              buildEventBus);

      // At this point, we have parsed options but haven't started running the command yet.  This is
      // a good opportunity to augment the event bus with our serialize-to-file event-listener.
      if (command.subcommand instanceof AbstractCommand) {
        AbstractCommand subcommand = (AbstractCommand) command.subcommand;
        Optional<Path> eventsOutputPath = subcommand.getEventsOutputPath();
        if (eventsOutputPath.isPresent()) {
          BuckEventListener listener =
              new FileSerializationEventBusListener(eventsOutputPath.get());
          buildEventBus.register(listener);
        }
      }

      exitCode = command.run(
          new CommandRunnerParams(
              console,
              rootRepository,
              androidPlatformTargetSupplier,
              artifactCacheFactory,
              buildEventBus,
              parser,
              platform,
              clientEnvironment,
              rootRepository.getBuckConfig().createDefaultJavaPackageFinder(),
              objectMapper,
              clock,
              processManager,
              webServer,
              buckConfig,
              fileHashCache));
      parser.cleanCache();
      buildEventBus.post(CommandEvent.finished(startedEvent, exitCode));
    } catch (Throwable t) {
      LOG.debug(t, "Failing build on exception.");
      closeCreatedArtifactCaches(artifactCacheFactory); // Close cache before exit on exception.
      flushEventListeners(console, buildId, eventListeners);
      throw t;
    } finally {
      if (commandSemaphoreAcquired) {
        commandSemaphore.release(); // Allow another command to execute while outputting traces.
      }
    }
    if (isDaemon && !rootRepository.getBuckConfig().getFlushEventsBeforeExit()) {
      context.get().in.close(); // Avoid client exit triggering client disconnection handling.
      context.get().exit(exitCode); // Allow nailgun client to exit while outputting traces.
    }
    closeCreatedArtifactCaches(artifactCacheFactory); // Wait for cache close after client exit.
    flushEventListeners(console, buildId, eventListeners);
    return exitCode;
  }

  @VisibleForTesting
  static Supplier<AndroidPlatformTarget> createAndroidPlatformTargetSupplier(
      final AndroidDirectoryResolver androidDirectoryResolver,
      final AndroidBuckConfig androidBuckConfig,
      final BuckEventBus eventBus) {
    // TODO(mbolin): Only one such Supplier should be created per Repository per Buck execution.
    // Currently, only one Supplier is created per Buck execution because Main creates the Supplier
    // and passes it from above all the way through, but it is not parameterized by Repository. It
    // seems like the Repository concept is not fully baked, so this is likely one of many
    // multi-Repository issues that need to be addressed to support it properly.
    //
    // TODO(mbolin): Every build rule that uses AndroidPlatformTarget must include the result of its
    // getName() method in its RuleKey.
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

  private static void closeCreatedArtifactCaches(
      @Nullable ArtifactCacheFactory artifactCacheFactory)
      throws InterruptedException {
    if (null != artifactCacheFactory) {
      artifactCacheFactory.closeCreatedArtifactCaches(ARTIFACT_CACHE_TIMEOUT_IN_SECONDS);
    }
  }

  private Parser getParserFromDaemon(
      Optional<NGContext> context,
      Repository repository,
      CommandEvent commandEvent,
      BuckEventBus eventBus,
      WatchmanWatcher watchmanWatcher,
      ParserConfig.GlobHandler globHandler) throws IOException, InterruptedException {
    // Wire up daemon to new client and get cached Parser.
    Daemon daemon = getDaemon(repository, globHandler);
    daemon.watchClient(context.get());
    daemon.watchFileSystem(commandEvent, eventBus, watchmanWatcher);
    daemon.initWebServer();
    return daemon.getParser();
  }

  private DefaultFileHashCache getFileHashCacheFromDaemon(
      Repository repository,
      ParserConfig.GlobHandler globHandler)
      throws IOException, InterruptedException {
    Daemon daemon = getDaemon(repository, globHandler);
    return daemon.getFileHashCache();
  }

  private Optional<WebServer> getWebServerIfDaemon(
      Optional<NGContext> context,
      Repository repository,
      ParserConfig.GlobHandler globHandler)
      throws IOException, InterruptedException  {
    if (context.isPresent()) {
      Daemon daemon = getDaemon(repository, globHandler);
      return daemon.getWebServer();
    }
    return Optional.absent();
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

  private ImmutableList<BuckEventListener> addEventListeners(
      BuckEventBus buckEvents,
      ProjectFilesystem projectFilesystem,
      BuildId buildId,
      BuckConfig config,
      Optional<WebServer> webServer,
      Clock clock,
      ExecutionEnvironment executionEnvironment,
      Console console,
      AbstractConsoleEventBusListener consoleEventBusListener,
      KnownBuildRuleTypes knownBuildRuleTypes,
      ImmutableMap<String, String> environment) throws InterruptedException {
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
    ImmutableMap<String, String> environmentExtraData = ImmutableMap.of();
    boolean shouldSample = config.getRemoteLogSampleRate()
        .transform(BuildIdSampler.CREATE_FUNCTION)
        .transform(MoreFunctions.<BuildId, Boolean>applyFunction(buildId))
        .or(true);
    if (remoteLogUrl.isPresent() && shouldSample) {
      eventListenersBuilder.add(
          new RemoteLogUploaderEventListener(
              objectMapper,
              RemoteLoggerFactory.create(remoteLogUrl.get(), objectMapper),
              BuildEnvironmentDescription.of(
                  executionEnvironment,
                  config.getArtifactCacheModes(),
                  environmentExtraData)
          ));
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

    eventListenersBuilder.addAll(externalEventsListeners);

    ImmutableList<BuckEventListener> eventListeners = eventListenersBuilder.build();

    for (BuckEventListener eventListener : eventListeners) {
      buckEvents.register(eventListener);
    }

    return eventListeners;
  }

  private AbstractConsoleEventBusListener createConsoleEventListener(
      Clock clock,
      Console console,
      TestResultSummaryVerbosity testResultSummaryVerbosity,
      ExecutionEnvironment executionEnvironment,
      Optional<WebServer> webServer) {
    Verbosity verbosity = console.getVerbosity();

    if (Platform.WINDOWS != Platform.detect() &&
        console.getAnsi().isAnsiTerminal() &&
        !verbosity.shouldPrintCommand() &&
        verbosity.shouldPrintStandardInformation()) {
      SuperConsoleEventBusListener superConsole = new SuperConsoleEventBusListener(
          console,
          clock,
          testResultSummaryVerbosity,
          executionEnvironment,
          webServer);
      superConsole.startRenderScheduler(SUPER_CONSOLE_REFRESH_RATE.getDuration(),
          SUPER_CONSOLE_REFRESH_RATE.getUnit());
      return superConsole;
    }
    return new SimpleConsoleEventBusListener(console, clock, testResultSummaryVerbosity);
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

  private void runMainThenExit(String[] args, Optional<NGContext> context) {
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
    new Main(System.out, System.err).runMainThenExit(args, Optional.<NGContext>absent());
  }

  /**
   * When running as a daemon in the NailGun server, {@link #nailMain(NGContext)} is called instead
   * of {@link #main(String[])} so that the given context can be used to listen for client
   * disconnections and interrupt command processing when they occur.
   */
  public static void nailMain(final NGContext context) throws InterruptedException {
    try (DaemonSlayer.ExecuteCommandHandle handle =
            DaemonSlayer.getSlayer(context).executeCommand()) {
      new Main(context.out, context.err).runMainThenExit(context.getArgs(), Optional.of(context));
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
