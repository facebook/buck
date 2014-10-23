/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.java;

import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class ExternalJavacEscaperTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return Arrays.asList(
        new Object[][]{
            {"Poundsign", "pound#sign"},
            {"Whitespace", "space present"},
            {"SingleQuote", "quote'"},
            {"DoubleQuote", "double_quote\""}
        });
  }

  @Parameterized.Parameter
  public String name;

  @Parameterized.Parameter(value = 1)
  public String badDir;

  @Test
  public void testSpecialCharsInSourcePath() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "external_javac", tmp);
    workspace.setUp();

    Path javac = Paths.get("/usr/bin/javac");
    assumeTrue(Files.exists(javac));
    workspace.replaceFileContents(".buckconfig", "@JAVAC@", javac.toString());

    workspace.move("java", badDir);
    workspace.runBuckCommand("clean").assertSuccess();
    workspace.runBuckCommand(
        "build",
        String.format("//%s/com/example:example", badDir))
        .assertSuccess();
  }

}
