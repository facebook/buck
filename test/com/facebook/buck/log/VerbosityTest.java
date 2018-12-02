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

package com.facebook.buck.log;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class VerbosityTest {

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "log", tmp);
    workspace.addBuckConfigLocalOption("ui", "warn_on_config_file_overrides", "false");
    workspace.setUp();
  }

  @Test
  public void noOutputOnNormalBuildWithVerboseZero() throws IOException {
    ProcessResult result = workspace.runBuckdCommand("build", "--verbose", "0", "//:foo");
    result.assertSuccess();
    Assert.assertEquals("", result.getStderr());
    Assert.assertEquals("", result.getStdout());
  }

  @Test
  public void noOutputOnConfigChangeWithVerboseZero() throws IOException {
    workspace.runBuckdCommand("build", "//:foo").assertSuccess();
    Map<String, ? extends Map<String, String>> override =
        ImmutableMap.<String, ImmutableMap<String, String>>builder()
            .put(
                "somesection",
                ImmutableMap.<String, String>builder().put("somekey", "somevalue").build())
            .build();
    TestDataHelper.overrideBuckconfig(workspace, override);
    ProcessResult result = workspace.runBuckdCommand("build", "--verbose", "0", "//:foo");
    result.assertSuccess();
    Assert.assertEquals("", result.getStderr());
    Assert.assertEquals("", result.getStdout());
  }
}
