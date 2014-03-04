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

import com.facebook.buck.rules.BuckPyFunction;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.Description;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.InputStreamConsumer;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Threads;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Delegates to buck.py for parsing of buck build files.  Constructed on demand for the
 * parsing phase and must be closed afterward to free up resources.
 */
public class ProjectBuildFileParser implements AutoCloseable {

  /** Options for parsing build files. */
  public enum Option {
    /** Don't output null items from {@code buck.py}. */
    STRIP_NULL,
  }

  /** Path to the buck.py script that is used to evaluate a build file. */
  private static final String PATH_TO_BUCK_PY = System.getProperty("buck.path_to_buck_py",
      "src/com/facebook/buck/parser/buck.py");

  private Optional<Path> pathToBuckPy;

  private Process buckPyProcess;

  private BuildFileToJsonParser buckPyStdoutParser;
  private BufferedWriter buckPyStdinWriter;

  private final File projectRoot;
  private final ImmutableSet<Path> ignorePaths;
  private final ImmutableSet<Description<?>> descriptions;
  private final ImmutableList<String> commonIncludes;
  private final String pythonInterpreter;
  private final EnumSet<Option> parseOptions;
  private final Console console;

  private boolean isServerMode;

  private boolean isInitialized;
  private boolean isClosed;

  protected ProjectBuildFileParser(
      ProjectFilesystem projectFilesystem,
      Iterable<String> commonIncludes,
      String pythonInterpreter,
      ImmutableSet<Description<?>> descriptions,
      EnumSet<Option> parseOptions,
      Console console) {
    this.projectRoot = projectFilesystem.getProjectRoot();
    this.descriptions = Preconditions.checkNotNull(descriptions);
    this.ignorePaths = projectFilesystem.getIgnorePaths();
    this.commonIncludes = ImmutableList.copyOf(commonIncludes);
    this.pythonInterpreter = Preconditions.checkNotNull(pythonInterpreter);
    this.parseOptions = parseOptions;
    this.pathToBuckPy = Optional.absent();
    this.console = Preconditions.checkNotNull(console);

    // Default to server mode unless explicitly unset internally.
    setServerMode(true);
  }

  /**
   * Sets whether buck.py will use --server mode.  Server mode communicates via
   * stdin/stdout to accept new BUCK files to parse in a long running fashion.  It
   * also changes the stdout format so that output has an extra layer of structure
   * sufficient to communicate state and coordinate on individual BUCK files
   * submitted.
   * <p>
   * Note that you must not invoke this method after initialization.
   */
  private void setServerMode(boolean isServerMode) {
    ensureNotClosed();
    ensureNotInitialized();

    this.isServerMode = isServerMode;
  }

  private void ensureNotClosed() {
    Preconditions.checkState(!isClosed);
  }

  private void ensureNotInitialized() {
    Preconditions.checkState(!isInitialized);
  }

  /**
   * Initialization on demand moves around the performance impact of creating the Jython
   * interpreter to when parsing actually begins.  This makes it easier to attribute this time
   * to the actual parse phase.
   */
  private void initIfNeeded() throws IOException {
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
    ProcessBuilder processBuilder = new ProcessBuilder(buildArgs());
    buckPyProcess = processBuilder.start();

    OutputStream stdin = buckPyProcess.getOutputStream();
    InputStream stderr = buckPyProcess.getErrorStream();

    Thread stderrConsumer = Threads.namedThread(
        ProjectBuildFileParser.class.getSimpleName(),
        new InputStreamConsumer(stderr,
            console.getStdErr(),
            console.getAnsi()));
    stderrConsumer.start();

    buckPyStdinWriter = new BufferedWriter(new OutputStreamWriter(stdin));

    Reader reader = new InputStreamReader(buckPyProcess.getInputStream(), Charsets.UTF_8);
    buckPyStdoutParser = new BuildFileToJsonParser(reader, isServerMode);
  }

  private ImmutableList<String> buildArgs() throws IOException {
    // Invoking buck.py and read JSON-formatted build rules from its stdout.
    ImmutableList.Builder<String> argBuilder = ImmutableList.builder();

    argBuilder.add(pythonInterpreter);

    // Ask python to unbuffer stdout so that we can coordinate based on the output as it is
    // produced.
    argBuilder.add("-u");

    argBuilder.add(getPathToBuckPy(descriptions).toString());

    if (isServerMode) {
      // Provide BUCK files to parse via buck.py's stdin.
      argBuilder.add("--server");
    }

    if (parseOptions.contains(Option.STRIP_NULL)) {
      argBuilder.add("--strip_none");
    }

    argBuilder.add("--project_root", projectRoot.getAbsolutePath());

    // Add the --include flags.
    for (String include : commonIncludes) {
      argBuilder.add("--include");
      argBuilder.add(include);
    }

    for (Path path : ignorePaths) {
      argBuilder.add("--ignore_path");
      argBuilder.add(path.toString());
    }

    return argBuilder.build();
  }

  /**
   * Create, parse and destroy the parser in one step for an entire project.  This should
   * only be used when the tree must be parsed without a specific target to be built or
   * otherwise operated upon.
   */
  public static List<Map<String, Object>> getAllRulesInProject(
      ProjectBuildFileParserFactory factory,
      Iterable<String> includes,
      Console console)
      throws BuildFileParseException {
    try (ProjectBuildFileParser buildFileParser =
             factory.createParser(includes, EnumSet.of(Option.STRIP_NULL), console)) {
      buildFileParser.setServerMode(false);
      return buildFileParser.getAllRulesInternal(Optional.<Path>absent());
    } catch (IOException e) {
      throw BuildFileParseException.createForGenericBuildFileParseError(e);
    }
  }

  /**
   * Collect all rules from a particular build file.
   *
   * @param buildFile should be an absolute path to a build file. Must have rootPath as its prefix.
   */
  public List<Map<String, Object>> getAllRules(Path buildFile)
      throws BuildFileParseException {
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
      throws BuildFileParseException {
    try {
      return getAllRulesInternal(Optional.of(buildFile));
    } catch (IOException e) {
      throw BuildFileParseException.createForBuildFileParseError(buildFile, e);
    }
  }

  @VisibleForTesting
  protected List<Map<String, Object>> getAllRulesInternal(Optional<Path> buildFile)
      throws IOException {
    ensureNotClosed();
    initIfNeeded();

    // When in server mode, we require a build file.  When not in server mode, we
    // cannot accept a build file.  Pretty stupid, actually.  Consider fixing this.
    Preconditions.checkState(buildFile.isPresent() == isServerMode);

    if (buildFile.isPresent()) {
      buckPyStdinWriter.write(buildFile.get().toString());
      buckPyStdinWriter.newLine();
      buckPyStdinWriter.flush();
    }

    return buckPyStdoutParser.nextRules();
  }

  @Override
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void close() throws BuildFileParseException {
    if (isClosed) {
      return;
    }

    try {
      if (isInitialized) {
        try {
          buckPyStdoutParser.close();
        } catch (IOException e) {
          // This is bad, but we swallow this so we can still close the other objects.
        }

        if (isServerMode) {
          // Allow buck.py to terminate gracefully.
          try {
            buckPyStdinWriter.close();
          } catch (IOException e) {
            // Safe to ignore since we've already flushed everything we wanted
            // to write.
          }
        }

        try {
          int exitCode = buckPyProcess.waitFor();
          if (exitCode != 0) {
            BuildFileParseException.createForUnknownParseError(
                String.format("Parser did not exit cleanly (exit code: %d)", exitCode));
          }
        } catch (InterruptedException e) {
          throw Throwables.propagate(e);
        }

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
      isClosed = true;
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

    // We currently create a temporary buck.py per instance of this class, rather than a single one
    // for the life of this buck invocation. We do this since this is generated in parallel we end
    // up with strange InterruptedExceptions being thrown.
    // TODO(simons): This would be the ideal thing to do.
    //    Path buckDotPy =
    //        projectRoot.toPath().resolve(BuckConstant.BIN_DIR).resolve("generated-buck.py");
    Path buckDotPy = Files.createTempFile("buck", ".py");
    Files.createDirectories(buckDotPy.getParent());

    try (Writer out = Files.newBufferedWriter(buckDotPy, UTF_8)) {
      Path original = Paths.get(PATH_TO_BUCK_PY);
      CharStreams.copy(Files.newBufferedReader(original, UTF_8), out);
      out.write("\n\n");

      // The base path doesn't matter, but should be set.
      ConstructorArgMarshaller inspector = new ConstructorArgMarshaller(projectRoot.toPath());
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
    pathToBuckPy = Optional.of(buckDotPy.normalize());
  }
}
