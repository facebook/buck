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
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuckPyFunction;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.Description;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.PackagedResource;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;

import java.io.IOException;
import java.io.Writer;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Represents a serialized copy of the buck python program used to read BUCK files.
 * <p/>
 * Layout of the directory:
 * <pre>
 *  root/
 *    __main__.py
 *    buck.py
 * </pre>
 */
class BuckPythonProgram implements AutoCloseable {
  /**
   * Path to the buck.py script that is used to evaluate a build file.
   */
  private static final String BUCK_PY_RESOURCE = "com/facebook/buck/json/buck.py";
  private static final String PATHLIB_RESOURCE = "pathlib-archive.zip";
  private static final String WATCHMAN_RESOURCE = "pywatchman-archive.zip";

  private static final Logger LOG = Logger.get(BuckPythonProgram.class);

  private final Path rootDirectory;

  /**
   * Create a new instance by layout the files in a temporary directory.
   */
  public static BuckPythonProgram newInstance(
      ProjectFilesystem filesystem,
      ConstructorArgMarshaller marshaller,
      ImmutableSet<Description<?>> descriptions) throws IOException {

    Path rootDirectory = Files.createTempDirectory("buck_python_program");

    LOG.debug("Creating temporary buck.py instance at %s.", rootDirectory);

    try (Writer out = Files.newBufferedWriter(rootDirectory.resolve("buck.py"), UTF_8)) {
      URL resource = Resources.getResource(BUCK_PY_RESOURCE);
      Resources.asCharSource(resource, UTF_8).copyTo(out);
      out.write("\n\n");

      BuckPyFunction function = new BuckPyFunction(marshaller);
      for (Description<?> description : descriptions) {
        out.write(function.toPythonFunction(
            description.getBuildRuleType(),
            description.createUnpopulatedConstructorArg()));
        out.write('\n');
      }
    }

    Path pathlibDir = new PackagedResource(
        filesystem,
        BuckPythonProgram.class,
        PATHLIB_RESOURCE,
        Optional.of("pathlib"))
        .get()
        .toAbsolutePath();

    Path watchmanDir = new PackagedResource(
        filesystem,
        BuckPythonProgram.class,
        WATCHMAN_RESOURCE,
        Optional.of("pywatchman"))
        .get()
        .toAbsolutePath();

    try (Writer out = Files.newBufferedWriter(rootDirectory.resolve("__main__.py"), UTF_8)) {
      out.write(Joiner.on("\n").join(
          "import sys",
          "sys.path.insert(0, \"" +
              Escaper.escapeAsBashString(MorePaths.pathWithUnixSeparators(pathlibDir)) + "\")",
          "sys.path.insert(0, \"" +
              Escaper.escapeAsBashString(MorePaths.pathWithUnixSeparators(watchmanDir)) + "\")",
          "import buck",
          "if __name__ == '__main__':",
          "    try:",
          "        buck.main()",
          "    except KeyboardInterrupt:",
          "        print >> sys.stderr, 'Killed by User'",
          ""));
    }

    LOG.debug("Created temporary buck.py instance at %s.", rootDirectory);
    return new BuckPythonProgram(rootDirectory);
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
