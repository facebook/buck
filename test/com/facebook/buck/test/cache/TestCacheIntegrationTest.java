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

package com.facebook.buck.test.cache;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.io.Files;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class TestCacheIntegrationTest {

  private ProjectWorkspace workspace;

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Before
  public void setupWorkspace() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "one_test", temporaryFolder);
    workspace.setUp();
  }

  @Ignore
  @Test
  public void shouldUseCacheByDefault() throws IOException {
    assertThat("First run should not be cached",
        run("test", "--all"), not(containsString("CACHED")));
    assertThat("Second run should be cached",
        run("test", "--all"), containsString("CACHED"));
  }

  @Ignore
  @Test
  public void shouldAvoidCacheWithCommandLineOption() throws IOException {
    assertThat("First run should not be cached",
        run("test", "--all"), not(containsString("CACHED")));
    assertThat("Second run should also not be cached",
        run("test", "--all", "--no-results-cache"), not(containsString("CACHED")));
  }

  @Ignore
  @Test
  public void shouldAvoidCacheWithConfigFileOption() throws IOException {
    String newBuckProject = Joiner.on('\n').join(
        "[test]",
        "  use_cached_results = no");
    Files.write(newBuckProject, workspace.getFile(".buckconfig"), Charsets.UTF_8);

    assertThat("First run should not be cached",
        run("test", "--all"), not(containsString("CACHED")));
    assertThat("Second run should also not be cached",
        run("test", "--all", "--no-results-cache"), not(containsString("CACHED")));
  }

  private String run(String... args) throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(args);
    result.assertSuccess();
    return result.getStderr();
  }
}
