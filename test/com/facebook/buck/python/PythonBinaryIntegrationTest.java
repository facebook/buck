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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.Config;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.DefaultCxxPlatforms;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.io.FakeExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.martiansoftware.nailgun.NGContext;

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
import java.util.Map;

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
        equalTo(0));
  }

  @Test
  public void commandLineArgs() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("run", ":bin", "HELLO WORLD").assertSuccess();
    assertThat(
        result.getStdout(),
        containsString("HELLO WORLD"));
  }

  @Test
  public void nativeLibraries() throws IOException {
    assumeThat(packageStyle, equalTo(PythonBuckConfig.PackageStyle.INPLACE));
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("run", ":bin-with-native-libs").assertSuccess();
    assertThat(
        result.getStdout(),
        containsString("HELLO WORLD"));
  }

  @Test
  public void runFromGenrule() throws IOException {
    workspace.runBuckBuild(":gen").assertSuccess();
  }

  @Test
  public void arg0IsPreserved() throws IOException {
    workspace.writeContentsToPath("import sys; print(sys.argv[0])", "main.py");
    String arg0 = workspace.runBuckCommand("run", ":bin").assertSuccess().getStdout().trim();
    String output = workspace.runBuckCommand("targets", "--show-output", "//:bin")
        .assertSuccess()
        .getStdout()
        .trim();
    assertThat(
        arg0,
        endsWith(Splitter.on(" ").splitToList(output).get(1)));
  }

  @Test
  public void nativeLibsEnvVarIsPreserved() throws IOException {
    String nativeLibsEnvVarName =
        DefaultCxxPlatforms
            .build(new CxxBuckConfig(new FakeBuckConfig()))
            .getLd()
            .searchPathEnvVar();
    String originalNativeLibsEnvVar = "something";
    workspace.writeContentsToPath(
        String.format("import os; print(os.environ.get('%s'))", nativeLibsEnvVarName),
        "main_with_native_libs.py");
    Map<String, String> env = Maps.newHashMap(System.getenv());
    env.remove(nativeLibsEnvVarName);

    // Pre-set library path.
    String nativeLibsEnvVar =
        workspace.runBuckCommandWithEnvironmentAndContext(
            workspace.getPath(""),
            Optional.<NGContext>absent(),
            Optional.<BuckEventListener>absent(),
            Optional.of(
                ImmutableMap.<String, String>builder()
                    .putAll(env)
                    .put(nativeLibsEnvVarName, originalNativeLibsEnvVar)
                    .build()),
            "run",
            ":bin-with-native-libs")
            .assertSuccess()
            .getStdout()
            .trim();
    assertThat(
        nativeLibsEnvVar,
        equalTo(originalNativeLibsEnvVar));

    // Empty library path.
    nativeLibsEnvVar =
        workspace.runBuckCommandWithEnvironmentAndContext(
            workspace.getPath(""),
            Optional.<NGContext>absent(),
            Optional.<BuckEventListener>absent(),
            Optional.of(ImmutableMap.copyOf(env)),
            "run",
            ":bin-with-native-libs")
            .assertSuccess()
            .getStdout()
            .trim();
    assertThat(
        nativeLibsEnvVar,
        equalTo("None"));
  }

  @Test
  public void sysPathDoesNotIncludeWorkingDir() throws IOException {
    workspace.writeContentsToPath("import sys; print(sys.path[0])", "main.py");
    String sysPath0 = workspace.runBuckCommand("run", ":bin").assertSuccess().getStdout().trim();
    assertThat(
        sysPath0,
        not(equalTo("")));
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
        equalTo(style));
  }

}
