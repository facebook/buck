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

import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class TestBinWriterTest {
  @Rule public TemporaryPaths temporaryPaths = new TemporaryPaths();

  @Test
  public void writesCorrectArgEchoer() throws IOException {
    ImmutableList.Builder<String> expectedOutputBuilder = ImmutableList.builder();
    expectedOutputBuilder.add("#!/usr/bin/env python");
    expectedOutputBuilder.add("from __future__ import print_function");
    expectedOutputBuilder.add("import sys");
    expectedOutputBuilder.add("print('''line1");
    expectedOutputBuilder.add("  line2");
    expectedOutputBuilder.add("line3''')");
    expectedOutputBuilder.add("sys.exit(1)");
    List<String> expectedOutput = expectedOutputBuilder.build();

    Path path = temporaryPaths.newFile("test.out");
    TestBinWriter writer = new TestBinWriter(path);
    String[] lines = new String[3];
    lines[0] = "line1";
    lines[1] = "  line2";
    lines[2] = "line3";
    writer.writeLineEchoer(lines, 1);

    Assert.assertEquals(expectedOutput, Files.readAllLines(path));
  }

  @Test
  public void writesCorrectLineEchoer() throws IOException {
    ImmutableList.Builder<String> expectedOutputBuilder = ImmutableList.builder();
    expectedOutputBuilder.add("#!/usr/bin/env python");
    expectedOutputBuilder.add("from __future__ import print_function");
    expectedOutputBuilder.add("import os");
    expectedOutputBuilder.add("import sys");
    expectedOutputBuilder.add("print(os.getcwd())");
    expectedOutputBuilder.add("for arg in sys.argv:");
    expectedOutputBuilder.add("    print(arg)");
    expectedOutputBuilder.add("if \"PYTHONHASHSEED\" in os.environ:");
    expectedOutputBuilder.add("    print(\"Random hashes configured\")");
    expectedOutputBuilder.add("for arg in sys.argv:");
    expectedOutputBuilder.add("    if arg.startswith(\"@\"):");
    expectedOutputBuilder.add("        print(\"Reading arguments from \" + arg)");
    expectedOutputBuilder.add("        for line in open(arg.lstrip(\"@\")):");
    expectedOutputBuilder.add("            print(line.rstrip())");
    expectedOutputBuilder.add("sys.exit(1)");
    List<String> expectedOutput = expectedOutputBuilder.build();

    Path path = temporaryPaths.newFile("test.out");
    TestBinWriter writer = new TestBinWriter(path);
    writer.writeArgEchoer(1);

    Assert.assertEquals(expectedOutput, Files.readAllLines(path));
  }
}
