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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Optional;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class CxxLinkIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void inputBasedRuleKeyAvoidsRelinkingAfterChangeToUnusedHeader() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "step_test", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:binary_with_unused_header");
    String unusedHeaderName = "unused_header.h";
    BuildTarget linkTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target);

    // Run the build and verify that the C++ source was compiled.
    workspace.runBuckBuild(target.toString());
    BuckBuildLog.BuildLogEntry firstRunEntry = workspace.getBuildLog().getLogEntry(linkTarget);
    assertThat(
        firstRunEntry.getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Now modify the unused header.
    workspace.writeContentsToPath(
        "static inline int newFunction() { return 20; }",
        unusedHeaderName);

    // Run the build again and verify that got a matching input-based rule key, and therefore
    // didn't recompile.
    workspace.runBuckBuild(target.toString());
    BuckBuildLog.BuildLogEntry secondRunEntry = workspace.getBuildLog().getLogEntry(linkTarget);
    assertThat(
        secondRunEntry.getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY)));

    // Also, make sure the original rule keys are actually different.
    assertThat(
        secondRunEntry.getRuleKey(),
        Matchers.not(Matchers.equalTo(firstRunEntry.getRuleKey())));
  }

}
