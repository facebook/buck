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

package com.facebook.buck.features.python;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.features.python.toolchain.PythonVersion;
import com.facebook.buck.features.python.toolchain.impl.PythonPlatformsProviderFactoryUtils;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.ParameterizedTests;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.VersionStringComparator;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Collection;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class PytestTestIntegrationTest {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return ParameterizedTests.getPermutations(
        ImmutableList.copyOf(PythonBuckConfig.PackageStyle.values()));
  }

  @Parameterized.Parameter public PythonBuckConfig.PackageStyle packageStyle;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  public ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "pytest_test", tmp);
    workspace.setUp();
    workspace.writeContentsToPath(
        "[python]\npackage_style = " + packageStyle.toString().toLowerCase() + "\n", ".buckconfig");
    PythonBuckConfig config = getPythonBuckConfig();
    assertThat(config.getPackageStyle(), equalTo(packageStyle));
  }

  @Test
  public void testPythonTest() throws IOException {
    // This test should pass.
    ProcessResult result1 = workspace.runBuckCommand("test", "//:test-success");
    result1.assertSuccess();
    workspace.resetBuildLogFile();

    ProcessResult runResult1 = workspace.runBuckCommand("run", "//:test-success");
    runResult1.assertSuccess("python test binary should exit 0 on success");

    // This test should fail.
    ProcessResult result2 = workspace.runBuckCommand("test", "//:test-failure");
    result2.assertTestFailure();
//    assertThat(
//        "`buck test` should fail because test_that_fails() failed.",
//        result2.getStderr(),
//        containsString("test_that_fails"));
//
//    ProcessResult runResult2 = workspace.runBuckCommand("run", "//:test-failure");
//    runResult2.assertExitCode(
//        "python test binary should exit with expected error code on failure", ExitCode.TEST_ERROR);
  }

//  @Test
//  public void testPythonTestSelectors() throws IOException {
//    ProcessResult result =
//        workspace.runBuckCommand(
//            "test", "--test-selectors", "Test#test_that_passes", "//:test-failure");
//    result.assertSuccess();
//
//    result =
//        workspace.runBuckCommand(
//            "test", "--test-selectors", "!Test#test_that_fails", "//:test-failure");
//    result.assertSuccess();
//    workspace.resetBuildLogFile();
//
//    result =
//        workspace.runBuckCommand(
//            "test", "--test-selectors", "!test_failure.Test#", "//:test-failure");
//    result.assertSuccess();
//    assertThat(result.getStderr(), containsString("1 Passed"));
//  }
//
//  @Test
//  public void testRunPythonTest() throws IOException {
//    ProcessResult result = workspace.runBuckCommand("run", "//:test-success");
//    result.assertSuccess();
//    assertThat(result.getStderr(), containsString("test_that_passes (test_success.Test) ... ok"));
//  }
//
//  @Test
//  public void testPythonTestCached() throws IOException {
//    workspace.enableDirCache();
//
//    // Warm the cache.
//    workspace.runBuckBuild("//:test-success").assertSuccess();
//
//    // Clean buck-out.
//    workspace.runBuckCommand("clean", "--keep-cache");
//
//    // Run the tests, which should get cache hits for everything.
//    workspace.runBuckCommand("test", "//:test-success").assertSuccess();
//  }

  private PythonBuckConfig getPythonBuckConfig() throws IOException {
    Config rawConfig = Configs.createDefaultConfig(tmp.getRoot());
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(rawConfig.getRawConfig())
            .setFilesystem(TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()))
            .build();
    return new PythonBuckConfig(buckConfig);
  }
}
