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

import com.facebook.buck.maven.TestPublisher;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

public class PublishCommandIntegrationTest {
  public static final String EXPECTED_PUT_URL_PATH = "/com/example/foo/1.0/foo-1.0.jar";
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
  public void testBasicCase() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "publish", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "publish",
        PublishCommand.REMOTE_REPO_SHORT_ARG,
        publisher.getHttpd().getRootUri().toString(),
        "//:foo");
    result.assertSuccess();
    List<String> putRequestsPaths = publisher.getPutRequestsHandler().getPutRequestsPaths();
    assertThat(putRequestsPaths, hasItem(EXPECTED_PUT_URL_PATH));
    assertThat(putRequestsPaths, hasItem(EXPECTED_PUT_URL_PATH + ".sha1"));
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
}
