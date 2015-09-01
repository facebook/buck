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
import static org.junit.Assume.assumeThat;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.Config;
import com.facebook.buck.io.FakeExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

@RunWith(Parameterized.class)
public class PythonBinaryIntegrationTest {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return ImmutableList.of(
        new Object[]{PythonBuckConfig.PackageStyle.INPLACE},
        new Object[]{PythonBuckConfig.PackageStyle.STANDALONE});
  }

  @Parameterized.Parameter
  public PythonBuckConfig.PackageStyle packageStyle;

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  public ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "python_binary", tmp);
    workspace.setUp();
    workspace.writeContentsToPath(
        "[python]\n" +
        "  package_style = " + packageStyle.toString().toLowerCase() + "\n",
        ".buckconfig");
    assertPackageStyle(packageStyle);
  }

  @Test
  public void nonComponentDepsArePreserved() throws IOException {
    workspace.runBuckBuild("//:bin-with-extra-dep").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:extra");
  }

  @Test
  public void executionThroughSymlink() throws IOException, InterruptedException {
    assumeThat(Platform.detect(), Matchers.oneOf(Platform.MACOS, Platform.LINUX));
    workspace.runBuckBuild("//:bin").assertSuccess();
    String output = workspace.runBuckCommand("targets", "--show-output", "//:bin")
        .assertSuccess()
        .getStdout()
        .trim();
    Path link = workspace.getPath("link");
    Files.createSymbolicLink(
        link,
        workspace.getPath(Splitter.on(" ").splitToList(output).get(1)).toAbsolutePath());
    ProcessExecutor.Result result = workspace.runCommand(link.toString());
    assertThat(
        result.getStdout().or("") + result.getStderr().or(""),
        result.getExitCode(),
        Matchers.equalTo(0));
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
    assumeThat(packageStyle, Matchers.equalTo(PythonBuckConfig.PackageStyle.INPLACE));
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

  private void assertPackageStyle(PythonBuckConfig.PackageStyle style) throws IOException {
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
        Matchers.equalTo(style));
  }

}
