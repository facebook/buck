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

import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.Closer;

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

  private ExecutorService executor;
  private ScriptEngine engine;

  private static final String python = Joiner.on(System.getProperty("line.separator")).join(
      "import sys",
      "import os.path",
      "sys.path.append(os.path.dirname(\"%s\"))",
      "import buck",
      "sys.argv=[\"%s\"]",
      "buck.main()");

  private final File projectRoot;
  private final ImmutableSet<String> ignorePaths;
  private final ImmutableList<String> commonIncludes;

  private boolean isInitialized;
  private boolean isClosed;

  public ProjectBuildFileParser(
      ProjectFilesystem projectFilesystem,
      ImmutableList<String> commonIncludes) {
    this.projectRoot = projectFilesystem.getProjectRoot();
    this.ignorePaths = projectFilesystem.getIgnorePaths();
    this.commonIncludes = Preconditions.checkNotNull(commonIncludes);
  }

  private void ensureNotClosed() {
    Preconditions.checkState(!isClosed);
  }

  /**
   * Initialization on demand moves around the performance impact of creating the Jython
   * interpreter to when parsing actually begins.  This makes it easier to attribute this time
   * to the actual parse phase.
   */
  private void initIfNeeded() {
    ensureNotClosed();
    if (!isInitialized) {
      executor = Executors.newSingleThreadExecutor();
      engine = new ScriptEngineManager().getEngineByName("python");
      isInitialized = true;
    }
  }

  private class BuildFileRunner implements Runnable {

    private final ImmutableList<String> args;
    private PipedWriter outputWriter;

    public BuildFileRunner(ImmutableList<String> args, PipedReader outputReader) throws IOException {
      this.args = args;
      this.outputWriter = new PipedWriter();
      outputWriter.connect(outputReader);
    }

    @Override
    public void run() {
      Closer closer = Closer.create();
      try {
        closer.register(outputWriter);

        // TODO(user): call buck.py directly rather than emulating the old command line interface?
        // TODO(user): escape args? (they are currently file names, which shouldn't contain quotes)
        engine.getContext().setWriter(outputWriter);
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

  /**
   * @param rootPath The project root used to find all build files.
   * @param buildFile The build file to parse, defaults to all build files in project.
   * @param includes The files to import before running the build file(s).
   * @return Arguments to pass to buck.py
   */
  private ImmutableList<String> buildArgs(
      String rootPath, Optional<String> buildFile, Iterable<String> includes) {
    // Create a process to run buck.py and read its stdout.
    ImmutableList.Builder<String> argBuilder = ImmutableList.builder();
    argBuilder.add("buck.py", "--project_root", rootPath);

    // Add the --include flags.
    for (String include : includes) {
      argBuilder.add("--include");
      argBuilder.add(include);
    }

    // Specify the build file, if present.
    if (buildFile.isPresent()) {
      argBuilder.add(buildFile.get());
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

    // Run the build file in a background thread.
    final ImmutableList<String> args = buildArgs(projectRoot.getAbsolutePath(),
        buildFile,
        commonIncludes);
    final PipedReader outputReader = new PipedReader();
    BuildFileRunner runner = new BuildFileRunner(args, outputReader);
    executor.execute(runner);

    // Stream build rules from python.
    BuildFileToJsonParser parser = new BuildFileToJsonParser(outputReader);
    List<Map<String, Object>> rules = Lists.newArrayList();
    Map<String, Object> value;
    while ((value = parser.next()) != null) {
      rules.add(value);
    }

    return rules;
  }

  @Override
  public void close() {
    if (isClosed) {
      return;
    }

    // Explicitly shut down the thread pool but allow the engine to finalize normally.  This
    // is unlikely to actually free resources at this time but in a subsequent diff we can enforce
    // that by design by forking a separate jython process for the entire parse phase.
    try {
      if (isInitialized) {
        executor.shutdown();
      }
    } finally {
      isClosed = true;
    }
  }
}
