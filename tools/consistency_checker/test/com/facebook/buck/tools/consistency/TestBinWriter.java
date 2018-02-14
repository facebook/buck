/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.tools.consistency;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Writes out a binary that can be executed and will return various things to stdout */
public class TestBinWriter {
  private final Path binPath;

  /**
   * Creates an instance of {@link TestBinWriter}
   *
   * @param binPath The path that the binary should be written to
   */
  public TestBinWriter(Path binPath) {
    this.binPath = binPath;
  }

  /**
   * Writes out a python binary that will print out the PWD, all of the arguments (one per line),
   * and if PYTHONHASHSEED is set in the environment, it will print "Random hashes configured"
   *
   * @param returnCode The return code that the binary should exit with
   * @throws IOException The file couldn't be written out, or couldn't have permissions set
   */
  public void writeArgEchoer(int returnCode) throws IOException {
    try (FileWriter output = new FileWriter(binPath.toAbsolutePath().toString())) {
      output.write("#!/usr/bin/env python");
      output.write(System.lineSeparator());
      output.write("from __future__ import print_function");
      output.write(System.lineSeparator());
      output.write("import os");
      output.write(System.lineSeparator());
      output.write("import sys");
      output.write(System.lineSeparator());
      output.write("print(os.getcwd())");
      output.write(System.lineSeparator());
      output.write("for arg in sys.argv:");
      output.write(System.lineSeparator());
      output.write("    print(arg)");
      output.write(System.lineSeparator());
      output.write("if \"PYTHONHASHSEED\" in os.environ:");
      output.write(System.lineSeparator());
      output.write("    print(\"Random hashes configured\")");
      output.write(System.lineSeparator());
      output.write("for arg in sys.argv:");
      output.write(System.lineSeparator());
      output.write("    if arg.startswith(\"@\"):");
      output.write(System.lineSeparator());
      output.write("        print(\"Reading arguments from \" + arg)");
      output.write(System.lineSeparator());
      output.write("        for line in open(arg.lstrip(\"@\")):");
      output.write(System.lineSeparator());
      output.write("            print(line.rstrip())");
      output.write(System.lineSeparator());
      output.write("sys.exit(" + Integer.toString(returnCode) + ")");
      output.write(System.lineSeparator());
    }
    setPermissions();
  }

  private void setPermissions() throws IOException {
    // Used instead of Files.setPosixFilePermissions for windows support
    File binFile = new File(binPath.toAbsolutePath().toString());
    if (!binFile.setExecutable(true, true)) {
      throw new IOException(String.format("Could not set executable flag on %s", binPath));
    }
    if (!binFile.setWritable(true, true)) {
      throw new IOException(String.format("Could not set writeable flag on %s", binPath));
    }
    if (!binFile.setReadable(true, true)) {
      throw new IOException(String.format("Could not set readable flag on %s", binPath));
    }
  }

  /**
   * Writes out a python binary that will print {@code lines} to stdout, and return with a given
   * return code
   *
   * @param lines The lines to print out
   * @param returnCode The return code that the binary should exit with
   * @throws IOException The file couldn't be written out, or couldn't have permissions set
   */
  public void writeLineEchoer(String[] lines, int returnCode) throws IOException {
    try (FileWriter output = new FileWriter(binPath.toAbsolutePath().toString())) {
      output.write("#!/usr/bin/env python");
      output.write(System.lineSeparator());
      output.write("from __future__ import print_function");
      output.write(System.lineSeparator());
      output.write("import sys");
      output.write(System.lineSeparator());
      output.write("print('''");
      output.write(
          Arrays.stream(lines)
              .map(line -> line.replace("'''", "\\'\\'\\'"))
              .collect(Collectors.joining(System.lineSeparator())));
      output.write("''')");
      output.write(System.lineSeparator());
      output.write("sys.exit(" + Integer.toString(returnCode) + ")");
    }
    setPermissions();
  }
}
