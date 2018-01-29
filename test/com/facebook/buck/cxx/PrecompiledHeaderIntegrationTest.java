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

import com.facebook.buck.file.ProjectFilesystemMatchers;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PrecompiledHeaderIntegrationTest {

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "precompiled_headers", tmp);
    workspace.setUp();
  }

  @Test
  public void compilesWithPrecompiledHeaders() throws IOException {
    CxxPrecompiledHeaderTestUtils.assumePrecompiledHeadersAreSupported();

    workspace.runBuckBuild("//:some_library#default,static").assertSuccess();
    findPchTarget();
  }

  @Test
  public void pchDepFileHasReferencedHeaders() throws IOException {
    CxxPrecompiledHeaderTestUtils.assumePrecompiledHeadersAreSupported();

    workspace.runBuckBuild("//:some_library#default,static").assertSuccess();
    BuildTarget target = findPchTarget();
    String depFileContents =
        workspace.getFileContents(
            "buck-out/gen/" + target.getShortNameAndFlavorPostfix() + ".h.gch.dep");
    assertThat(depFileContents, containsString("referenced_by_prefix_header.h"));
  }

  @Test
  public void changingPrefixHeaderCausesRecompile() throws Exception {
    CxxPrecompiledHeaderTestUtils.assumePrecompiledHeadersAreSupported();

    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    workspace.resetBuildLogFile();
    workspace.writeContentsToPath(
        "#pragma once\n"
            + "#include <stdio.h>\n"
            + "#include \"referenced_by_prefix_header.h\"\n"
            + "#include <referenced_by_prefix_header_from_dependency.h>\n"
            + "#define FOO 100\n",
        "prefix_header.h");
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(findPchTarget().toString());
    buildLog.assertTargetBuiltLocally("//:some_library#default,static");
  }

  @Test
  public void changingPchReferencedHeaderFromSameTargetCausesLibraryToRecompile() throws Exception {
    CxxPrecompiledHeaderTestUtils.assumePrecompiledHeadersAreSupported();

    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    workspace.resetBuildLogFile();
    workspace.writeContentsToPath(
        "#pragma once\n#define REFERENCED_BY_PREFIX_HEADER 3\n", "referenced_by_prefix_header.h");
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(findPchTarget().toString());
    buildLog.assertTargetBuiltLocally("//:some_library#default,static");
  }

  @Test
  public void changingPchReferencedHeaderFromDependencyCausesLibraryToRecompile() throws Exception {
    CxxPrecompiledHeaderTestUtils.assumePrecompiledHeadersAreSupported();

    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    workspace.resetBuildLogFile();
    workspace.writeContentsToPath(
        "#pragma once\n#define REFERENCED_BY_PREFIX_HEADER_FROM_DEPENDENCY 3\n",
        "referenced_by_prefix_header_from_dependency.h");
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(findPchTarget().toString());
    buildLog.assertTargetBuiltLocally("//:some_library#default,static");
  }

  @Test
  public void touchingPchReferencedHeaderShouldNotCauseClangToRejectPch() throws Exception {
    CxxPrecompiledHeaderTestUtils.assumePrecompiledHeadersAreSupported();

    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    workspace.resetBuildLogFile();
    // Change this file (not in the pch) to trigger recompile.
    workspace.writeContentsToPath("int lib_func() { return 0; }", "lib.c");
    // Touch this file that contributes to the PCH without changing its contents.
    workspace.writeContentsToPath(
        workspace.getFileContents("referenced_by_prefix_header_from_dependency.h"),
        "referenced_by_prefix_header_from_dependency.h");
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetHadMatchingRuleKey(findPchTarget().toString());
    buildLog.assertTargetBuiltLocally("//:some_library#default,static");
  }

  @Test
  public void changingCodeUsingPchWhenPchIsCachedButNotBuiltShouldBuildPch() throws Exception {
    CxxPrecompiledHeaderTestUtils.assumePrecompiledHeadersAreSupported();

    workspace.enableDirCache();
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache");
    workspace.writeContentsToPath("int lib_func() { return 0; }", "lib.c");
    workspace.runBuckBuild("//:some_binary#default").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(findPchTarget().toString());
    assertThat(
        workspace.asCell().getFilesystem(),
        ProjectFilesystemMatchers.pathExists(
            workspace.getPath(
                "buck-out/gen/" + findPchTarget().getShortNameAndFlavorPostfix() + ".h.gch")));
    buildLog.assertTargetBuiltLocally("//:some_library#default,static");
  }

  @Test
  public void testBuildUsingPrecompiledHeaderInOtherCell() throws Exception {
    CxxPrecompiledHeaderTestUtils.assumePrecompiledHeadersAreSupported();

    BuildTarget target = workspace.newBuildTarget("//:library_multicell_pch#default");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
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
}
