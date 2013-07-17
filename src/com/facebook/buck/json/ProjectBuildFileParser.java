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

import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.InputStreamConsumer;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Closer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

/**
 * Delegates to buck.py for parsing of buck build files.  Constructed on demand for the
 * parsing phase and must be closed afterward to free up resources.
 */
public class ProjectBuildFileParser implements AutoCloseable {

  /** Path to the buck.py script that is used to evaluate a build file. */
  private static final String PATH_TO_BUCK_PY = System.getProperty("buck.path_to_buck_py",
      "src/com/facebook/buck/parser/buck.py");

  private static final String python = Joiner.on(System.getProperty("line.separator")).join(
      "import sys",
      "import os.path",
      "sys.path.append(os.path.dirname(\"%s\"))",
      "import buck",
      "sys.argv=[\"%s\"]",
      "buck.main()");

  private ExecutorService executor;
  private ScriptEngine engine;

  private BuildFileToJsonParser buckPyStdoutParser;
  private BufferedWriter buckPyStdinWriter;

  private final File projectRoot;
  private final ImmutableSet<String> ignorePaths;
  private final ImmutableList<String> commonIncludes;

  private boolean isServerMode;

  private boolean isInitialized;
  private boolean isClosed;

  public ProjectBuildFileParser(
      ProjectFilesystem projectFilesystem,
      ImmutableList<String> commonIncludes) {
    this.projectRoot = projectFilesystem.getProjectRoot();
    this.ignorePaths = projectFilesystem.getIgnorePaths();
    this.commonIncludes = Preconditions.checkNotNull(commonIncludes);

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
   * Initialize the parser.  This starts buck.py, waiting on stdin for directives.
   * <p>
   * It is safe to invoke this method multiple times (subsequent invocations will be
   * ignored).
   */
  private void init() throws IOException {
    executor = Executors.newFixedThreadPool(2);
    engine = new ScriptEngineManager().getEngineByName("python");

    // Run buck.py in a dedicated thread.
    final ImmutableList<String> args = buildArgs();

    final PipedWriter inputWriter = new PipedWriter();
    final PipedReader outputReader = new PipedReader();
    final PipedReader stderrReader = new PipedReader();
    BuildFileRunner runner = new BuildFileRunner(args, inputWriter, outputReader,
        stderrReader);
    executor.execute(runner);
    executor.execute(new InputStreamConsumer(stderrReader,
        System.err,
        true /* shouldRedirect */,
        new Ansi()));

    buckPyStdinWriter = new BufferedWriter(inputWriter);
    buckPyStdoutParser = new BuildFileToJsonParser(outputReader);
  }

  private class BuildFileRunner implements Runnable {

    private final ImmutableList<String> args;
    private PipedReader inputReader;
    private PipedWriter outputWriter;
    private PipedWriter stderrWriter;

    public BuildFileRunner(
        ImmutableList<String> args,
        PipedWriter inputWriter,
        PipedReader outputReader,
        PipedReader stderrReader)
        throws IOException {
      this.args = args;
      this.inputReader = new PipedReader();
      inputReader.connect(inputWriter);
      this.outputWriter = new PipedWriter();
      outputWriter.connect(outputReader);
      this.stderrWriter = new PipedWriter();
      stderrWriter.connect(stderrReader);
    }

    @Override
    public void run() {
      Closer closer = Closer.create();
      try {
        closer.register(inputReader);
        closer.register(outputWriter);
        closer.register(stderrWriter);

        // TODO(user): call buck.py directly rather than emulating the old command line interface?
        // TODO(user): escape args? (they are currently file names, which shouldn't contain quotes)
        engine.getContext().setReader(inputReader);
        engine.getContext().setWriter(outputWriter);
        engine.getContext().setErrorWriter(stderrWriter);
        engine.eval(String.format(python, PATH_TO_BUCK_PY, Joiner.on("\",\"").join(args)));
      } catch (ScriptException e) {
        // Print human readable python trace if available, default to normal exception if not.
        // Path to build file is included in python stack trace.
        final Throwable cause = e.getCause();
        if (cause != null) {
          throw new HumanReadableException("Unable to parse build file: %s%s,",
              System.getProperty("line.separator"),
              cause);
        } else {
          throw Throwables.propagate(e);
        }
      } finally {
        try {
          closer.close();
        } catch (IOException e) {
          throw Throwables.propagate(e);
        }
      }
    }
  }

  private ImmutableList<String> buildArgs() {
    // Invoking buck.py and read JSON-formatted build rules from its stdout.
    ImmutableList.Builder<String> argBuilder = ImmutableList.builder();
    argBuilder.add("buck.py");

    if (isServerMode) {
      // Provide BUCK files to parse via buck.py's stdin.
      argBuilder.add("--server");
    }

    argBuilder.add("--project_root", projectRoot.getAbsolutePath());

    // Add the --include flags.
    for (String include : commonIncludes) {
      argBuilder.add("--include");
      argBuilder.add(include);
    }

    for (String path : ignorePaths) {
      argBuilder.add("--ignore_path");
      argBuilder.add(path);
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
      Iterable<String> includes)
      throws IOException {
    try (ProjectBuildFileParser buildFileParser = factory.createParser(includes)) {
      buildFileParser.setServerMode(false);
      return buildFileParser.getAllRulesInternal(Optional.<String>absent());
    }
  }

  /**
   * Collect all rules from a particular build file.
   *
   * @param buildFile should be an absolute path to a build file. Must have rootPath as its prefix.
   */
  public List<Map<String, Object>> getAllRules(String buildFile) throws IOException {
    return getAllRulesInternal(Optional.of(buildFile));
  }

  @VisibleForTesting
  protected List<Map<String, Object>> getAllRulesInternal(Optional<String> buildFile)
      throws IOException {
    ensureNotClosed();
    initIfNeeded();

    // When in server mode, we require a build file.  When not in server mode, we
    // cannot accept a build file.  Pretty stupid, actually.  Consider fixing this.
    Preconditions.checkState(buildFile.isPresent() == isServerMode);

    if (buildFile.isPresent()) {
      buckPyStdinWriter.write(buildFile.get());
      buckPyStdinWriter.newLine();
      buckPyStdinWriter.flush();
    }

    return buckPyStdoutParser.nextRules();
  }

  @Override
  public void close() throws IOException {
    if (isClosed) {
      return;
    }

    // Explicitly shut down the thread pool but allow the engine to finalize normally.  This
    // is unlikely to actually free resources at this time but in a subsequent diff we can enforce
    // that by design by forking a separate jython process for the entire parse phase.
    try {
      if (isInitialized) {
        executor.shutdown();

        // Allow buck.py to terminate gracefully...
        if (isServerMode) {
          buckPyStdinWriter.close();
        }
      }
    } finally {
      isClosed = true;
    }
  }
}
