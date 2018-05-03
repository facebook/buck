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

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItemInArray;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class CxxLinkIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void inputBasedRuleKeyAvoidsRelinkingAfterChangeToUnusedHeader() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "step_test", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:binary_with_unused_header");
    String unusedHeaderName = "unused_header.h";
    BuildTarget linkTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target, Optional.empty());

    // Run the build and verify that the C++ source was compiled.
    workspace.runBuckBuild(target.toString());
    BuckBuildLog.BuildLogEntry firstRunEntry = workspace.getBuildLog().getLogEntry(linkTarget);
    assertThat(
        firstRunEntry.getSuccessType(), equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Now modify the unused header.
    workspace.writeContentsToPath(
        "static inline int newFunction() { return 20; }", unusedHeaderName);

    // Run the build again and verify that got a matching input-based rule key, and therefore
    // didn't recompile.
    workspace.runBuckBuild(target.toString());
    BuckBuildLog.BuildLogEntry secondRunEntry = workspace.getBuildLog().getLogEntry(linkTarget);
    assertThat(
        secondRunEntry.getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY)));

    // Also, make sure the original rule keys are actually different.
    assertThat(secondRunEntry.getRuleKey(), not(equalTo(firstRunEntry.getRuleKey())));
  }

  @Test
  public void osoSymbolsAreScrubbedCorrectly() throws Exception {
    Assume.assumeThat(Platform.detect(), equalTo(Platform.MACOS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_link_oso_symbol_scrub", tmp);
    workspace.setUp();
    Path resultPath = workspace.getPath("result");
    workspace.runBuckBuild("root_cell//:binary", "--out", resultPath.toString()).assertSuccess();
    String output =
        workspace.runCommand("dsymutil", "-s", resultPath.toString()).getStdout().toString();
    String[] lines = output.split("\n");
    assertThat(
        "Path in root cell is relativized to ./",
        lines,
        hasItemInArray(matchesPattern(".*N_OSO.*'\\./buck-out/.*")));
    assertThat(
        "Path in non-root cell is relativized relative to root cell",
        lines,
        hasItemInArray(matchesPattern(".*N_OSO.*'\\.\\./other_cell/buck-out/.*")));
  }

  @Test
  public void linkerExtraOutputsWork() throws Exception {
    // The test uses a dummy ld using python.
    Assume.assumeThat(Platform.detect(), not(equalTo(Platform.WINDOWS)));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "linker_extra_outputs_work", tmp);
    workspace.setUp();
    Path result = workspace.buildAndReturnOutput(":map-extractor");
    String contents;

    contents = new String(Files.readAllBytes(result.resolve("bin")), StandardCharsets.UTF_8);
    assertThat(contents, not(emptyString()));

    contents = new String(Files.readAllBytes(result.resolve("shared_lib")), StandardCharsets.UTF_8);
    assertThat(contents, not(emptyString()));
  }
}
