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

import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.Config;
import com.facebook.buck.io.FakeExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

public class PythonInPlaceBinaryIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();
  public ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "python_inplace_binary", tmp);
    workspace.setUp();
    assertInPlacePackageStyleIsBeingUsed();
  }

  @Test
  public void commandLineArgs() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("run", ":bin", "HELLO WORLD").assertSuccess();
    assertThat(
        result.getStdout(),
        Matchers.containsString("HELLO WORLD"));
  }

  @Test
  public void nativeLibraries() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("run", ":bin-with-native-libs").assertSuccess();
    assertThat(
        result.getStdout(),
        Matchers.containsString("HELLO WORLD"));
  }

  @Test
  public void runFromGenrule() throws IOException {
    workspace.runBuckBuild(":gen").assertSuccess();
  }

  private void assertInPlacePackageStyleIsBeingUsed() throws IOException {
    Config rawConfig = Config.createDefaultConfig(
        tmp.getRootPath(),
        ImmutableMap.<String, ImmutableMap<String, String>>of());

    BuckConfig buckConfig = new BuckConfig(
        rawConfig,
        new ProjectFilesystem(tmp.getRootPath()),
        Platform.detect(),
        ImmutableMap.<String, String>of());

    PythonBuckConfig pythonBuckConfig =
        new PythonBuckConfig(
            buckConfig,
            new FakeExecutableFinder(ImmutableList.<Path>of()));
    assertThat(
        pythonBuckConfig.getPackageStyle(),
        Matchers.equalTo(PythonBuckConfig.PackageStyle.INPLACE));
  }

}
