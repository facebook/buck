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

import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.Javac;
import com.facebook.buck.maven.AetherUtil;
import com.facebook.buck.maven.TestPublisher;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.FluentIterable;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

public class PublishCommandIntegrationTest {
  public static final String EXPECTED_PUT_URL_PATH_BASE = "/com/example/foo/1.0/foo-1.0";
  public static final String JAR = ".jar";
  public static final String POM = ".pom";
  public static final String SRC_JAR = Javac.SRC_JAR;
  public static final String SHA1 = ".sha1";
  public static final String TARGET = "//:foo";
  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private TestPublisher publisher;

  @Before
  public void setUp() throws Exception {
    publisher = TestPublisher.create(tmp);
  }

  @After
  public void tearDown() throws Exception {
    publisher.close();
  }

  @Test
  public void testDependenciesTriggerPomGeneration() throws IOException {
    ProjectWorkspace.ProcessResult result = runValidBuckPublish("publish_fatjar");
    result.assertSuccess();
    List<String> putRequestsPaths = publisher.getPutRequestsHandler().getPutRequestsPaths();
    assertThat(putRequestsPaths, hasItem(EXPECTED_PUT_URL_PATH_BASE + POM));
    assertThat(putRequestsPaths, hasItem(EXPECTED_PUT_URL_PATH_BASE + POM + SHA1));
  }

  @Test
  public void testBasicCase() throws IOException {
    ProjectWorkspace.ProcessResult result = runValidBuckPublish("publish");
    result.assertSuccess();
  }

  private ProjectWorkspace.ProcessResult runValidBuckPublish(String workspaceName)
      throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, workspaceName, tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = runBuckPublish(
        workspace,
        PublishCommand.INCLUDE_SOURCE_LONG_ARG);
    result.assertSuccess();
    List<String> putRequestsPaths = publisher.getPutRequestsHandler().getPutRequestsPaths();
    assertThat(putRequestsPaths, hasItem(EXPECTED_PUT_URL_PATH_BASE + JAR));
    assertThat(putRequestsPaths, hasItem(EXPECTED_PUT_URL_PATH_BASE + JAR + SHA1));
    assertThat(putRequestsPaths, hasItem(EXPECTED_PUT_URL_PATH_BASE + SRC_JAR));
    assertThat(putRequestsPaths, hasItem(EXPECTED_PUT_URL_PATH_BASE + SRC_JAR + SHA1));
    return result;
  }

  @Test
  public void testRequireRepoUrl() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "publish", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "publish",
        "//:foo");
    result.assertFailure();
    assertTrue(result.getStderr().contains(PublishCommand.REMOTE_REPO_LONG_ARG));
  }

  @Test
  public void testDryDun() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "publish", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = runBuckPublish(
        workspace,
        PublishCommand.INCLUDE_SOURCE_LONG_ARG,
        PublishCommand.DRY_RUN_LONG_ARG);
    result.assertSuccess();

    assertTrue(publisher.getPutRequestsHandler().getPutRequestsPaths().isEmpty());

    String stdOut = result.getStdout();
    assertTrue(stdOut, stdOut.contains("com.example:foo:jar:1.0"));
    assertTrue(stdOut,
        stdOut.contains("com.example:foo:jar:" + AetherUtil.CLASSIFIER_SOURCES + ":1.0"));
    assertTrue(stdOut, stdOut.contains("/foo#maven.jar"));
    assertTrue(stdOut, stdOut.contains(Javac.SRC_JAR));
    assertTrue(stdOut, stdOut.contains(getMockRepoUrl()));
  }

  private ProjectWorkspace.ProcessResult runBuckPublish(
      ProjectWorkspace workspace,
      String... extraArgs) throws IOException {
    return workspace.runBuckCommand(
        FluentIterable
            .of(new String[]{"publish"})
            .append(extraArgs)
            .append(
                PublishCommand.REMOTE_REPO_SHORT_ARG,
                getMockRepoUrl(),
                TARGET)
            .toArray(String.class));
  }

  private String getMockRepoUrl() {
    return publisher.getHttpd().getRootUri().toString();
  }

}
