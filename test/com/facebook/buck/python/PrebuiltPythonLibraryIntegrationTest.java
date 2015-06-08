/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.python;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class PrebuiltPythonLibraryIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder().doNotDeleteOnExit();
  public ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException, InterruptedException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "prebuilt_package", tmp);
    workspace.setUp();


    // EGGs are versioned to the version of Python they were built it, but the EGG for this test
    // doesn't actually matter.
    String version = new PythonBuckConfig(new FakeBuckConfig(), new ExecutableFinder())
        .getPythonEnvironment(
            new ProcessExecutor(
                new Console(
                    Verbosity.SILENT,
                    new CapturingPrintStream(),
                    new CapturingPrintStream(),
                    Ansi.withoutTty())))
        .getPythonVersion()
        .getVersionString()
        .substring("Python ".length());
    if (!version.equals("2.6")) {
      workspace.move("dist/package-0.1-py2.6.egg", "dist/package-0.1-py" + version + ".egg");
    }
  }

  @Test
  public void testRunPexWithEggDependency() throws IOException {
    ProcessResult results = workspace.runBuckCommand("run", "//:main");
    results.assertSuccess();
  }

}
