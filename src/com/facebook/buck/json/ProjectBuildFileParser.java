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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.bser.BserDeserializer;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuckPyFunction;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.Description;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.InputStreamConsumer;
import com.facebook.buck.util.MoreThrowables;
import com.facebook.buck.util.NamedTemporaryFile;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Threads;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Delegates to buck.py for parsing of buck build files.  Constructed on demand for the
 * parsing phase and must be closed afterward to free up resources.
 */
public class ProjectBuildFileParser implements AutoCloseable {

  /** Path to the buck.py script that is used to evaluate a build file. */
  private static final String BUCK_PY_RESOURCE = "com/facebook/buck/json/buck.py";

  private static final Path PATH_TO_PATHLIB_PY = Paths.get(
      System.getProperty(
          "buck.path_to_pathlib_py",
          "third-party/py/pathlib/pathlib.py"));

  private static final Path PATH_TO_PYWATCHMAN = Paths.get(
      System.getProperty(
          "buck.path_to_pywatchman",
          "third-party/py/pywatchman"));

  private static final Logger LOG = Logger.get(ProjectBuildFileParser.class);

  private final ImmutableMap<String, String> environment;

  private Optional<Path> pathToBuckPy;

  @Nullable private ProcessExecutor.LaunchedProcess buckPyProcess;
  @Nullable private BufferedWriter buckPyStdinWriter;

  private final ProjectBuildFileParserOptions options;
  private final BuckEventBus buckEventBus;
  private final ProcessExecutor processExecutor;
  private final BserDeserializer bserDeserializer;

  private boolean isInitialized;
  private boolean isClosed;

  private boolean enableProfiling;
  @Nullable private NamedTemporaryFile profileOutputFile;
  @Nullable private Thread stderrConsumer;
  @Nullable private ProjectBuildFileParseEvents.Started projectBuildFileParseEventStarted;

  protected ProjectBuildFileParser(
      ProjectBuildFileParserOptions options,
      ImmutableMap<String, String> environment,
      BuckEventBus buckEventBus,
      ProcessExecutor processExecutor) {
    this.pathToBuckPy = Optional.absent();
    this.options = options;
    this.environment = environment;
    this.buckEventBus = buckEventBus;
    this.processExecutor = processExecutor;
    this.bserDeserializer = new BserDeserializer(BserDeserializer.KeyOrdering.SORTED);
  }

  public void setEnableProfiling(boolean enableProfiling) {
    ensureNotClosed();
    ensureNotInitialized();
    this.enableProfiling = enableProfiling;
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

      ProcessExecutorParams params = ProcessExecutorParams.builder()
          .setCommand(buildArgs())
          .setEnvironment(environment)
          .build();

      LOG.debug(
          "Starting buck.py command: %s environment: %s",
          params.getCommand(),
          params.getEnvironment());
      buckPyProcess = processExecutor.launchProcess(params);
      LOG.debug("Started process %s successfully", buckPyProcess);

      OutputStream stdin = buckPyProcess.getOutputStream();
      InputStream stderr = buckPyProcess.getErrorStream();

      stderrConsumer = Threads.namedThread(
          ProjectBuildFileParser.class.getSimpleName(),
          new InputStreamConsumer(
              stderr,
              new InputStreamConsumer.Handler() {
                @Override
                public void handleLine(String line) {
                  buckEventBus.post(
                      ConsoleEvent.warning("Warning raised by BUCK file parser: %s", line));
                }
              }));
      stderrConsumer.start();

      buckPyStdinWriter = new BufferedWriter(new OutputStreamWriter(stdin));
    }
  }

  private ImmutableList<String> buildArgs() throws IOException {
    // Invoking buck.py and read JSON-formatted build rules from its stdout.
    ImmutableList.Builder<String> argBuilder = ImmutableList.builder();

    argBuilder.add(options.getPythonInterpreter());

    // Ask python to unbuffer stdout so that we can coordinate based on the output as it is
    // produced.
    argBuilder.add("-u");

    if (enableProfiling) {
      profileOutputFile = new NamedTemporaryFile("buck-py-profile", ".pstats");
      argBuilder.add("-m");
      argBuilder.add("cProfile");
      argBuilder.add("-o");
      argBuilder.add(profileOutputFile.get().toString());
    }

    argBuilder.add(getPathToBuckPy(options.getDescriptions()).toString());

    if (options.getAllowEmptyGlobs()) {
      argBuilder.add("--allow_empty_globs");
    }

    if (options.getUseWatchmanGlob()) {
      argBuilder.add("--use_watchman_glob");
    }

    if (options.getWatchman().getProjectPrefix().isPresent()) {
      argBuilder.add("--watchman_project_prefix", options.getWatchman().getProjectPrefix().get());
    }

    if (options.getWatchman().getWatchRoot().isPresent()) {
      argBuilder.add("--watchman_watch_root", options.getWatchman().getWatchRoot().get());
    }

    if (options.getWatchmanQueryTimeoutMs().isPresent()) {
      argBuilder.add(
          "--watchman_query_timeout_ms",
          options.getWatchmanQueryTimeoutMs().get().toString());
    }

    argBuilder.add("--project_root", options.getProjectRoot().toAbsolutePath().toString());
    argBuilder.add("--build_file_name", options.getBuildFileName());

    // Tell the parser not to print exceptions to stderr.
    argBuilder.add("--quiet");

    // Add the --include flags.
    for (String include : options.getDefaultIncludes()) {
      argBuilder.add("--include");
      argBuilder.add(include);
    }

    return argBuilder.build();
  }

  /**
   * Collect all rules from a particular build file.
   *
   * @param buildFile should be an absolute path to a build file. Must have rootPath as its prefix.
   */
  public List<Map<String, Object>> getAll(Path buildFile)
      throws BuildFileParseException, InterruptedException {
    List<Map<String, Object>> result = getAllRulesAndMetaRules(buildFile);

    // Strip out the __includes meta rule, which is the last rule.
    return Collections.unmodifiableList(result.subList(0, result.size() - 1));
  }

  /**
   * Collect all rules from a particular build file, along with meta rules about the rules, for
   * example which build files the rules depend on.
   *
   * @param buildFile should be an absolute path to a build file. Must have rootPath as its prefix.
   */
  public List<Map<String, Object>> getAllRulesAndMetaRules(Path buildFile)
      throws BuildFileParseException, InterruptedException {
    try {
      return getAllRulesInternal(buildFile);
    } catch (IOException e) {
      MoreThrowables.propagateIfInterrupt(e);
      throw BuildFileParseException.createForBuildFileParseError(buildFile, e);
    }
  }

  @VisibleForTesting
  protected List<Map<String, Object>> getAllRulesInternal(Path buildFile)
      throws IOException, BuildFileParseException {
    ensureNotClosed();
    initIfNeeded();

    // Check isInitialized implications (to avoid Eradicate warnings).
    Preconditions.checkNotNull(buckPyStdinWriter);
    Preconditions.checkNotNull(buckPyProcess);

    ParseBuckFileEvent.Started parseBuckFileStarted = ParseBuckFileEvent.started(buildFile);
    int numRules = 0;
    buckEventBus.post(parseBuckFileStarted);
    try {
      String buildFileString = buildFile.toString();
      LOG.verbose("Writing to buck.py stdin: %s", buildFileString);
      buckPyStdinWriter.write(buildFileString);
      buckPyStdinWriter.newLine();
      buckPyStdinWriter.flush();

      LOG.debug("Parsing output of process %s...", buckPyProcess);
      List<Map<String, Object>> result;
      try {
        Object deserializedValue = bserDeserializer.deserializeBserValue(
            buckPyProcess.getInputStream());
        result = handleDeserializedValue(buildFile, deserializedValue, buckEventBus);
      } catch (BserDeserializer.BserEofException e) {
        LOG.warn(e, "Parser exited while decoding BSER data");
        throw new IOException("Parser exited unexpectedly", e);
      }
      LOG.verbose("Got rules: %s", result);
      numRules = result.size();
      LOG.debug("Parsed %d rules from process", numRules);
      return result;
    } finally {
      buckEventBus.post(ParseBuckFileEvent.finished(parseBuckFileStarted, numRules));
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> handleDeserializedValue(
      Path buildFile,
      Object deserializedValue,
      BuckEventBus buckEventBus) throws IOException, BuildFileParseException {
    if (!(deserializedValue instanceof Map<?, ?>)) {
      throw new IOException(
          String.format("Invalid parser output (expected map, got %s)", deserializedValue));
    }
    Map<String, Object> decodedResult = (Map<String, Object>) deserializedValue;
    Object diagnostics = decodedResult.get("diagnostics");
    if (diagnostics != null) {
      if (!(diagnostics instanceof List<?>)) {
        throw new IOException(
            String.format("Invalid parser diagnostics (expected list, got %s)", diagnostics));
      }
      List<Map<String, String>> diagnosticsList = (List<Map<String, String>>) diagnostics;
      handleDiagnostics(buildFile, diagnosticsList, buckEventBus);
    }
    Object values = decodedResult.get("values");
    if (!(values instanceof List<?>)) {
      throw new IOException(
          String.format("Invalid parser values (expected list, got %s)", values));
    }
    return (List<Map<String, Object>>) values;
  }

  private static void handleDiagnostics(
      Path buildFile,
      List<Map<String, String>> diagnosticsList,
      BuckEventBus buckEventBus) throws IOException, BuildFileParseException {
    for (Map<String, String> diagnostic : diagnosticsList) {
      String level = diagnostic.get("level");
      String message = diagnostic.get("message");
      if (level == null || message == null) {
        throw new IOException(
            String.format("Invalid diagnostic(level=%s, message=%s)", level, message));
      }
      switch (level) {
        case "warning":
          LOG.warn("Warning raised by BUCK file parser for file %s: %s", buildFile, message);
          buckEventBus.post(
              ConsoleEvent.warning("Warning raised by BUCK file parser: %s", message));
          break;
        case "error":
          LOG.warn("Error raised by BUCK file parser for file %s: %s", buildFile, message);
          buckEventBus.post(
              ConsoleEvent.severe("Error raised by BUCK file parser: %s", message));
          break;
        case "fatal":
          LOG.warn("Fatal error raised by BUCK file parser for file %s: %s", buildFile, message);
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

  @Override
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void close() throws BuildFileParseException, InterruptedException {
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

        if (stderrConsumer != null) {
          stderrConsumer.join();
          stderrConsumer = null;
        }

        if (enableProfiling && profileOutputFile != null) {
          parseProfileOutput(profileOutputFile.get());
        }

        LOG.debug("Waiting for process %s to exit...", buckPyProcess);
        int exitCode = processExecutor.waitForLaunchedProcess(buckPyProcess);
        if (exitCode != 0) {
          LOG.warn("Process %s exited with error code %d", buckPyProcess, exitCode);
          throw BuildFileParseException.createForUnknownParseError(
              String.format("Parser did not exit cleanly (exit code: %d)", exitCode));
        }
        LOG.debug("Process %s exited cleanly.", buckPyProcess);

        try {
          synchronized (this) {
            if (pathToBuckPy.isPresent()) {
              Files.delete(pathToBuckPy.get());
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

  private static void parseProfileOutput(Path profileOutput) throws InterruptedException {
    try {
      LOG.debug("Parsing output of profiler: %s", profileOutput);
      ProcessBuilder processBuilder = new ProcessBuilder(
          "python", "-m", "pstats", profileOutput.toString());
      Process process = processBuilder.start();
      LOG.debug("Started process: %s", processBuilder.command());
      try (OutputStreamWriter stdin =
               new OutputStreamWriter(process.getOutputStream(), Charsets.UTF_8);
           BufferedWriter stdinWriter = new BufferedWriter(stdin);
           InputStreamReader stdout =
               new InputStreamReader(process.getInputStream(), Charsets.UTF_8);
           BufferedReader stdoutReader = new BufferedReader(stdout)) {
        stdinWriter.write("sort cumulative\nstats 25\n");
        stdinWriter.flush();
        stdinWriter.close();
        LOG.debug("Reading process output...");
        String line;
        while ((line = stdoutReader.readLine()) != null) {
          LOG.debug("buck.py profile: %s", line);
        }
        LOG.debug("Done reading process output.");
      }
      process.waitFor();
    } catch (IOException e) {
      LOG.error(e, "Couldn't read profile output file %s", profileOutput);
    }
  }

  private Path getPathToBuckPy(ImmutableSet<Description<?>> descriptions) throws IOException {
    generatePathToBuckPy(descriptions);
    return pathToBuckPy.get();
  }

  private synchronized void generatePathToBuckPy(ImmutableSet<Description<?>> descriptions)
      throws IOException {
    if (pathToBuckPy.isPresent()) {
      return;
    }

    LOG.debug("Creating temporary buck.py instance...");
    // We currently create a temporary buck.py per instance of this class, rather than a single one
    // for the life of this buck invocation. We do this since this is generated in parallel we end
    // up with strange InterruptedExceptions being thrown.
    // TODO(shs96c): This would be the ideal thing to do.
    //    Path buckDotPy =
    //        projectRoot.toPath().resolve(BuckConstant.BIN_DIR).resolve("generated-buck.py");
    Path buckDotPy = Files.createTempFile("buck", ".py");
    Files.createDirectories(buckDotPy.getParent());

    try (Writer out = Files.newBufferedWriter(buckDotPy, UTF_8)) {
      URL resource = Resources.getResource(BUCK_PY_RESOURCE);
      String pathlibDir = PATH_TO_PATHLIB_PY.getParent().toString();
      String watchmanDir = PATH_TO_PYWATCHMAN.toString();
      out.write(
          "from __future__ import with_statement\n" +
          "import sys\n" +
          "sys.path.insert(0, \"" +
              Escaper.escapeAsBashString(MorePaths.pathWithUnixSeparators(pathlibDir)) + "\")\n" +
          "sys.path.insert(0, \"" +
              Escaper.escapeAsBashString(MorePaths.pathWithUnixSeparators(watchmanDir)) + "\")\n");

      Resources.asCharSource(resource, UTF_8).copyTo(out);
      out.write("\n\n");

      ConstructorArgMarshaller inspector = new ConstructorArgMarshaller();
      BuckPyFunction function = new BuckPyFunction(inspector);
      for (Description<?> description : descriptions) {
        out.write(function.toPythonFunction(
            description.getBuildRuleType(),
            description.createUnpopulatedConstructorArg()));
        out.write('\n');
      }

      out.write(Joiner.on("\n").join(
          "if __name__ == '__main__':",
          "  try:",
          "    main()",
          "  except KeyboardInterrupt:",
          "    print >> sys.stderr, 'Killed by User'",
          ""));
    }
    Path normalizedBuckDotPyPath = buckDotPy.normalize();
    pathToBuckPy = Optional.of(normalizedBuckDotPyPath);
    LOG.debug("Created temporary buck.py instance at %s.", normalizedBuckDotPyPath);
  }
}
