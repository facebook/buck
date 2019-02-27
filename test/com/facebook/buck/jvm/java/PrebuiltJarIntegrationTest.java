/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class PrebuiltJarIntegrationTest {

  @Rule public TemporaryPaths temp = new TemporaryPaths();

  @Test
  public void outputIsPlacedInCorrectFolder() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "prebuilt", temp);
    workspace.setUp();
    Path output = workspace.buildAndReturnOutput("//:jar_from_gen");
    assertTrue(Files.exists(output));

    Path localPath =
        BuildTargetPaths.getGenPath(
            workspace.asCell().getFilesystem(),
            BuildTargetFactory.newInstance("//:jar_from_gen"),
            "");
    Path expectedRoot = workspace.resolve(localPath);

    assertTrue(output.startsWith(expectedRoot));
  }

  @Test
  public void testAbiKeyIsHashOfFileContents() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "prebuilt", temp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:jar");
    BuildTarget abiTarget = target.withAppendedFlavors(JavaAbis.CLASS_ABI_FLAVOR);
    ProcessResult result = workspace.runBuckBuild(target.getFullyQualifiedName());
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(target);

    result = workspace.runBuckBuild("//:depends_on_jar");
    result.assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetHadMatchingRuleKey(target);

    // We expect the binary jar to have a different hash to the stub jar.
    Path binaryJar = workspace.getPath("junit.jar");
    HashCode originalHash = MorePaths.asByteSource(binaryJar).hash(Hashing.sha1());
    Path expectedOut =
        BuildTargetPaths.getGenPath(
                TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath()),
                abiTarget,
                "%s")
            .resolve(String.format("%s-abi.jar", abiTarget.getShortName()));
    Path abiJar = workspace.getPath(expectedOut.toString());
    HashCode abiHash = MorePaths.asByteSource(abiJar).hash(Hashing.sha1());

    assertTrue(Files.exists(abiJar));
    assertNotEquals(originalHash, abiHash);
  }

  @Test
  public void testPrebuiltJarWrappingABinaryJarGeneratedByAGenrule() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "prebuilt", temp);
    workspace.setUp();
    Path output = workspace.buildAndReturnOutput("//:jar_from_gen");
    assertTrue(Files.exists(output));
  }

  @Test
  public void testPrebuiltJarGenruleDirectory() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "prebuilt", temp);
    workspace.setUp();
    Path output = workspace.buildAndReturnOutput("//:jar_from_gen_dir");
    assertTrue(Files.exists(output));
    assertTrue(Files.isDirectory(output));

    workspace.runBuckCommand("run", "//:bin_from_gen_dir").assertSuccess();
  }

  @Test
  public void testPrebuiltJarRebuildsWhenItsInputsChange() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "prebuilt", temp);
    workspace.setUp();
    Path output = workspace.buildAndReturnOutput("//:jar_from_gen");
    assertTrue(Files.exists(output));

    workspace.copyFile("tiny.jar", "junit.jar");

    workspace.runBuckBuild("//:jar_from_gen").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:genjar");
    workspace.getBuildLog().assertTargetBuiltLocally("//:jar_from_gen");
  }

  @Test
  public void testPrebuiltJarDoesNotRebuildWhenDependentRulesChangeWhileProducingSameOutput()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "prebuilt", temp);
    workspace.setUp();
    Path output = workspace.buildAndReturnOutput("//:jar_from_gen");
    assertTrue(Files.exists(output));

    workspace.replaceFileContents("BUCK", "cp ", "cp  ");

    workspace.runBuckBuild("//:jar_from_gen").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:genjar");
    workspace.getBuildLog().assertTargetHadMatchingInputRuleKey("//:jar_from_gen");
  }

  @Test
  public void testPrebuiltJarRenamesExtensionButKeepsNameAndWarns() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "prebuilt", temp);
    workspace.setUp();
    ProcessResult processResult = workspace.runBuckBuild("//:jar_from_exported_zip");
    processResult.assertSuccess();
    assertThat(processResult.getStderr(), Matchers.stringContainsInOrder("renaming to junit.jar"));

    Path output = workspace.buildAndReturnOutput("//:jar_from_exported_zip");
    assertThat(MorePaths.getFileExtension(output), Matchers.equalTo("jar"));
  }
}
