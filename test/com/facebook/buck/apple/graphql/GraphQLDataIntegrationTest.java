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

package com.facebook.buck.apple.graphql;

import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class GraphQLDataIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void writesOutTheRightBatchAndDefaultFilesFilesForCompiling() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "writes_out_the_right_batch_and_default_files_files_for_compiling", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:graphql-data#FOR_COMPILING").assertSuccess();
    workspace.verify();
  }

  @Test
  public void writesOutTheRightBatchAndDefaultFilesFilesForLinking() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "writes_out_the_right_batch_and_default_files_files_for_linking", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:graphql-data#FOR_LINKING").assertSuccess();
    workspace.verify();
  }

  @Test
  public void changesRuleKeysWhenGeneratorIsChanged() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "changes_rule_keys_when_plugins_are_changed", tmp);
    workspace.setUp();
    String target = "//:graphql-data#FOR_COMPILING";

    workspace.runBuckCommand("build", target).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(target);

    workspace.resetBuildLogFile();

    workspace.runBuckCommand("build", target).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetHadMatchingRuleKey(target);

    workspace.resetBuildLogFile();

    workspace.replaceFileContents("plugin.js", "hello", "world");
    workspace.runBuckCommand("build", target).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(target);
  }

}
