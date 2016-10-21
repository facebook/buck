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

package com.facebook.buck.json;

import com.facebook.buck.bser.BserDeserializer;
import com.facebook.buck.bser.BserSerializer;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.PathOrGlobMatcher;
import com.facebook.buck.io.ProjectWatch;
import com.facebook.buck.io.WatchmanDiagnostic;
import com.facebook.buck.io.WatchmanDiagnosticCache;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.Description;
import com.facebook.buck.util.InputStreamConsumer;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreThrowables;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.concurrent.AssertScopeExclusiveAccess;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import javax.annotation.Nullable;

/**
 * Delegates to buck.py for parsing of buck build files.  Constructed on demand for the
 * parsing phase and must be closed afterward to free up resources.
 */
public class ProjectBuildFileParser implements AutoCloseable {

  private static final Logger LOG = Logger.get(ProjectBuildFileParser.class);

  private final ImmutableMap<String, String> environment;

  @Nullable private BuckPythonProgram buckPythonProgram;
  private Supplier<Path> rawConfigJson;
  private Supplier<Path> ignorePathsJson;

  @Nullable private ProcessExecutor.LaunchedProcess buckPyProcess;
  @Nullable private BufferedOutputStream buckPyStdinWriter;

  private final ProjectBuildFileParserOptions options;
  private final ConstructorArgMarshaller marshaller;
  private final BuckEventBus buckEventBus;
  private final ProcessExecutor processExecutor;
  private final BserDeserializer bserDeserializer;
  private final BserSerializer bserSerializer;
  private final AssertScopeExclusiveAccess assertSingleThreadedParsing;
  private final boolean ignoreBuckAutodepsFiles;
  private final WatchmanDiagnosticCache watchmanDiagnosticCache;

  private boolean isInitialized;
  private boolean isClosed;

  private boolean enableProfiling;
  @Nullable private FutureTask<Void> stderrConsumerTerminationFuture;
  @Nullable private Thread stderrConsumerThread;
  @Nullable private ProjectBuildFileParseEvents.Started projectBuildFileParseEventStarted;

  protected ProjectBuildFileParser(
      final ProjectBuildFileParserOptions options,
      final ConstructorArgMarshaller marshaller,
      ImmutableMap<String, String> environment,
      BuckEventBus buckEventBus,
      ProcessExecutor processExecutor,
      boolean ignoreBuckAutodepsFiles,
      WatchmanDiagnosticCache watchmanDiagnosticCache) {
    this.buckPythonProgram = null;
    this.options = options;
    this.marshaller = marshaller;
    this.environment = environment;
    this.buckEventBus = buckEventBus;
    this.processExecutor = processExecutor;
    this.bserDeserializer = new BserDeserializer(BserDeserializer.KeyOrdering.SORTED);
    this.bserSerializer = new BserSerializer();
    this.assertSingleThreadedParsing = new AssertScopeExclusiveAccess();
    this.ignoreBuckAutodepsFiles = ignoreBuckAutodepsFiles;
    this.watchmanDiagnosticCache = watchmanDiagnosticCache;

    this.rawConfigJson =
        Suppliers.memoize(
            () -> {
              try {
                Path rawConfigJson1 = Files.createTempFile("raw_config", ".json");
                Files.createDirectories(rawConfigJson1.getParent());
                try (OutputStream output =
                         new BufferedOutputStream(Files.newOutputStream(rawConfigJson1))) {
                  bserSerializer.serializeToStream(options.getRawConfig(), output);
                }
                return rawConfigJson1;
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    this.ignorePathsJson =
        Suppliers.memoize(
            () -> {
              try {
                Path ignorePathsJson1 = Files.createTempFile("ignore_paths", ".json");
                Files.createDirectories(ignorePathsJson1.getParent());
                try (OutputStream output =
                         new BufferedOutputStream(Files.newOutputStream(ignorePathsJson1))) {
                  bserSerializer.serializeToStream(
                      options.getIgnorePaths().stream()
                          .map(PathOrGlobMatcher.toPathOrGlob()::apply)
                          .collect(MoreCollectors.toImmutableList()),
                      output);
                }
                return ignorePathsJson1;
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
  }

  public void setEnableProfiling(boolean enableProfiling) {
    ensureNotClosed();
    ensureNotInitialized();
    this.enableProfiling = enableProfiling;
  }

  @VisibleForTesting
  public boolean isClosed() {
    return isClosed;
  }

  private void ensureNotClosed() {
    Preconditions.checkState(!isClosed);
  }

  private void ensureNotInitialized() {
    Preconditions.checkState(!isInitialized);
  }

  /**
   * Initialization on demand moves around the performance impact of creating the Python
   * interpreter to when parsing actually begins.  This makes it easier to attribute this time
   * to the actual parse phase.
   */
  @VisibleForTesting
  public void initIfNeeded() throws IOException {
    ensureNotClosed();
    if (!isInitialized) {
      init();
      isInitialized = true;
    }
  }

  /**
   * Initialize the parser, starting buck.py.
   */
  private void init() throws IOException {
    projectBuildFileParseEventStarted = new ProjectBuildFileParseEvents.Started();
    buckEventBus.post(projectBuildFileParseEventStarted);
    try (SimplePerfEvent.Scope scope = SimplePerfEvent.scope(
        buckEventBus,
        PerfEventId.of("ParserInit"))) {

      ImmutableMap<String, String> pythonEnvironment =
          new ImmutableMap.Builder<String, String>()
              .putAll(new HashMap<String, String>() {{
                putAll(environment);
                put("PYTHONPATH",
                    options.getPythonModuleSearchPath().orElse(""));
              }})
              .build();

      ProcessExecutorParams params = ProcessExecutorParams.builder()
          .setCommand(buildArgs())
          .setEnvironment(pythonEnvironment)
          .build();

      LOG.debug(
          "Starting buck.py command: %s environment: %s",
          params.getCommand(),
          params.getEnvironment());
      buckPyProcess = processExecutor.launchProcess(params);
      LOG.debug("Started process %s successfully", buckPyProcess);

      OutputStream stdin = buckPyProcess.getOutputStream();
      InputStream stderr = buckPyProcess.getErrorStream();

      InputStreamConsumer stderrConsumer = new InputStreamConsumer(
          stderr,
          (InputStreamConsumer.Handler) line -> buckEventBus.post(
              ConsoleEvent.warning("Warning raised by BUCK file parser: %s", line)));
      stderrConsumerTerminationFuture = new FutureTask<>(stderrConsumer);
      stderrConsumerThread = Threads.namedThread(
          ProjectBuildFileParser.class.getSimpleName(),
          stderrConsumerTerminationFuture);
      stderrConsumerThread.start();

      buckPyStdinWriter = new BufferedOutputStream(stdin);
    }
  }

  private ImmutableList<String> buildArgs() throws IOException {
    // Invoking buck.py and read JSON-formatted build rules from its stdout.
    ImmutableList.Builder<String> argBuilder = ImmutableList.builder();

    argBuilder.add(options.getPythonInterpreter());

    // Ask python to unbuffer stdout so that we can coordinate based on the output as it is
    // produced.
    argBuilder.add("-u");

    argBuilder.add(getPathToBuckPy(options.getDescriptions()).toString());

    if (enableProfiling) {
      argBuilder.add("--profile");
    }

    if (ignoreBuckAutodepsFiles) {
      argBuilder.add("--ignore_buck_autodeps_files");
    }

    if (options.getAllowEmptyGlobs()) {
      argBuilder.add("--allow_empty_globs");
    }

    if (options.getUseWatchmanGlob()) {
      argBuilder.add("--use_watchman_glob");
    }

    if (options.getWatchmanGlobStatResults()) {
      argBuilder.add("--watchman_glob_stat_results");
    }

    if (options.getWatchmanUseGlobGenerator()) {
      argBuilder.add("--watchman_use_glob_generator");
    }

    if (options.getWatchman().getSocketPath().isPresent()) {
      argBuilder.add(
          "--watchman_socket_path",
          options.getWatchman().getSocketPath().get().toAbsolutePath().toString());
    }

    if (options.getWatchmanQueryTimeoutMs().isPresent()) {
      argBuilder.add(
          "--watchman_query_timeout_ms",
          options.getWatchmanQueryTimeoutMs().get().toString());
    }

    if (options.getUseMercurialGlob()) {
      argBuilder.add("--use_mercurial_glob");
    }

    if (options.getEnableBuildFileSandboxing()) {
      argBuilder.add("--enable_build_file_sandboxing");
    }

    // Add the --build_file_import_whitelist flags.
    for (String module : options.getBuildFileImportWhitelist()) {
      argBuilder.add("--build_file_import_whitelist");
      argBuilder.add(module);
    }

    argBuilder.add("--project_root", options.getProjectRoot().toAbsolutePath().toString());

    for (ImmutableMap.Entry<String, Path> entry : options.getCellRoots().entrySet()) {
      argBuilder.add("--cell_root", entry.getKey() + "=" + entry.getValue());
    }

    argBuilder.add("--build_file_name", options.getBuildFileName());

    if (!options.getAutodepsFilesHaveSignatures()) {
      argBuilder.add("--no_autodeps_signatures");
    }

    // Tell the parser not to print exceptions to stderr.
    argBuilder.add("--quiet");

    // Add the --include flags.
    for (String include : options.getDefaultIncludes()) {
      argBuilder.add("--include");
      argBuilder.add(include);
    }

    // Add all config settings.
    argBuilder.add("--config", rawConfigJson.get().toString());

    // Add ignore paths.
    argBuilder.add("--ignore_paths", ignorePathsJson.get().toString());

    return argBuilder.build();
  }

  /**
   * Collect all rules from a particular build file.
   *
   * @param buildFile should be an absolute path to a build file. Must have rootPath as its prefix.
   */
  public List<Map<String, Object>> getAll(Path buildFile)
      throws BuildFileParseException, InterruptedException {
    ImmutableList<Map<String, Object>> result = getAllRulesAndMetaRules(buildFile);

    // Strip out the __includes, __configs, and __env meta rules, which are the last rules.
    return Collections.unmodifiableList(result.subList(0, result.size() - 3));
  }

  /**
   * Collect all rules from a particular build file, along with meta rules about the rules, for
   * example which build files the rules depend on.
   *
   * @param buildFile should be an absolute path to a build file. Must have rootPath as its prefix.
   */
  public ImmutableList<Map<String, Object>> getAllRulesAndMetaRules(Path buildFile)
      throws BuildFileParseException, InterruptedException {
    try {
      return getAllRulesInternal(buildFile);
    } catch (IOException e) {
      MoreThrowables.propagateIfInterrupt(e);
      throw BuildFileParseException.createForBuildFileParseError(buildFile, e);
    }
  }

  @VisibleForTesting
  protected ImmutableList<Map<String, Object>> getAllRulesInternal(Path buildFile)
      throws IOException, BuildFileParseException {
    ensureNotClosed();
    initIfNeeded();

    // Check isInitialized implications (to avoid Eradicate warnings).
    Preconditions.checkNotNull(buckPyStdinWriter);
    Preconditions.checkNotNull(buckPyProcess);

    ParseBuckFileEvent.Started parseBuckFileStarted = ParseBuckFileEvent.started(buildFile);
    buckEventBus.post(parseBuckFileStarted);

    ImmutableList<Map<String, Object>> values = ImmutableList.of();
    String profile = "";
    try (AssertScopeExclusiveAccess.Scope scope = assertSingleThreadedParsing.scope()) {
      ProjectWatch projectWatch = options.getWatchman().getProjectWatch();
      bserSerializer.serializeToStream(
          ImmutableMap.of(
              "buildFile", buildFile.toString(),
              "watchRoot", projectWatch.getWatchRoot(),
              "projectPrefix", projectWatch.getProjectPrefix().orElse("")),
          buckPyStdinWriter);
      buckPyStdinWriter.flush();

      LOG.debug("Parsing output of process %s...", buckPyProcess);
      Object deserializedValue;
      try {
        deserializedValue = bserDeserializer.deserializeBserValue(
            buckPyProcess.getInputStream());
      } catch (BserDeserializer.BserEofException e) {
        LOG.warn(e, "Parser exited while decoding BSER data");
        throw new IOException("Parser exited unexpectedly", e);
      }
      BuildFilePythonResult resultObject = handleDeserializedValue(deserializedValue);
      handleDiagnostics(
          buildFile,
          resultObject.getDiagnostics(),
          buckEventBus,
          watchmanDiagnosticCache);
      values = resultObject.getValues();
      LOG.verbose("Got rules: %s", values);
      LOG.debug("Parsed %d rules from process", values.size());
      profile = resultObject.getProfile();
      if (profile != null) {
        LOG.debug("Profile result: %s", profile);
      }
      return values;
    } finally {
      buckEventBus.post(ParseBuckFileEvent.finished(parseBuckFileStarted, values, profile));
    }
  }

  @SuppressWarnings("unchecked")
  private static BuildFilePythonResult handleDeserializedValue(@Nullable Object deserializedValue)
      throws IOException {
    if (!(deserializedValue instanceof Map<?, ?>)) {
      throw new IOException(
          String.format("Invalid parser output (expected map, got %s)", deserializedValue));
    }
    Map<String, Object> decodedResult = (Map<String, Object>) deserializedValue;
    List<Map<String, Object>> values;
    try {
      values = (List<Map<String, Object>>) decodedResult.get("values");
    } catch (ClassCastException e) {
      throw new IOException("Invalid parser values", e);
    }
    List<Map<String, String>> diagnostics;
    try {
      diagnostics = (List<Map<String, String>>) decodedResult.get("diagnostics");
    } catch (ClassCastException e) {
      throw new IOException("Invalid parser diagnostics", e);
    }
    String profile;
    try {
      profile = (String) decodedResult.get("profile");
    } catch (ClassCastException e) {
      throw new IOException("Invalid parser profile", e);
    }
    return BuildFilePythonResult.of(
        values,
        diagnostics == null ? ImmutableList.of() : diagnostics,
        profile == null ? "" : profile);
  }

  private static void handleDiagnostics(
      Path buildFile,
      List<Map<String, String>> diagnosticsList,
      BuckEventBus buckEventBus,
      WatchmanDiagnosticCache watchmanDiagnosticCache) throws IOException, BuildFileParseException {
    for (Map<String, String> diagnostic : diagnosticsList) {
      String level = diagnostic.get("level");
      String message = diagnostic.get("message");
      String source = diagnostic.get("source");
      if (level == null || message == null) {
        throw new IOException(
            String.format(
                "Invalid diagnostic(level=%s, message=%s, source=%s)",
                level,
                message,
                source));
      }
      if (source != null && source.equals("watchman")) {
        handleWatchmanDiagnostic(buildFile, level, message, buckEventBus, watchmanDiagnosticCache);
      } else {
        String header;
        if (source != null) {
          header = buildFile + " (" + source + ")";
        } else {
          header = buildFile.toString();
        }
        switch (level) {
          case "debug":
            LOG.debug("%s: %s", header, message);
            break;
          case "info":
            LOG.info("%s: %s", header, message);
            break;
          case "warning":
            LOG.warn("Warning raised by BUCK file parser for file %s: %s", header, message);
            buckEventBus.post(
                ConsoleEvent.warning("Warning raised by BUCK file parser: %s", message));
            break;
          case "error":
            LOG.warn("Error raised by BUCK file parser for file %s: %s", header, message);
            buckEventBus.post(
                ConsoleEvent.severe("Error raised by BUCK file parser: %s", message));
            break;
          case "fatal":
            LOG.warn("Fatal error raised by BUCK file parser for file %s: %s", header, message);
            throw BuildFileParseException.createForBuildFileParseError(
                buildFile,
                new IOException(message));
          default:
            LOG.warn(
                "Unknown diagnostic (level %s) raised by BUCK file parser for build file %s: %s",
                level,
                buildFile,
                message);
            break;
        }
      }
    }
  }

  private static void handleWatchmanDiagnostic(
      Path buildFile,
      String level,
      String message,
      BuckEventBus buckEventBus,
      WatchmanDiagnosticCache watchmanDiagnosticCache) {
    WatchmanDiagnostic.Level watchmanDiagnosticLevel;
    switch (level) {
      // Watchman itself doesn't issue debug or info, but in case
      // engineers hacking on stuff add calls, let's log them
      // then return.
      case "debug":
        LOG.debug("%s (watchman): %s", buildFile, message);
        return;
      case "info":
        LOG.info("%s (watchman): %s", buildFile, message);
        return;
      case "warning":
        watchmanDiagnosticLevel = WatchmanDiagnostic.Level.WARNING;
        break;
      case "error":
        watchmanDiagnosticLevel = WatchmanDiagnostic.Level.ERROR;
        break;
      default:
        throw new RuntimeException(
            String.format(
                "Unrecognized watchman diagnostic level: %s (message=%s)",
                level,
                message));
    }
    WatchmanDiagnostic watchmanDiagnostic = WatchmanDiagnostic.of(
        watchmanDiagnosticLevel,
        message);
    switch (watchmanDiagnosticCache.addDiagnostic(watchmanDiagnostic)) {
      case NEW_DIAGNOSTIC:
        switch (watchmanDiagnosticLevel) {
          case WARNING:
            buckEventBus.post(
                ConsoleEvent.warning("Watchman raised a warning: %s", message));
            break;
          case ERROR:
            buckEventBus.post(
                ConsoleEvent.severe("Watchman raised an error: %s", message));
            break;
        }
        break;
      case DUPLICATE_DIAGNOSTIC:
        // Nothing to do.
        break;
    }
  }

  @Override
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void close() throws BuildFileParseException, InterruptedException, IOException {
    if (isClosed) {
      return;
    }

    try {
      if (isInitialized) {

        // Check isInitialized implications (to avoid Eradicate warnings).
        Preconditions.checkNotNull(buckPyStdinWriter);
        Preconditions.checkNotNull(buckPyProcess);

        // Allow buck.py to terminate gracefully.
        try {
          buckPyStdinWriter.close();
        } catch (IOException e) {
          // Safe to ignore since we've already flushed everything we wanted
          // to write.
        }

        if (stderrConsumerThread != null) {
          stderrConsumerThread.join();
          stderrConsumerThread = null;
          try {
            Preconditions.checkNotNull(stderrConsumerTerminationFuture).get();
          } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
              throw (IOException) cause;
            } else {
              throw new RuntimeException(e);
            }
          }
          stderrConsumerTerminationFuture = null;
        }

        LOG.debug("Waiting for process %s to exit...", buckPyProcess);
        ProcessExecutor.Result result = processExecutor.waitForLaunchedProcess(buckPyProcess);
        if (result.getExitCode() != 0) {
          LOG.warn(result.getMessageForUnexpectedResult(buckPyProcess.toString()));
          throw BuildFileParseException.createForUnknownParseError(
              result.getMessageForResult("Parser did not exit cleanly"));
        }
        LOG.debug("Process %s exited cleanly.", buckPyProcess);

        try {
          synchronized (this) {
            if (buckPythonProgram != null) {
              buckPythonProgram.close();
            }
          }
        } catch (IOException e) {
          // Eat any exceptions from deleting the temporary buck.py file.
        }

      }
    } finally {
      if (isInitialized) {
        buckEventBus.post(
            new ProjectBuildFileParseEvents.Finished(
                Preconditions.checkNotNull(projectBuildFileParseEventStarted)));
      }
      isClosed = true;
    }
  }

  private synchronized Path getPathToBuckPy(ImmutableSet<Description<?>> descriptions)
      throws IOException {
    if (buckPythonProgram == null) {
      buckPythonProgram = BuckPythonProgram.newInstance(marshaller, descriptions);
    }
    return buckPythonProgram.getExecutablePath();
  }

  @Value.Immutable
  @BuckStyleTuple
  interface AbstractBuildFilePythonResult {
    List<Map<String, Object>> getValues();
    List<Map<String, String>> getDiagnostics();
    String getProfile();
  }
}
