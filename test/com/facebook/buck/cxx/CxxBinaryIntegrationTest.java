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

package com.facebook.buck.cxx;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

public class CxxBinaryIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testSimpleCxxBinaryBuilds() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple", tmp);
    workspace.setUp();

    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(new FakeBuckConfig());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:simple");
    BuildTarget binaryTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target);
    String sourceName = "simple.cpp";
    String sourceFull = "foo/" + sourceName;
    BuildTarget preprocessTarget = CxxPreprocessables.createPreprocessBuildTarget(
        target,
        cxxPlatform.getFlavor(),
        CxxSource.Type.CXX,
        /* pic */ false,
        sourceName);
    BuildTarget compileTarget = CxxCompilableEnhancer.createCompileBuildTarget(
        target,
        cxxPlatform.getFlavor(),
        sourceName,
        /* pic */ false);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target, cxxPlatform.getFlavor());

    // Do a clean build, verify that it succeeds, and check that all expected targets built
    // successfully.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(
            headerSymlinkTreeTarget,
            preprocessTarget,
            compileTarget,
            binaryTarget,
            target),
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(preprocessTarget.toString());
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
    buildLog.assertTargetBuiltLocally(binaryTarget.toString());
    buildLog.assertTargetBuiltLocally(target.toString());

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Check that running a build again results in no builds since everything is up to
    // date.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(
            headerSymlinkTreeTarget,
            preprocessTarget,
            compileTarget,
            binaryTarget,
            target),
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(preprocessTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(compileTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(binaryTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(target.toString());

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(sourceFull, "{}", "{ return 0; }");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(
            headerSymlinkTreeTarget,
            preprocessTarget,
            compileTarget,
            binaryTarget,
            target),
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(preprocessTarget.toString());
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
    buildLog.assertTargetBuiltLocally(binaryTarget.toString());
    buildLog.assertTargetBuiltLocally(target.toString());

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(sourceFull, "{ return 0; }", "won't compile");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertFailure();
    buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(
            headerSymlinkTreeTarget,
            preprocessTarget,
            compileTarget),
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(preprocessTarget.toString());
    buildLog.assertTargetFailed(compileTarget.toString());
  }

  @Test
  public void testSimpleCxxBinaryWithoutHeader() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//foo:simple_without_header").assertFailure();
  }

  @Test
  public void testSimpleCxxBinaryWithHeader() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple", tmp);
    workspace.setUp();

    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(new FakeBuckConfig());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:simple_with_header");
    BuildTarget binaryTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target);
    String sourceName = "simple_with_header.cpp";
    String headerName = "simple_with_header.h";
    String headerFull = "foo/" + headerName;
    BuildTarget preprocessTarget = CxxPreprocessables.createPreprocessBuildTarget(
        target,
        cxxPlatform.getFlavor(),
        CxxSource.Type.CXX,
        /* pic */ false,
        sourceName);
    BuildTarget compileTarget = CxxCompilableEnhancer.createCompileBuildTarget(
        target,
        cxxPlatform.getFlavor(),
        sourceName,
        /* pic */ false);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target, cxxPlatform.getFlavor());

    // Do a clean build, verify that it succeeds, and check that all expected targets built
    // successfully.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(
            headerSymlinkTreeTarget,
            preprocessTarget,
            compileTarget,
            binaryTarget,
            target),
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(preprocessTarget.toString());
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
    buildLog.assertTargetBuiltLocally(binaryTarget.toString());
    buildLog.assertTargetBuiltLocally(target.toString());

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(headerFull, "BLAH 5", "BLAH 6");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(
            headerSymlinkTreeTarget,
            preprocessTarget,
            compileTarget,
            binaryTarget,
            target),
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(preprocessTarget.toString());
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
    buildLog.assertTargetBuiltLocally(binaryTarget.toString());
    buildLog.assertTargetBuiltLocally(target.toString());
  }

  @Test
  public void testSimpleCxxBinaryMissingDependencyOnCxxLibraryWithHeader() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//foo:binary_without_dep").assertFailure();
  }

  @Test
  public void testSimpleCxxBinaryWithDependencyOnCxxLibraryWithHeader() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple", tmp);
    workspace.setUp();

    // Setup variables pointing to the sources and targets of the top-level binary rule.
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(new FakeBuckConfig());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:binary_with_dep");
    BuildTarget binaryTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target);
    String sourceName = "foo.cpp";
    BuildTarget preprocessTarget = CxxPreprocessables.createPreprocessBuildTarget(
        target,
        cxxPlatform.getFlavor(),
        CxxSource.Type.CXX,
        /* pic */ false,
        sourceName);
    BuildTarget compileTarget = CxxCompilableEnhancer.createCompileBuildTarget(
        target,
        cxxPlatform.getFlavor(),
        sourceName,
        /* pic */ false);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target, cxxPlatform.getFlavor());

    // Setup variables pointing to the sources and targets of the library dep.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//foo:library_with_header");
    String depSourceName = "bar.cpp";
    String depSourceFull = "foo/" + depSourceName;
    String depHeaderName = "bar.h";
    String depHeaderFull = "foo/" + depHeaderName;
    BuildTarget depPreprocessTarget =
        CxxPreprocessables.createPreprocessBuildTarget(
            depTarget,
            cxxPlatform.getFlavor(),
            CxxSource.Type.CXX,
            /* pic */ false,
            depSourceName);
    BuildTarget depCompileTarget =
        CxxCompilableEnhancer.createCompileBuildTarget(
            depTarget,
            cxxPlatform.getFlavor(),
            depSourceName,
            /* pic */ false);
    BuildTarget depHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(depTarget, cxxPlatform.getFlavor());
    BuildTarget depArchiveTarget =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(depTarget, cxxPlatform.getFlavor());

    // Do a clean build, verify that it succeeds, and check that all expected targets built
    // successfully.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(
            depHeaderSymlinkTreeTarget,
            depPreprocessTarget,
            depCompileTarget,
            depArchiveTarget,
            depTarget,
            headerSymlinkTreeTarget,
            preprocessTarget,
            compileTarget,
            binaryTarget,
            target),
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(depHeaderSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(depPreprocessTarget.toString());
    buildLog.assertTargetBuiltLocally(depCompileTarget.toString());
    buildLog.assertTargetBuiltLocally(depArchiveTarget.toString());
    buildLog.assertTargetBuiltLocally(depTarget.toString());
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(preprocessTarget.toString());
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
    buildLog.assertTargetBuiltLocally(binaryTarget.toString());
    buildLog.assertTargetBuiltLocally(target.toString());

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(depHeaderFull, "int x", "int y");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(
            depHeaderSymlinkTreeTarget,
            depPreprocessTarget,
            depCompileTarget,
            depArchiveTarget,
            depTarget,
            headerSymlinkTreeTarget,
            preprocessTarget,
            compileTarget,
            binaryTarget,
            target),
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(depHeaderSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(depPreprocessTarget.toString());
    buildLog.assertTargetBuiltLocally(depCompileTarget.toString());
    buildLog.assertTargetBuiltLocally(depArchiveTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(depTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(preprocessTarget.toString());
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
    buildLog.assertTargetBuiltLocally(binaryTarget.toString());
    buildLog.assertTargetBuiltLocally(target.toString());

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(depSourceFull, "x + 5", "x + 6");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(
            depHeaderSymlinkTreeTarget,
            depPreprocessTarget,
            depCompileTarget,
            depArchiveTarget,
            depTarget,
            headerSymlinkTreeTarget,
            preprocessTarget,
            compileTarget,
            binaryTarget,
            target),
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(depHeaderSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(depPreprocessTarget.toString());
    buildLog.assertTargetBuiltLocally(depCompileTarget.toString());
    buildLog.assertTargetBuiltLocally(depArchiveTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(depTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(preprocessTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(compileTarget.toString());
    buildLog.assertTargetBuiltLocally(binaryTarget.toString());
    buildLog.assertTargetBuiltLocally(target.toString());
  }

  @Test
  public void testCxxBinaryWithGeneratedSourceAndHeader() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//foo:binary_without_dep").assertFailure();
  }

  @Test
  public void testHeaderNamespace() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "header_namespace", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//:test").assertSuccess();
  }

  @Test
  public void resolveHeadersBehindSymlinkTreesInPreprocessedOutput() throws IOException {
    BuckConfig buckConfig = new FakeBuckConfig();
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(buckConfig);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "resolved", tmp);
    workspace.setUp();

    workspace.writeContentsToPath("", "lib2.h");

    BuildTarget target = BuildTargetFactory.newInstance("//:bin");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();

    // Verify that the preprocessed source contains no references to the symlink tree used to
    // setup the headers.
    BuildTarget ppTarget =
        CxxPreprocessables.createPreprocessBuildTarget(
            target,
            cxxPlatform.getFlavor(),
            CxxSource.Type.CXX,
            /* pic */ false,
            "bin.cpp");
    Path output =
        CxxPreprocessables.getPreprocessOutputPath(
            ppTarget,
            CxxSource.Type.CXX,
            "bin.cpp");
    String contents = workspace.getFileContents(output.toString());
    assertThat(contents, Matchers.not(Matchers.containsString(BuckConstant.BIN_DIR)));
    assertThat(contents, Matchers.not(Matchers.containsString(BuckConstant.GEN_DIR)));
    assertThat(contents, Matchers.containsString("# 1 \"bin.h"));
    assertThat(contents, Matchers.containsString("# 1 \"lib1.h"));
    assertThat(contents, Matchers.containsString("# 1 \"lib2.h"));
  }

  @Test
  public void resolveHeadersBehindSymlinkTreesInError() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "resolved", tmp);
    workspace.setUp();

    workspace.writeContentsToPath("#invalid_pragma", "lib2.h");

    BuildTarget target = BuildTargetFactory.newInstance("//:bin");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", target.toString());
    result.assertFailure();

    // Verify that the preprocessed source contains no references to the symlink tree used to
    // setup the headers.
    String error = result.getStderr();
    assertThat(error, Matchers.not(Matchers.containsString(BuckConstant.BIN_DIR)));
    assertThat(error, Matchers.not(Matchers.containsString(BuckConstant.GEN_DIR)));
    assertThat(error, Matchers.containsString("In file included from lib1.h:1"));
    assertThat(error, Matchers.containsString("from bin.h:1"));
    assertThat(error, Matchers.containsString("from bin.cpp:1:"));
    assertThat(error, Matchers.containsString("lib2.h:1:2: error: invalid preprocessing"));
  }

  @Test
  public void ndkCxxPlatforms() throws IOException {
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//foo:simple#android-arm").assertSuccess();
    workspace.runBuckCommand("build", "//foo:simple#android-armv7").assertSuccess();
    workspace.runBuckCommand("build", "//foo:simple#android-x86").assertSuccess();
  }

  @Test
  public void linkerFlags() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "linker_flags", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:binary_with_linker_flag").assertFailure("--bad-flag");
    workspace.runBuckBuild("//:binary_with_library_dep").assertFailure("--bad-flag");
    workspace.runBuckBuild("//:binary_with_prebuilt_library_dep").assertFailure("--bad-flag");

    // Build binary that has unresolved symbols.  Normally this would fail, but should work
    // with the proper linker flag.
    switch (Platform.detect()) {
      case MACOS:
        workspace.runBuckBuild("//:binary_with_unresolved_symbols_macos").assertSuccess();
        break;
      case LINUX:
        workspace.runBuckBuild("//:binary_with_unresolved_symbols_linux").assertSuccess();
        break;
      // $CASES-OMITTED$
      default:
        break;
    }
  }

  private void platformLinkerFlags(ProjectWorkspace workspace, String target) throws IOException {
    workspace.runBuckBuild("//:binary_matches_default_exactly_" + target).assertSuccess();
    workspace.runBuckBuild("//:binary_matches_default_" + target).assertSuccess();
    workspace.runBuckBuild("//:binary_no_match_" + target).assertFailure("unresolved");
    workspace.runBuckBuild("//:binary_with_library_matches_default_" + target).assertSuccess();
    workspace.runBuckBuild("//:binary_with_prebuilt_library_matches_default_" + target)
        .assertSuccess();
  }

  @Test
  public void platformLinkerFlags() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "platform_linker_flags", tmp);
    workspace.setUp();

    // Build binary that has unresolved symbols.  Normally this would fail, but should work
    // with the proper linker flag.
    switch (Platform.detect()) {
      case MACOS:
        platformLinkerFlags(workspace, "macos");
        break;
      case LINUX:
        platformLinkerFlags(workspace, "linux");
        break;
      // $CASES-OMITTED$
      default:
        break;
    }
  }

}
