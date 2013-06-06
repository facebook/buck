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
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
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
 * A ProjectBuildFileParser finds all build files within a project root and executes
 * those build files to generate the build rules they define.
 */
public class ProjectBuildFileParser {

  /** Path to the buck.py script that is used to evaluate a build file. */
  private static final String PATH_TO_BUCK_PY = System.getProperty("buck.path_to_buck_py",
      "src/com/facebook/buck/parser/buck.py");

  private static final ExecutorService executor = Executors.newSingleThreadExecutor();
  private static final ScriptEngine engine = new ScriptEngineManager().getEngineByName("python");
  private static final String python = Joiner.on(System.getProperty("line.separator")).join(
      "import sys",
      "import os.path",
      "sys.path.append(os.path.dirname(\"%s\"))",
      "import buck",
      "sys.argv=[\"%s\"]",
      "buck.main()");

  public ProjectBuildFileParser() {
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
    return argBuilder.build();
  }

  /**
   * @param rootPath Absolute path to the root of the project. buck.py uses this to determine the
   *     base path of the targets in the build file that it is parsing.
   */
  public List<Map<String, Object>> getAllRulesInProject(
      File rootPath, Iterable<String> includes)
      throws IOException {
    return getAllRules(rootPath.getAbsolutePath(), Optional.<String>absent(), includes);
  }

  /**
   * @param rootPath Absolute path to the root of the project. buck.py uses this to determine the
   *     base path of the targets in the build file that it is parsing.
   * @param buildFile should be an absolute path to a build file. Must have rootPath as its prefix.
   *     If absent, all build files under rootPath will be parsed.
   */
  public List<Map<String, Object>> getAllRules(
      String rootPath,
      Optional<String> buildFile,
      Iterable<String> includes) throws IOException {

    // Run the build file in a background thread.
    final ImmutableList<String> args = buildArgs(rootPath, buildFile, includes);
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
}
