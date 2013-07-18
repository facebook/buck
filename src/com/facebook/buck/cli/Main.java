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
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.ChromeTraceBuildListener;
import com.facebook.buck.rules.JavaUtilsLoggingBuildListener;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.LoggingArtifactCacheDecorator;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystemWatcher;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.concurrent.MoreBuckExecutors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.EventBus;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

public final class Main {

  private static final String DEFAULT_BUCK_CONFIG_FILE_NAME = ".buckconfig";
  private static final String DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME = ".buckconfig.local";

  private final PrintStream stdOut;
  private final PrintStream stdErr;

  /**
   * Daemon used to monitor the file system and cache build rules between Main() method
   * invocations is static so that it can outlive Main() objects and survive for the lifetime
   * of the potentially long running Buck process.
   */
  private final class Daemon implements Closeable {

    private final Parser parser;
    private final EventBus fileEventBus;
    private final ProjectFilesystemWatcher filesystemWatcher;
    private final BuckConfig config;

    public Daemon(ProjectFilesystem projectFilesystem,
        BuckConfig config,
        Console console) throws IOException {
      this.config = config;
      this.parser = new Parser(projectFilesystem, new KnownBuildRuleTypes(), console);
      this.fileEventBus = new EventBus("file-change-events");
      this.filesystemWatcher = new ProjectFilesystemWatcher(
          projectFilesystem,
          fileEventBus,
          config.getIgnorePaths(),
          FileSystems.getDefault().newWatchService());
      fileEventBus.register(parser);
    }

    private Parser getParser() {
      return parser;
    }

    private void watchFileSystem() throws IOException {
      filesystemWatcher.postEvents();
    }

    public BuckConfig getConfig() {
      return config;
    }

    @Override
    public void close() throws IOException {
      filesystemWatcher.close();
    }
  }

  @Nullable private static Daemon daemon;

  private boolean isDaemon() {
    return Boolean.getBoolean("buck.daemon");
  }

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
  public Main(PrintStream stdOut, PrintStream stdErr) {
    this.stdOut = Preconditions.checkNotNull(stdOut);
    this.stdErr = Preconditions.checkNotNull(stdErr);
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
   * @param args command line arguments
   * @return an exit code or {@code null} if this is a process that should not exit
   */
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public int runMainWithExitCode(File projectRoot, String... args) throws IOException {
    if (args.length == 0) {
      return usage();
    }

    // Create common command parameters. projectFilesystem initialization looks odd because it needs
    // ignorePaths from a BuckConfig instance, which in turn needs a ProjectFilesystem (i.e. this
    // solves a bootstrapping issue).
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(
        projectRoot,
        createBuckConfig(new ProjectFilesystem(projectRoot)).getIgnorePaths());
    BuckConfig config = createBuckConfig(projectFilesystem);
    Verbosity verbosity = VerbosityParser.parse(args);
    Console console = new Console(verbosity, stdOut, stdErr, config.createAnsi());
    KnownBuildRuleTypes knownBuildRuleTypes = new KnownBuildRuleTypes();

    // Create or get and invalidate cached command parameters.
    Parser parser;
    if (isDaemon()) {
      Daemon daemon = getDaemon(projectFilesystem, config, console);
      daemon.watchFileSystem();
      parser = daemon.getParser();
    } else {
      parser = new Parser(projectFilesystem, knownBuildRuleTypes, console);
    }

    ExecutorService busExecutor = MoreBuckExecutors.newCachedThreadPool(
        new ThreadPoolExecutor.DiscardPolicy());

    Clock clock = new DefaultClock();
    BuckEventBus buildEventBus = new BuckEventBus(
        new AsyncEventBus("buck-build-events", busExecutor),
        clock,
        BuckEventBus.getDefaultThreadIdSupplier());

    // Find and execute command.
    Optional<Command> command = Command.getCommandForName(args[0]);
    if (command.isPresent()) {
      ImmutableList<BuckEventListener> eventListeners =
          addEventListeners(buildEventBus,
              projectFilesystem);
      String[] remainingArgs = new String[args.length - 1];
      System.arraycopy(args, 1, remainingArgs, 0, remainingArgs.length);

      Command executingCommand = command.get();
      String commandName = executingCommand.name().toLowerCase();

      buildEventBus.post(CommandEvent.started(commandName, isDaemon()));

      ArtifactCache artifactCache = new LoggingArtifactCacheDecorator(buildEventBus)
          .decorate(config.createArtifactCache(console));

      int exitCode = executingCommand.execute(remainingArgs, config, new CommandRunnerParams(
          console,
          projectFilesystem,
          new KnownBuildRuleTypes(),
          artifactCache,
          buildEventBus,
          parser));

      buildEventBus.post(CommandEvent.finished(commandName, isDaemon(), exitCode));

      busExecutor.shutdown();
      try {
        busExecutor.awaitTermination(15, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        // Give the eventBus 15 seconds to finish dispatching all events, but if they should fail
        // to finish in that amount of time just eat it, the end user doesn't care.
      }
      for (BuckEventListener eventListener : eventListeners) {
        eventListener.outputTrace();
      }
      return exitCode;
    } else {
      int exitCode = new GenericBuckOptions(stdOut, stdErr).execute(args);
      if (exitCode == GenericBuckOptions.SHOW_MAIN_HELP_SCREEN_EXIT_CODE) {
        return usage();
      } else {
        return exitCode;
      }
    }
  }

  private ImmutableList<BuckEventListener> addEventListeners(BuckEventBus buckEvents,
      ProjectFilesystem projectFilesystem) {
    ImmutableList<BuckEventListener> eventListeners = ImmutableList.of(
      new JavaUtilsLoggingBuildListener(),
      new ChromeTraceBuildListener(projectFilesystem));

    for (BuckEventListener eventListener : eventListeners) {
      buckEvents.register(eventListener);
    }

    JavaUtilsLoggingBuildListener.ensureLogFileIsWritten();
    return eventListeners;
  }


  /**
   * @param projectFilesystem The directory that is the root of the project being built.
   */
  private static BuckConfig createBuckConfig(ProjectFilesystem projectFilesystem)
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
    return BuckConfig.createFromFiles(projectFilesystem, configFiles);
  }

  private int tryRunMainWithExitCode(File projectRoot, String... args) throws IOException {
    try {
      return runMainWithExitCode(projectRoot, args);
    } catch (HumanReadableException e) {
      Console console = new Console(Verbosity.STANDARD_INFORMATION, stdOut, stdErr, new Ansi());
      console.printBuildFailure(e.getHumanReadableErrorMessage());
      return 1;
    }
  }

  public static void main(String[] args) {
    Main main = new Main(System.out, System.err);
    File projectRoot = new File(".");
    int exitCode = 1;
    try {
      exitCode = main.tryRunMainWithExitCode(projectRoot, args);
    } catch (Throwable t) {
      t.printStackTrace();
    } finally {
      // Exit explicitly so that non-daemon threads (of which we use many) don't
      // keep the VM alive.
      System.exit(exitCode);
    }
  }
}
