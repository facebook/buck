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

package com.facebook.buck.testutil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.MoreFiles;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class ProjectWorkspaceTest {

  @Test
  public void testGetBuildRuleSuccessTypes() throws IOException {
    DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();
    tmpFolder.create();
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "dir_doesnt_exist", tmpFolder);

    List<String> buildLogLines = Lists.newArrayList(
        "735 INFO  BuildRuleFinished(//example/base:one): SUCCESS MISS BUILT_LOCALLY 489e1b85f804dc0f66545f2ce06f57ee85204747",
        "735 INFO  BuildRuleFinished(//example/base:two): SUCCESS MISS MISSING 489e1b85f804dc0f66545f2ce06f57ee85204747");

    tmpFolder.newFolder("buck-out");
    tmpFolder.newFolder("buck-out/bin");
    File buildLogFile = tmpFolder.newFile("buck-out/bin/build.log");
    MoreFiles.writeLinesToFile(buildLogLines, buildLogFile);

    Map<BuildTarget, Optional<BuildRuleSuccess.Type>> successTypes =
        workspace.getBuildRuleSuccessTypes();

    assertEquals(
        BuildRuleSuccess.Type.BUILT_LOCALLY,
        successTypes.get(BuildTargetFactory.newInstance("//example/base:one")).get());
    assertFalse(
        successTypes.get(BuildTargetFactory.newInstance("//example/base:two")).isPresent());
  }
}
