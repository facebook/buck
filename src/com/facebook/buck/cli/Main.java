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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.LogEvent;
import com.facebook.buck.event.listener.AbstractConsoleEventBusListener;
import com.facebook.buck.event.listener.ChromeTraceBuildListener;
import com.facebook.buck.event.listener.JavaUtilsLoggingBuildListener;
import com.facebook.buck.event.listener.SimpleConsoleEventBusListener;
import com.facebook.buck.event.listener.SuperConsoleEventBusListener;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKey.Builder;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultAndroidDirectoryResolver;
import com.facebook.buck.util.DefaultFileHashCache;
import com.facebook.buck.util.DefaultPropertyFinder;
import com.facebook.buck.util.FileHashCache;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreStrings;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystemWatcher;
import com.facebook.buck.util.PropertyFinder;
import com.facebook.buck.util.ShutdownException;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.WatchServiceWatcher;
import com.facebook.buck.util.WatchmanWatcher;
import com.facebook.buck.util.concurrent.TimeSpan;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.common.reflect.ClassPath;
import com.martiansoftware.nailgun.NGClientListener;
import com.martiansoftware.nailgun.NGContext;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Paths;
import java.util.Arrays;
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

  private static final String DEFAULT_BUCK_CONFIG_FILE_NAME = ".buckconfig";
  private static final String DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME = ".buckconfig.local";

  private static final String BUCK_VERSION_UID_KEY = "buck.version_uid";
  private static final String BUCK_VERSION_UID = System.getProperty(BUCK_VERSION_UID_KEY, "N/A");

  private static final String BUCKD_COLOR_DEFAULT_ENV_VAR = "BUCKD_COLOR_DEFAULT";

  private static final int ARTIFACT_CACHE_TIMEOUT_IN_SECONDS = 15;

  private static final TimeSpan SUPER_CONSOLE_REFRESH_RATE =
      new TimeSpan(100, TimeUnit.MILLISECONDS);

  /**
   * Path to a directory of static content that should be served by the {@link WebServer}.
   */
  private static final String STATIC_CONTENT_DIRECTORY = System.getProperty(
      "buck.path_to_static_content", "webserver/static");

  private final PrintStream stdOut;
  private final PrintStream stdErr;

  private static final Semaphore commandSemaphore = new Semaphore(1);

  private final Platform platform;

  /**
   * Daemon used to monitor the file system and cache build rules between Main() method
   * invocations is static so that it can outlive Main() objects and survive for the lifetime
   * of the potentially long running Buck process.
   */
  private final class Daemon implements Closeable {

    private final Parser parser;
    private final DefaultFileHashCache hashCache;
    private final EventBus fileEventBus;
    private final ProjectFilesystemWatcher filesystemWatcher;
    private final BuckConfig config;
    private final Optional<WebServer> webServer;
    private final Console console;

    public Daemon(ProjectFilesystem projectFilesystem,
                  BuckConfig config,
                  Console console) throws IOException {
      this.config = Preconditions.checkNotNull(config);
      this.console = Preconditions.checkNotNull(console);
      this.hashCache = new DefaultFileHashCache(projectFilesystem, console);
      this.parser = new Parser(projectFilesystem,
          KnownBuildRuleTypes.getDefault(),
          console,
          config.getPythonInterpreter(),
          config.getTempFilePatterns(),
          createRuleKeyBuilderFactory(hashCache));
      this.fileEventBus = new EventBus("file-change-events");
      this.filesystemWatcher = createWatcher(projectFilesystem);
      fileEventBus.register(parser);
      fileEventBus.register(hashCache);
      webServer = createWebServer(config, console, projectFilesystem);
      JavaUtilsLoggingBuildListener.ensureLogFileIsWritten(projectFilesystem);
    }

    private ProjectFilesystemWatcher createWatcher(ProjectFilesystem projectFilesystem)
        throws IOException {
      if (System.getProperty("buck.buckd_watcher", "WatchService").equals("Watchman")) {
        return new WatchmanWatcher(
            projectFilesystem,
            fileEventBus,
            config.getIgnorePaths());
      }
      return new WatchServiceWatcher(
          projectFilesystem,
          fileEventBus,
          config.getIgnorePaths(),
          FileSystems.getDefault().newWatchService());
    }

    private Optional<WebServer> createWebServer(BuckConfig config,
        Console console,
        ProjectFilesystem projectFilesystem) {
      // Enable the web httpserver if it is given by command line parameter or specified in
      // .buckconfig. The presence of a port number is sufficient.
      Optional<String> serverPort =
          Optional.fromNullable(System.getProperty("buck.httpserver.port"));
      if (!serverPort.isPresent()) {
        serverPort = config.getValue("httpserver", "port");
      }
      Optional<WebServer> webServer;
      if (serverPort.isPresent()) {
        String rawPort = serverPort.get();
        try {
          int port = Integer.parseInt(rawPort, 10);
          webServer = Optional.of(new WebServer(port, projectFilesystem, STATIC_CONTENT_DIRECTORY));
        } catch (NumberFormatException e) {
          console.printErrorText(
              String.format("Could not parse port for httpserver: %s.", rawPort));
          webServer = Optional.absent();
        }
      } else {
        webServer = Optional.absent();
      }
      return webServer;
    }

    public Optional<WebServer> getWebServer() {
      return webServer;
    }

    private Parser getParser() {
      return parser;
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
            // Client should no longer be connected, but printing helps detect false disconnections.
            context.err.println("Client disconnected.");
            throw new InterruptedException("Client disconnected.");
          }
        }
      });
    }

    private void watchFileSystem(
        Console console,
        CommandEvent commandEvent,
        BuckEventBus eventBus) throws IOException {

      // Synchronize on parser object so that all outstanding watch events are processed
      // as a single, atomic Parser cache update and are not interleaved with Parser cache
      // invalidations triggered by requests to parse build files or interrupted by client
      // disconnections.
      synchronized (parser) {
        parser.setConsole(console);
        hashCache.setConsole(console);
        parser.recordParseStartTime(eventBus);
        fileEventBus.post(commandEvent);
        filesystemWatcher.postEvents();
      }
    }

    /** @return true if the web server was started successfully. */
    private boolean initWebServer() {
      if (webServer.isPresent()) {
        try {
          webServer.get().start();
          return true;
        } catch (WebServer.WebServerException e) {
          e.printStackTrace(console.getStdErr());
        }
      }
      return false;
    }

    public BuckConfig getConfig() {
      return config;
    }

    @Override
    public void close() throws IOException {
      filesystemWatcher.close();
      shutdownWebServer();
    }

    private void shutdownWebServer() {
      if (webServer.isPresent()) {
        try {
          webServer.get().stop();
        } catch (WebServer.WebServerException e) {
          e.printStackTrace(console.getStdErr());
        }
      }
    }
  }

  @Nullable private static volatile Daemon daemon;

  /**
   * Get or create Daemon.
   */
  private Daemon getDaemon(ProjectFilesystem filesystem,
                           BuckConfig config,
                           Console console) throws IOException {
    if (daemon == null) {
      daemon = new Daemon(filesystem, config, console);
    } else {
      // Buck daemons cache build files within a single project root, changing to a different
      // project root is not supported and will likely result in incorrect builds. The buck and
      // buckd scripts attempt to enforce this, so a change in project root is an error that
      // should be reported rather than silently worked around by invalidating the cache and
      // creating a new daemon object.
      File parserRoot = daemon.getParser().getProjectRoot();
      if (!filesystem.getProjectRoot().equals(parserRoot)) {
        throw new HumanReadableException(String.format("Unsupported root path change from %s to %s",
            filesystem.getProjectRoot(), parserRoot));
      }

      // If Buck config has changed, invalidate the cache and create a new daemon.
      if (!daemon.getConfig().equals(config)) {
        daemon.close();
        daemon = new Daemon(filesystem, config, console);
      }
    }
    return daemon;
  }

  @VisibleForTesting
  static void resetDaemon() {
    daemon = null;
  }

  @VisibleForTesting
  static void registerFileWatcher(Object watcher) {
    Preconditions.checkNotNull(daemon);
    daemon.fileEventBus.register(watcher);
  }

  @VisibleForTesting
  static void watchFilesystem() throws IOException {
    Preconditions.checkNotNull(daemon);
    daemon.filesystemWatcher.postEvents();
  }

  @VisibleForTesting
  public Main(PrintStream stdOut, PrintStream stdErr) {
    this.stdOut = Preconditions.checkNotNull(stdOut);
    this.stdErr = Preconditions.checkNotNull(stdErr);
    this.platform = Platform.detect();
  }

  /** Prints the usage message to standard error. */
  @VisibleForTesting
  int usage() {
    stdErr.println("buck build tool");

    stdErr.println("usage:");
    stdErr.println("  buck [options]");
    stdErr.println("  buck command --help");
    stdErr.println("  buck command [command-options]");
    stdErr.println("available commands:");

    int lengthOfLongestCommand = 0;
    for (Command command : Command.values()) {
      String name = command.name();
      if (name.length() > lengthOfLongestCommand) {
        lengthOfLongestCommand = name.length();
      }
    }

    for (Command command : Command.values()) {
      String name = command.name().toLowerCase();
      stdErr.printf("  %s%s  %s\n",
          name,
          Strings.repeat(" ", lengthOfLongestCommand - name.length()),
          command.getShortDescription());
    }

    stdErr.println("options:");
    new GenericBuckOptions(stdOut, stdErr).printUsage();
    return 1;
  }

  /**
   * @param context an optional NGContext that is present if running inside a Nailgun server.
   * @param args command line arguments
   * @return an exit code or {@code null} if this is a process that should not exit
   */
  public int runMainWithExitCode(File projectRoot, Optional<NGContext> context, String... args)
      throws IOException {
    if (args.length == 0) {
      return usage();
    }

    // Find and execute command.
    int exitCode;
    Command.ParseResult command = Command.parseCommandName(args[0]);
    if (command.getCommand().isPresent()) {
      return executeCommand(projectRoot, command, context, args);
    } else {
      exitCode = new GenericBuckOptions(stdOut, stdErr).execute(args);
      if (exitCode == GenericBuckOptions.SHOW_MAIN_HELP_SCREEN_EXIT_CODE) {
        return usage();
      } else {
        return exitCode;
      }
    }
  }


  /**
   * @param context an optional NGContext that is present if running inside a Nailgun server.
   * @param args command line arguments
   * @return an exit code or {@code null} if this is a process that should not exit
   */
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public int executeCommand(
      File projectRoot,
      Command.ParseResult commandParseResult,
      Optional<NGContext> context,
      String... args) throws IOException {


    // Create common command parameters. projectFilesystem initialization looks odd because it needs
    // ignorePaths from a BuckConfig instance, which in turn needs a ProjectFilesystem (i.e. this
    // solves a bootstrapping issue).
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(
        Paths.get(projectRoot.getPath()),
        createBuckConfig(new ProjectFilesystem(projectRoot), platform).getIgnorePaths());
    BuckConfig config = createBuckConfig(projectFilesystem, platform);
    Verbosity verbosity = VerbosityParser.parse(args);
    Optional<String> color;
    final boolean isDaemon = context.isPresent();
    if (isDaemon && (context.get().getEnv() != null)) {
      String colorString = context.get().getEnv().getProperty(BUCKD_COLOR_DEFAULT_ENV_VAR);
      color = Optional.fromNullable(colorString);
    } else {
      color = Optional.absent();
    }
    final Console console = new Console(verbosity, stdOut, stdErr, config.createAnsi(color));

    if (commandParseResult.getErrorText().isPresent()) {
      console.getStdErr().println(commandParseResult.getErrorText().get());
    }

    // No more early outs: acquire the command semaphore and become the only executing command.
    if (!commandSemaphore.tryAcquire()) {
      return BUSY_EXIT_CODE;
    }

    int exitCode;
    ImmutableList<BuckEventListener> eventListeners;
    String buildId = MoreStrings.createRandomString();
    Clock clock = new DefaultClock();
    ExecutionEnvironment executionEnvironment = new DefaultExecutionEnvironment();

    // The order of resources in the try-with-resources block is important: the BuckEventBus must
    // be the last resource, so that it is closed first and can deliver its queued events to the
    // other resources before they are closed.
    try (AbstractConsoleEventBusListener consoleListener =
             createConsoleEventListener(clock, console, verbosity, executionEnvironment);
         BuckEventBus buildEventBus = new BuckEventBus(clock, buildId)) {
      Optional<WebServer> webServer = getWebServerIfDaemon(context,
          projectFilesystem,
          config,
          console);
      eventListeners = addEventListeners(buildEventBus,
          projectFilesystem,
          config,
          webServer,
          consoleListener);

      ImmutableList<String> remainingArgs = ImmutableList.copyOf(
          Arrays.copyOfRange(args, 1, args.length));

      Command executingCommand = commandParseResult.getCommand().get();
      String commandName = executingCommand.name().toLowerCase();

      CommandEvent commandEvent = CommandEvent.started(commandName, remainingArgs, isDaemon);
      buildEventBus.post(commandEvent);

      // The ArtifactCache is constructed lazily so that we do not try to connect to Cassandra when
      // running commands such as `buck clean`.
      ArtifactCacheFactory artifactCacheFactory = new LoggingArtifactCacheFactory(buildEventBus);

      // Configure the AndroidDirectoryResolver.
      PropertyFinder propertyFinder = new DefaultPropertyFinder(projectFilesystem);
      AndroidDirectoryResolver androidDirectoryResolver =
          new DefaultAndroidDirectoryResolver(
              projectFilesystem,
              config.getNdkVersion(),
              propertyFinder);

      // Create or get Parser and invalidate cached command parameters.
      Parser parser;

      KnownBuildRuleTypes buildRuleTypes =
          KnownBuildRuleTypes.getConfigured(config,
              new ProcessExecutor(console),
              androidDirectoryResolver);

      if (isDaemon) {
        parser = getParserFromDaemon(
            context,
            projectFilesystem,
            config,
            console,
            commandEvent,
            buildEventBus);
      } else {
        // Initialize logging and create new Parser for new process.
        JavaUtilsLoggingBuildListener.ensureLogFileIsWritten(projectFilesystem);
        parser = new Parser(projectFilesystem,
            buildRuleTypes,
            console,
            config.getPythonInterpreter(),
            config.getTempFilePatterns(),
            createRuleKeyBuilderFactory(new DefaultFileHashCache(projectFilesystem, console)));
      }

      exitCode = executingCommand.execute(remainingArgs,
          config,
          new CommandRunnerParams(
              console,
              projectFilesystem,
              androidDirectoryResolver,
              buildRuleTypes,
              artifactCacheFactory,
              buildEventBus,
              parser,
              platform));

      // TODO(user): allocate artifactCacheFactory in the try-with-resources block to avoid leaks.
      artifactCacheFactory.closeCreatedArtifactCaches(ARTIFACT_CACHE_TIMEOUT_IN_SECONDS);

      // If the Daemon is running and serving web traffic, print the URL to the Chrome Trace.
      if (webServer.isPresent()) {
        int port = webServer.get().getPort();
        buildEventBus.post(LogEvent.info(
            "See trace at http://localhost:%s/trace/%s", port, buildId));
      }

      buildEventBus.post(CommandEvent.finished(commandName, remainingArgs, isDaemon, exitCode));
    } finally {
      commandSemaphore.release(); // Allow another command to execute while outputting traces.
    }
    if (isDaemon) {
      context.get().in.close(); // Avoid client exit triggering client disconnection handling.
      context.get().exit(exitCode); // Allow nailgun client to exit while outputting traces.
    }
    for (BuckEventListener eventListener : eventListeners) {
      try {
        eventListener.outputTrace(buildId);
      } catch (RuntimeException e) {
        System.err.println("Skipping over non-fatal error");
        e.printStackTrace();
      }
    }
    return exitCode;
  }

  private Parser getParserFromDaemon(
      Optional<NGContext> context,
      ProjectFilesystem projectFilesystem,
      BuckConfig config, Console console,
      CommandEvent commandEvent,
      BuckEventBus eventBus) throws IOException {
    // Wire up daemon to new client and console and get cached Parser.
    Daemon daemon = getDaemon(projectFilesystem, config, console);
    daemon.watchClient(context.get());
    daemon.watchFileSystem(console, commandEvent, eventBus);
    daemon.initWebServer();
    return daemon.getParser();
  }

  private Optional<WebServer> getWebServerIfDaemon(
      Optional<NGContext> context,
      ProjectFilesystem projectFilesystem,
      BuckConfig config,
      Console console) throws IOException {
    if (context.isPresent()) {
      return getDaemon(projectFilesystem, config, console).getWebServer();
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
      BuckConfig config,
      Optional<WebServer> webServer,
      AbstractConsoleEventBusListener consoleEventBusListener) {
    ImmutableList.Builder<BuckEventListener> eventListenersBuilder =
        ImmutableList.<BuckEventListener>builder()
            .add(new JavaUtilsLoggingBuildListener())
            .add(new ChromeTraceBuildListener(projectFilesystem, config.getMaxTraces()))
            .add(consoleEventBusListener);

    if (webServer.isPresent()) {
      eventListenersBuilder.add(webServer.get().createListener());
    }

    loadListenersFromBuckConfig(eventListenersBuilder, projectFilesystem, config);



    ImmutableList<BuckEventListener> eventListeners = eventListenersBuilder.build();

    for (BuckEventListener eventListener : eventListeners) {
      buckEvents.register(eventListener);
    }

    return eventListeners;
  }

  private AbstractConsoleEventBusListener createConsoleEventListener(
      Clock clock,
      Console console,
      Verbosity verbosity,
      ExecutionEnvironment executionEnvironment) {
    if (console.getAnsi().isAnsiTerminal() && !verbosity.shouldPrintCommand()) {
      SuperConsoleEventBusListener superConsole =
          new SuperConsoleEventBusListener(console, clock, executionEnvironment);
      superConsole.startRenderScheduler(SUPER_CONSOLE_REFRESH_RATE.getDuration(),
          SUPER_CONSOLE_REFRESH_RATE.getUnit());
      return superConsole;
    }
    return new SimpleConsoleEventBusListener(console, clock);
  }


  /**
   * @param projectFilesystem The directory that is the root of the project being built.
   */
  private static BuckConfig createBuckConfig(ProjectFilesystem projectFilesystem, Platform platform)
      throws IOException {
    ImmutableList.Builder<File> configFileBuilder = ImmutableList.builder();
    File configFile = projectFilesystem.getFileForRelativePath(DEFAULT_BUCK_CONFIG_FILE_NAME);
    if (configFile.isFile()) {
      configFileBuilder.add(configFile);
    }
    File overrideConfigFile = projectFilesystem.getFileForRelativePath(
        DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME);
    if (overrideConfigFile.isFile()) {
      configFileBuilder.add(overrideConfigFile);
    }

    ImmutableList<File> configFiles = configFileBuilder.build();
    return BuckConfig.createFromFiles(projectFilesystem, configFiles, platform);
  }

  /**
   * @param hashCache A cache of file content hashes, used to avoid reading and hashing input files.
   */
  private static RuleKeyBuilderFactory createRuleKeyBuilderFactory(final FileHashCache hashCache) {
    return new RuleKeyBuilderFactory() {
      @Override
      public Builder newInstance(BuildRule buildRule) {
        RuleKey.Builder builder = RuleKey.builder(buildRule, hashCache);
        builder.set("buckVersionUid", BUCK_VERSION_UID);
        return builder;
      }
    };
  }

  @VisibleForTesting
  int tryRunMainWithExitCode(File projectRoot, Optional<NGContext> context, String... args)
      throws IOException {
    // TODO(user): enforce write command exclusion, but allow concurrent read only commands?
    try {
      return runMainWithExitCode(projectRoot, context, args);
    } catch (HumanReadableException e) {
      Console console = new Console(Verbosity.STANDARD_INFORMATION,
          stdOut,
          stdErr,
          new Ansi(platform));
      console.printBuildFailure(e.getHumanReadableErrorMessage());
      return FAIL_EXIT_CODE;
    } catch (ShutdownException e) {
      stdErr.println(e);
      e.printStackTrace(stdErr);
      return 0;
    }
  }

  private void runMainThenExit(String[] args, Optional<NGContext> context) {
    File projectRoot = new File(".");
    int exitCode = FAIL_EXIT_CODE;
    try {
      exitCode = tryRunMainWithExitCode(projectRoot, context, args);
    } catch (Throwable t) {
      t.printStackTrace();
    } finally {
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
    new Main(context.out, context.err).runMainThenExit(context.getArgs(), Optional.of(context));
  }
}
