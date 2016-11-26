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

package com.facebook.buck.cxx;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.file.ProjectFilesystemMatchers;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;

import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.Optional;

public class PrecompiledHeaderIntegrationTest {

  private ProjectWorkspace workspace;

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "precompiled_headers", tmp);
    workspace.setUp();
  }

  @Test
  public void compilesWithPrecompiledHeaders() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    workspace.runBuckBuild("//:some_library#default,static").assertSuccess();
    findPchTarget();
  }

  @Test
  public void pchDepFileHasReferencedHeaders() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    workspace.runBuckBuild("//:some_library#default,static").assertSuccess();
    BuildTarget target = findPchTarget();
    String depFileContents = workspace.getFileContents(
        "buck-out/gen/" + target.getShortNameAndFlavorPostfix() + ".h.gch.dep");
    assertThat(depFileContents, containsString("referenced_by_prefix_header.h"));
  }

  @Test
  public void changingPrefixHeaderCausesRecompile() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    workspace.resetBuildLogFile();
    workspace.writeContentsToPath(
        "#pragma once\n" +
            "#include <stdio.h>\n" +
            "#include \"referenced_by_prefix_header.h\"\n" +
            "#include <referenced_by_prefix_header_from_dependency.h>\n" +
            "#define FOO 100\n",
        "prefix_header.h");
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertThat(
        buildLog,
        reportedTargetSuccessType(findPchTarget(), BuildRuleSuccessType.BUILT_LOCALLY));
    assertThat(
        buildLog,
        reportedTargetSuccessType(
            workspace.newBuildTarget("//:some_library#default,static"),
            BuildRuleSuccessType.BUILT_LOCALLY));
  }

  @Test
  public void changingPchReferencedHeaderFromSameTargetCausesLibraryToRecompile() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    workspace.resetBuildLogFile();
    workspace.writeContentsToPath(
        "#pragma once\n#define REFERENCED_BY_PREFIX_HEADER 3\n",
        "referenced_by_prefix_header.h");
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertThat(
        buildLog,
        reportedTargetSuccessType(findPchTarget(), BuildRuleSuccessType.BUILT_LOCALLY));
    assertThat(
        buildLog,
        reportedTargetSuccessType(
            workspace.newBuildTarget("//:some_library#default,static"),
            BuildRuleSuccessType.BUILT_LOCALLY));
  }

  @Test
  public void changingPchReferencedHeaderFromDependencyCausesLibraryToRecompile() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    workspace.resetBuildLogFile();
    workspace.writeContentsToPath(
        "#pragma once\n#define REFERENCED_BY_PREFIX_HEADER_FROM_DEPENDENCY 3\n",
        "referenced_by_prefix_header_from_dependency.h");
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertThat(
        buildLog,
        reportedTargetSuccessType(findPchTarget(), BuildRuleSuccessType.BUILT_LOCALLY));
    assertThat(
        buildLog,
        reportedTargetSuccessType(
            workspace.newBuildTarget("//:some_library#default,static"),
            BuildRuleSuccessType.BUILT_LOCALLY));
  }

  @Test
  public void touchingPchReferencedHeaderShouldNotCauseClangToRejectPch() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    workspace.resetBuildLogFile();
    // Change this file (not in the pch) to trigger recompile.
    workspace.writeContentsToPath(
        "int lib_func() { return 0; }",
        "lib.c");
    // Touch this file that contributes to the PCH without changing its contents.
    workspace.writeContentsToPath(
        workspace.getFileContents("referenced_by_prefix_header_from_dependency.h"),
        "referenced_by_prefix_header_from_dependency.h");
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertThat(
        "PCH should not change as no pch input file contents has changed.",
        buildLog,
        reportedTargetSuccessType(findPchTarget(), BuildRuleSuccessType.MATCHING_RULE_KEY));
    assertThat(
        buildLog,
        reportedTargetSuccessType(
            workspace.newBuildTarget("//:some_library#default,static"),
            BuildRuleSuccessType.BUILT_LOCALLY));
  }


  @Test
  public void changingCodeUsingPchWhenPchIsCachedButNotBuiltShouldBuildPch() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    workspace.enableDirCache();
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    workspace.runBuckCommand("clean");
    workspace.writeContentsToPath(
        "int lib_func() { return 0; }",
        "lib.c");
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertThat(
        buildLog,
        reportedTargetSuccessType(
            findPchTarget(),
            BuildRuleSuccessType.BUILT_LOCALLY));
    assertThat(
        workspace.asCell().getFilesystem(),
        ProjectFilesystemMatchers.pathExists(
            workspace.getPath(
                "buck-out/gen/" + findPchTarget().getShortNameAndFlavorPostfix() + ".h.gch")));
    assertThat(
        buildLog,
        reportedTargetSuccessType(
            workspace.newBuildTarget("//:some_library#default,static"),
            BuildRuleSuccessType.BUILT_LOCALLY));
  }

  private BuildTarget findPchTarget() throws IOException {
    for (BuildTarget target : workspace.getBuildLog().getAllTargets()) {
      for (Flavor flavor : target.getFlavors()) {
        if (flavor.getName().startsWith("pch-")) {
          return target;
        }
      }
    }
    fail("should have generated a pch target");
    return null;
  }

  private static Matcher<BuckBuildLog> reportedTargetSuccessType(
      final BuildTarget target,
      final BuildRuleSuccessType successType) {
    return new CustomTypeSafeMatcher<BuckBuildLog>(
        "target: " + target.toString() + " with result: " + successType) {

      @Override
      protected boolean matchesSafely(BuckBuildLog buckBuildLog) {
        return buckBuildLog.getLogEntry(target).getSuccessType().equals(Optional.of(successType));
      }
    };
  }

}
