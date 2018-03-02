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

package com.facebook.buck.cli;

import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ThriftRuleKeyDeserializer;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.thrift.TException;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class BuildCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void justBuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    workspace.runBuckBuild("--just-build", "//:bar", "//:foo", "//:ex ample").assertSuccess();
    assertThat(
        workspace.getBuildLog().getAllTargets(),
        Matchers.contains(BuildTargetFactory.newInstance(workspace.getDestPath(), "//:bar")));
  }

  @Test
  public void showOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult = workspace.runBuckBuild("--show-output", "//:bar");
    runBuckResult.assertSuccess();
    assertThat(runBuckResult.getStdout(), Matchers.containsString("//:bar buck-out"));
  }

  @Test
  public void showFullOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult = workspace.runBuckBuild("--show-full-output", "//:bar");
    runBuckResult.assertSuccess();
    Path expectedRootDirectory = tmp.getRoot();
    String expectedOutputDirectory = expectedRootDirectory.resolve("buck-out/").toString();
    String stdout = runBuckResult.getStdout();
    assertThat(stdout, Matchers.containsString("//:bar "));
    assertThat(stdout, Matchers.containsString(expectedOutputDirectory));
  }

  @Test
  public void showJsonOutput() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild("--show-json-output", "//:foo", "//:bar", "//:ex ample");
    runBuckResult.assertSuccess();
    assertThat(
        runBuckResult.getStdout(),
        Matchers.containsString(
            "\"//:bar\" : \"buck-out/gen/bar/bar\",\n  \"//:ex ample\" : \"buck-out/gen/ex ample/example\",\n  \"//:foo\" : \"buck-out/gen/foo/foo\"\n}"));
  }

  @Test
  public void showFullJsonOutput() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build/sub folder", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild("--show-full-json-output", "//:bar", "//:foo", "//:ex ample");
    runBuckResult.assertSuccess();
    Path expectedRootDirectory = tmp.getRoot();
    String expectedOutputDirectory = expectedRootDirectory.resolve("buck-out/").toString();
    assertThat(
        runBuckResult.getStdout(),
        Matchers.containsString(
            "{\n  \"//:bar\" : \""
                + expectedOutputDirectory
                + "/gen/bar/bar\",\n  \"//:ex ample\" : \""
                + expectedOutputDirectory
                + "/gen/ex ample/example\",\n  \"//:foo\" : \""
                + expectedOutputDirectory
                + "/gen/foo/foo\"\n}"));
  }

  @Test
  public void showRuleKey() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult = workspace.runBuckBuild("--show-rulekey", "//:bar");
    runBuckResult.assertSuccess();

    Pattern pattern = Pattern.compile("\\b[0-9a-f]{5,40}\\b"); // sha
    Matcher shaMatcher = pattern.matcher(runBuckResult.getStdout());
    assertThat(shaMatcher.find(), Matchers.equalTo(true));
    String shaValue = shaMatcher.group();
    assertThat(shaValue.length(), Matchers.equalTo(40));
    assertThat(runBuckResult.getStdout(), Matchers.containsString("//:bar " + shaValue));
  }

  @Test
  public void showRuleKeyAndOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild("--show-output", "--show-rulekey", "//:bar");
    runBuckResult.assertSuccess();

    Pattern pattern = Pattern.compile("\\b[0-9a-f]{5,40}\\b"); // sha
    Matcher shaMatcher = pattern.matcher(runBuckResult.getStdout());
    assertThat(shaMatcher.find(), Matchers.equalTo(true));
    String shaValue = shaMatcher.group();
    assertThat(shaValue.length(), Matchers.equalTo(40));
    assertThat(
        runBuckResult.getStdout(), Matchers.containsString("//:bar " + shaValue + " buck-out"));
  }

  @Test
  public void buckBuildAndCopyOutputFileWithBuildTargetThatSupportsIt() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "build_into", tmp);
    workspace.setUp();

    Path externalOutputs = tmp.newFolder("into-output");
    Path output = externalOutputs.resolve("the_example.jar");
    assertFalse(output.toFile().exists());
    workspace.runBuckBuild("//:example", "--out", output.toString()).assertSuccess();
    assertTrue(output.toFile().exists());

    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("com/example/Example.class");
  }

  @Test
  public void buckBuildAndCopyOutputFileWithBuildTargetThatDoesNotSupportIt() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "build_into", tmp);
    workspace.setUp();

    Path externalOutputs = tmp.newFolder("into-output");
    Path output = externalOutputs.resolve("pylib.zip");
    assertFalse(output.toFile().exists());
    ProcessResult result = workspace.runBuckBuild("//:example_py", "--out", output.toString());
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "//:example_py does not have an output that is compatible with `buck build --out`"));
  }

  @Test
  public void lastOutputDir() throws InterruptedException, IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild("-c", "build.create_build_output_symlinks_enabled=true", "//:bar");
    runBuckResult.assertSuccess();
    assertTrue(
        Files.exists(workspace.getBuckPaths().getLastOutputDir().toAbsolutePath().resolve("bar")));
  }

  @Test
  public void lastOutputDirForAppleBundle() throws InterruptedException, IOException {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_app_bundle", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild(
            "-c", "build.create_build_output_symlinks_enabled=true", "//:DemoApp#dwarf-and-dsym");
    runBuckResult.assertSuccess();
    assertTrue(
        Files.exists(
            workspace.getBuckPaths().getLastOutputDir().toAbsolutePath().resolve("DemoApp.app")));
    assertTrue(
        Files.exists(
            workspace
                .getBuckPaths()
                .getLastOutputDir()
                .toAbsolutePath()
                .resolve("DemoAppBinary#apple-dsym,iphonesimulator-x86_64.dSYM")));
  }

  @Test
  public void writesBinaryRuleKeysToDisk() throws IOException, TException {
    Path logFile = tmp.newFile("out.bin");
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild(
            "--show-rulekey", "--rulekeys-log-path", logFile.toAbsolutePath().toString(), "//:bar");
    runBuckResult.assertSuccess();

    List<FullRuleKey> ruleKeys = ThriftRuleKeyDeserializer.readRuleKeys(logFile);
    // Three rules, they could have any number of sub-rule keys and contributors
    assertTrue(ruleKeys.size() >= 3);
    assertTrue(ruleKeys.stream().anyMatch(ruleKey -> ruleKey.name.equals("//:bar")));
  }
}
