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

import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FetchCommandIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temp = new DebuggableTemporaryFolder();

  private static HttpdForTests httpd;

  @BeforeClass
  public static void startHttpd() throws Exception {
    httpd = new HttpdForTests();

    httpd.addStaticContent("cheese");
    httpd.start();
  }

  @AfterClass
  public static void shutdownHttpd() throws Exception {
    httpd.close();
  }

  @Test
  public void shouldBuildNothingIfThereAreNoFetchableRules() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "fetch_nothing",
        temp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("fetch", "//:example");

    result.assertSuccess();

    BuckBuildLog log = workspace.getBuildLog();
    ImmutableSet<BuildTarget> allTargets = log.getAllTargets();

    assertFalse(allTargets.contains(BuildTargetFactory.newInstance("//:example")));
  }

  @Test
  public void shouldFetchARemoteResourceIfThatIsTheExactTargetRequested()
      throws IOException, URISyntaxException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "fetch_concrete",
        temp);
    workspace.setUp();

    // We don't know the URL of the file beforehand. Fix that.
    addRemoteFileTarget(workspace);

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("fetch", "//:remote");

    result.assertSuccess();

    BuckBuildLog log = workspace.getBuildLog();
    ImmutableSet<BuildTarget> allTargets = log.getAllTargets();

    assertTrue(allTargets.contains(BuildTargetFactory.newInstance("//:remote")));
  }

  @Test
  public void shouldNotFetchARemoteResourceIfNotIncludedInTheSetOfTargetsToBuild()
      throws IOException, URISyntaxException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "fetch_concrete",
        temp);
    workspace.setUp();

    // We don't know the URL of the file beforehand. Fix that.
    addRemoteFileTarget(workspace);

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("fetch", "//:no-download");

    result.assertSuccess();

    BuckBuildLog log = workspace.getBuildLog();
    ImmutableSet<BuildTarget> allTargets = log.getAllTargets();

    assertFalse(allTargets.contains(BuildTargetFactory.newInstance("//:remote")));
  }

  @Test
  public void shouldOnlyExecuteDownloadableTargets() throws IOException, URISyntaxException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "fetch_concrete",
        temp);
    workspace.setUp();

    // We don't know the URL of the file beforehand. Fix that.
    addRemoteFileTarget(workspace);

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("fetch", "//:needs-download");

    result.assertSuccess();

    BuckBuildLog log = workspace.getBuildLog();
    ImmutableSet<BuildTarget> allTargets = log.getAllTargets();

    assertTrue(allTargets.contains(BuildTargetFactory.newInstance("//:remote")));
    assertFalse(allTargets.contains(BuildTargetFactory.newInstance("//:needs-download")));
  }

  private void addRemoteFileTarget(ProjectWorkspace workspace)
      throws IOException, URISyntaxException {
    Path buckFile = workspace.resolve("BUCK");

    String existingBuck = new String(Files.readAllBytes(buckFile), UTF_8);

    HashCode expectedHash = Hashing.sha1().hashString("cheese", UTF_16);

    String newRule = Joiner.on("\n").join(
        "remote_file(name = 'remote',",
        String.format("  url = '%s',", httpd.getUri("/cheese")),
        String.format("  sha1 = '%s',", expectedHash.toString()),
        "  out = 'example.txt',",
        ")");

    Files.write(buckFile, (existingBuck + "\n" + newRule).getBytes(UTF_8));
  }
}
