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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.testutil.ParameterizedTests;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Optional;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CsharpLibraryIntegrationTest {

  private static final String CSC_DIR =
      "C:/tools/toolchains/vs2017_15.5/BuildTools/MSBuild/15.0/Bin/Roslyn";
  private static final String CSC_EXE = String.format("%s/csc.exe", CSC_DIR);

  private ProjectWorkspace workspace;
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ImmutableMap<String, String> env;

  @Parameterized.Parameters(name = "configure_csc={0}")
  public static Collection<Object[]> data() {
    return ParameterizedTests.getPermutations(ImmutableList.of(false, true));
  }

  @Parameterized.Parameter(value = 0)
  public boolean configureCsc;

  @Before
  public void setUp() throws IOException, InterruptedException {
    checkAssumptions();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "csc-tests", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    if (configureCsc) {
      TestDataHelper.overrideBuckconfig(
          workspace, ImmutableMap.of("dotnet", ImmutableMap.of("csc", CSC_EXE)));
    }
    env = getEnv();
  }

  @Test
  public void shouldCompileLibraryWithSystemProvidedDeps() throws IOException {
    workspace.runBuckCommand(env, "build", "//src:simple").assertSuccess();
    assertTrue(Files.exists(workspace.resolve("buck-out/gen/src/simple/simple.dll")));
    workspace.runBuckCommand(env, "clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand(env, "build", "//src:simple").assertSuccess();
    workspace.getBuildLog().assertTargetWasFetchedFromCache("//src:simple");
    assertTrue(Files.exists(workspace.resolve("buck-out/gen/src/simple/simple.dll")));
  }

  @Test
  public void shouldCompileLibraryWithAPrebuiltDependency() throws IOException {
    workspace.runBuckCommand(env, "build", "//src:prebuilt").assertSuccess();
    assertTrue(Files.exists(workspace.resolve("buck-out/gen/src/prebuilt/prebuilt.dll")));
    workspace.runBuckCommand(env, "clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand(env, "build", "//src:prebuilt").assertSuccess();
    workspace.getBuildLog().assertTargetWasFetchedFromCache("//src:prebuilt");
    assertTrue(Files.exists(workspace.resolve("buck-out/gen/src/prebuilt/prebuilt.dll")));
  }

  @Test
  public void shouldBeAbleToEmbedResourcesIntoTheBuiltDll() throws IOException {
    workspace.runBuckCommand(env, "build", "//src:embed").assertSuccess();
    assertTrue(Files.exists(workspace.resolve("buck-out/gen/src/embed/embed.dll")));
    workspace.runBuckCommand(env, "clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand(env, "build", "//src:embed").assertSuccess();
    workspace.getBuildLog().assertTargetWasFetchedFromCache("//src:embed");
    assertTrue(Files.exists(workspace.resolve("buck-out/gen/src/embed/embed.dll")));
  }

  @Test
  public void shouldBeAbleToDependOnAnotherCsharpLibrary() throws IOException {
    workspace.runBuckCommand(env, "build", "//src:dependent").assertSuccess();
    assertTrue(Files.exists(workspace.resolve("buck-out/gen/src/dependent/dependent.dll")));
    workspace.runBuckCommand(env, "clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand(env, "build", "//src:dependent").assertSuccess();
    workspace.getBuildLog().assertTargetWasFetchedFromCache("//src:dependent");
    assertTrue(Files.exists(workspace.resolve("buck-out/gen/src/dependent/dependent.dll")));
  }

  @Test
  public void shouldCachePrebuiltCsharpLibrary() throws IOException {
    workspace.runBuckCommand(env, "build", "//lib:log4net").assertSuccess();
    assertTrue(Files.exists(workspace.resolve("buck-out/gen/lib/log4net/log4net.dll")));
    workspace.runBuckCommand(env, "clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand(env, "build", "//lib:log4net").assertSuccess();
    workspace.getBuildLog().assertTargetWasFetchedFromCache("//lib:log4net");
    assertTrue(Files.exists(workspace.resolve("buck-out/gen/lib/log4net/log4net.dll")));
  }

  @Test
  @Ignore
  public void shouldBeAbleToAddTheSameResourceToADllTwice() {
    fail("Implement me, please!");
  }

  private void checkAssumptions() {
    assumeTrue("Running on windows", Platform.detect() == Platform.WINDOWS);
    Optional<Path> csc =
        new ExecutableFinder().getOptionalExecutable(Paths.get(CSC_EXE), ImmutableMap.of());
    assumeTrue(String.format("csc.exe (%s) is available", CSC_EXE), csc.isPresent());
  }

  private ImmutableMap<String, String> getEnv() {
    ImmutableMap<String, String> defaultEnv = EnvVariablesProvider.getSystemEnv();
    if (configureCsc) {
      return defaultEnv;
    } else {
      HashMap<String, String> patchedEnv = new HashMap<>(defaultEnv);
      patchedEnv.put("PATH", String.format("%s;%s", CSC_DIR, defaultEnv.get("PATH")));
      return ImmutableMap.copyOf(patchedEnv);
    }
  }
}
