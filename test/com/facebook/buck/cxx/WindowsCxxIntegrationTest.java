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

package com.facebook.buck.cxx;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.WindowsUtils;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WindowsCxxIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;


  private WindowsUtils windowsUtils = new WindowsUtils();

  @Before
  public void setUp() throws IOException {
    assumeTrue(Platform.detect() == Platform.WINDOWS);
    windowsUtils.checkAssumptions();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "win_x64", tmp);
    workspace.setUp();
    windowsUtils.setUpWorkspace(workspace, "xplat");
  }

  @Test
  public void simpleBinary64() throws IOException {
    ProcessResult runResult = workspace.runBuckCommand("run", "//app:hello#windows-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), Matchers.containsString("The process is 64bits"));
    assertThat(
        runResult.getStdout(), Matchers.not(Matchers.containsString("The process is WOW64")));
  }

  @Test
  public void simpleBinaryWithLib() throws IOException {
    ProcessResult runResult = workspace.runBuckCommand("run", "//app_lib:app_lib#windows-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), Matchers.containsString("BUCK ON WINDOWS"));
  }

  @Test
  public void simpleBinaryIsExecutableByCmd() throws IOException {
    ProcessResult runResult = workspace.runBuckCommand("build", "//app:log");
    runResult.assertSuccess();
    Path outputPath = workspace.resolve("buck-out/gen/app/log/log.txt");
    assertThat(
        workspace.getFileContents(outputPath), Matchers.containsString("The process is 64bits"));
  }


  @Test
  public void simpleBinaryInDevConsole() throws IOException, InterruptedException {
    ProcessResult amd64RunResult =
        workspace.runBuckCommand(getDevConsoleEnv("amd64"), "run", "d//app:hello#windows-x86_64");
    amd64RunResult.assertSuccess();
    assertThat(amd64RunResult.getStdout(), Matchers.containsString("The process is 64bits"));
    assertThat(
        amd64RunResult.getStdout(), Matchers.not(Matchers.containsString("The process is WOW64")));

    ProcessResult x86RunResult =
        workspace.runBuckCommand(getDevConsoleEnv("x86"), "run", "d//app:hello#windows-x86_64");
    x86RunResult.assertSuccess();
    assertThat(x86RunResult.getStdout(), Matchers.containsString("The process is 64bits"));
    assertThat(x86RunResult.getStdout(), Matchers.containsString("The process is WOW64"));
  }

  @Test
  public void librariesInDevConsole() throws IOException, InterruptedException {
    ProcessResult staticLibResult =
        workspace.runBuckCommand(
            getDevConsoleEnv("amd64"), "build", "d//lib:lib#windows-x86_64,static");
    staticLibResult.assertSuccess();

    ProcessResult sharedLibResult =
        workspace.runBuckCommand(
            getDevConsoleEnv("amd64"), "build", "d//lib:lib#windows-x86_64,shared");
    sharedLibResult.assertSuccess();
  }

  @Test
  public void simpleBinaryWithDll() throws IOException {
    ProcessResult libResult =
        workspace.runBuckCommand("build", "//implib:implib#windows-x86_64,shared");
    libResult.assertSuccess();

    ProcessResult appResult =
        workspace.runBuckCommand("build", "//implib_usage:app#windows-x86_64");
    appResult.assertSuccess();

    ProcessResult runResult = workspace.runBuckCommand("run", "//implib_usage:app#windows-x86_64");
    runResult.assertSuccess();

    ProcessResult logResult = workspace.runBuckCommand("build", "//implib_usage:log");
    logResult.assertSuccess();
    Path outputPath = workspace.resolve("buck-out/gen/implib_usage/log/log.txt");
    assertThat(workspace.getFileContents(outputPath), Matchers.containsString("a + (a * b)"));
  }

  private ImmutableMap<String, String> getDevConsoleEnv(String vcvarsallBatArg)
      throws IOException, InterruptedException {
    workspace.writeContentsToPath(
        String.format("\"%s\" %s & set", windowsUtils.getVcvarsallbat(), vcvarsallBatArg),
        "env.bat");
    ProcessExecutor.Result envResult = workspace.runCommand("cmd", "/Q", "/c", "env.bat");
    Optional<String> envOut = envResult.getStdout();
    Assert.assertTrue(envOut.isPresent());
    String envString = envOut.get();
    String[] envStrings = envString.split("\\r?\\n");
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    for (String s : envStrings) {
      int sep = s.indexOf('=');
      String key = s.substring(0, sep);
      if ("PATH".equalsIgnoreCase(key)) {
        key = "PATH";
      }
      String val = s.substring(sep + 1, s.length());
      builder.put(key, val);
    }
    return builder.build();
  }
}
