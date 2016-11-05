/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuckPyFunction;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.Description;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Represents a serialized copy of the buck python program used to read BUCK files.
 * <p/>
 * Layout of the directory:
 * <pre>
 *  root/
 *    __main__.py
 *    generated_rules.py
 *    python_bundle.zip
 * </pre>
 */
class BuckPythonProgram implements AutoCloseable {
  /**
   * Path to the resource containing the buck python package.
   */
  private static final String BUCK_PY_RESOURCE = "python_bundle.zip";

  /**
   * Location of buck python package directory on disk. If set, used in favor of built-in resource.
   *
   * Used in intellij tests as it doesn't permit easy compile-time creation of the resource zip
   * file.
   */
  private static final Optional<Path> BUCK_PY_PACKAGE_OVERRIDE =
      Optional.ofNullable(System.getProperty("buck.override_python_package_path", null))
          .map(Paths::get);


  private static final Path PATH_TO_PATHLIB_PY =
      Paths.get(System.getProperty("buck.path_to_pathlib_py", "third-party/py/pathlib/pathlib.py"));

  private static final Path PATH_TO_PYWATCHMAN =
      Paths.get(System.getProperty("buck.path_to_pywatchman", "third-party/py/pywatchman"));

  private static final Logger LOG = Logger.get(BuckPythonProgram.class);

  private final Path rootDirectory;

  /**
   * Create a new instance by layout the files in a temporary directory.
   */
  public static BuckPythonProgram newInstance(
      ConstructorArgMarshaller marshaller,
      ImmutableSet<Description<?>> descriptions) throws IOException {

    Path rootDir = Files.createTempDirectory("buck_python_program");
    Path pythonPath;

    LOG.debug("Creating temporary buck.py instance at %s.", rootDir);

    if (BUCK_PY_PACKAGE_OVERRIDE.isPresent()) {
      // Use python source file location directly.
      pythonPath = BUCK_PY_PACKAGE_OVERRIDE.get();
    } else {
      // Use zip file built in as a resource.
      URL resource = Resources.getResource(BuckPythonProgram.class, BUCK_PY_RESOURCE);
      pythonPath = rootDir.resolve("python_bundle.zip");
      try (InputStream stream = Resources.asByteSource(resource).openStream()) {
        Files.copy(stream, pythonPath, StandardCopyOption.REPLACE_EXISTING);
      }
    }

    try (
        Writer out =
            Files.newBufferedWriter(
                rootDir.resolve("generated_rules.py"),
                UTF_8)) {
      out.write("from buck_parser.buck import *\n\n");
      BuckPyFunction function = new BuckPyFunction(marshaller);
      for (Description<?> description : descriptions) {
        out.write(function.toPythonFunction(
            description.getBuildRuleType(),
            description.createUnpopulatedConstructorArg()));
        out.write('\n');
      }
    }

    String pathlibDir = PATH_TO_PATHLIB_PY.getParent().toString();
    String watchmanDir = PATH_TO_PYWATCHMAN.toString();
    try (Writer out = Files.newBufferedWriter(rootDir.resolve("__main__.py"), UTF_8)) {
      out.write(Joiner.on("\n").join(
          "from __future__ import absolute_import",
          "import sys",
          "sys.path.insert(0, \"" +
              Escaper.escapeAsBashString(MorePaths.pathWithUnixSeparators(pathlibDir)) + "\")",
          "sys.path.insert(0, \"" +
              Escaper.escapeAsBashString(MorePaths.pathWithUnixSeparators(watchmanDir)) + "\")",
          "sys.path.insert(0, \"" +
              Escaper.escapeAsBashString(MorePaths.pathWithUnixSeparators(pythonPath)) + "\")",
          "if __name__ == '__main__':",
          "    try:",
          "        from buck_parser import buck",
          "        buck.main()",
          "    except KeyboardInterrupt:",
          "        print >> sys.stderr, 'Killed by User'",
          ""));
    }

    LOG.debug("Created temporary buck.py instance at %s.", rootDir);
    return new BuckPythonProgram(rootDir);
  }

  public Path getExecutablePath() {
    return this.rootDirectory.resolve("__main__.py");
  }

  @Override
  public void close() throws IOException {
    MoreFiles.deleteRecursively(this.rootDirectory);
  }

  private BuckPythonProgram(Path rootDirectory) {
    this.rootDirectory = rootDirectory;
  }
}
