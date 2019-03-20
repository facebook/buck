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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.file.ProjectFilesystemMatchers;
import com.facebook.buck.testutil.ParameterizedTests;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.function.ThrowingConsumer;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Collection;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class PrecompiledHeaderIntegrationTest {

  @Parameterized.Parameter() public boolean pchEnabled;

  @Parameterized.Parameters(name = "cxx.pch_enabled={0}")
  public static Collection<Object[]> data() {
    return ParameterizedTests.getPermutations(ImmutableList.of(false, true));
  }

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "precompiled_headers", tmp);
    workspace.addBuckConfigLocalOption("build", "depfiles", "cache");
    workspace.addBuckConfigLocalOption("cxx", "pch_enabled", pchEnabled ? "true" : "false");
    workspace.setUp();
  }

  @Test
  public void compilesWithPrecompiledHeaders() throws Exception {
    CxxPrecompiledHeaderTestUtils.assumePrecompiledHeadersAreSupported();

    workspace.runBuckBuild("//:some_library#default,static").assertSuccess();
    findPchTarget(target -> {});
  }

  @Test
  public void pchDepFileHasReferencedHeaders() throws Exception {
    CxxPrecompiledHeaderTestUtils.assumePrecompiledHeadersAreSupported();

    workspace.runBuckBuild("//:some_library#default,static").assertSuccess();
    findPchTarget(
        target -> {
          String depFileContents =
              workspace.getFileContents(
                  "buck-out/gen/" + target.getShortNameAndFlavorPostfix() + ".h.gch.dep");
          assertThat(depFileContents, containsString("referenced_by_prefix_header.h"));
        });
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
    findPchTarget(buildLog::assertTargetBuiltLocally);
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
    findPchTarget(buildLog::assertTargetBuiltLocally);
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
    findPchTarget(buildLog::assertTargetBuiltLocally);
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
    findPchTarget(buildLog::assertTargetHadMatchingRuleKey);
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
    findPchTarget(buildLog::assertTargetBuiltLocally);
    findPchTarget(
        target ->
            assertThat(
                workspace.asCell().getFilesystem(),
                ProjectFilesystemMatchers.pathExists(
                    workspace.getPath(
                        "buck-out/gen/" + target.getShortNameAndFlavorPostfix() + ".h.gch"))));
    buildLog.assertTargetBuiltLocally("//:some_library#default,static");
  }

  @Test
  public void testBuildUsingPrecompiledHeaderInOtherCell() throws Exception {
    CxxPrecompiledHeaderTestUtils.assumePrecompiledHeadersAreSupported();

    BuildTarget target = workspace.newBuildTarget("//:library_multicell_pch#default");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
  }

  private void findPchTarget(ThrowingConsumer<BuildTarget, Exception> consumer) throws Exception {
    if (!pchEnabled) {
      return;
    }
    for (BuildTarget target : workspace.getBuildLog().getAllTargets()) {
      for (Flavor flavor : target.getFlavors()) {
        if (flavor.getName().startsWith("pch-")) {
          consumer.accept(target);
          return;
        }
      }
    }
    fail("should have generated a pch target");
  }
}
