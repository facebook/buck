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

package com.facebook.buck.features.dotnet;

import static com.facebook.buck.features.dotnet.DotnetAssumptions.assumeCscIsAvailable;
import static org.junit.Assert.fail;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class CsharpLibraryIntegrationTest {

  // https://msdn.microsoft.com/en-us/library/1700bbwd.aspx
  private static final String vsvars32bat =
      "C:\\Program Files (x86)\\Microsoft Visual Studio 14.0\\Common7\\Tools\\vsvars32.bat";

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ImmutableMap<String, String> env;

  @Before
  public void setUp() throws IOException, InterruptedException {
    env = getEnv();
    assumeCscIsAvailable(env);
  }

  @Test
  public void shouldCompileLibraryWithSystemProvidedDeps() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "csc-tests", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(env, "build", "//src:simple");
    result.assertSuccess();
  }

  @Test
  public void shouldCompileLibraryWithAPrebuiltDependency() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "csc-tests", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(env, "build", "//src:prebuilt");
    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToEmbedResourcesIntoTheBuiltDll() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "csc-tests", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(env, "build", "//src:embed");
    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToDependOnAnotherCsharpLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "csc-tests", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(env, "build", "//src:dependent");
    result.assertSuccess();
  }

  @Test
  @Ignore
  public void shouldBeAbleToAddTheSameResourceToADllTwice() {
    fail("Implement me, please!");
  }

  private ImmutableMap<String, String> getEnv() throws IOException, InterruptedException {
    if (Platform.detect() == Platform.WINDOWS && Files.exists(Paths.get(vsvars32bat))) {
      String vsvar32BatEsc = vsvars32bat.replace(" ", "^ ").replace("(", "^(");
      ProcessExecutorParams params =
          ProcessExecutorParams.ofCommand("cmd", "/c", vsvar32BatEsc + " && set");
      ProcessExecutor executor = new DefaultProcessExecutor(new TestConsole());
      ProcessExecutor.Result envResult = executor.launchAndExecute(params);
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
        String val = s.substring(sep + 1);
        builder.put(key, val);
      }
      return builder.build();
    } else {
      return ImmutableMap.copyOf(System.getenv());
    }
  }
}
