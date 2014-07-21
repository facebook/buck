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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class CxxBinaryIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testSimpleCxxBinaryBuilds() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//foo:simple");
    String sourceName = "simple.cpp";
    String sourceFull = "foo/" + sourceName;
    BuildTarget compileTarget = CxxCompilableEnhancer.createCompileBuildTarget(target, sourceName);
    BuildTarget headerTarget = CxxDescriptionEnhancer.createHeaderTarget(target);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target);

    // Do a clean build, verify that it succeeds, and check that all expected targets built
    // successfully.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(headerTarget, headerSymlinkTreeTarget, compileTarget, target),
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(headerTarget.toString());
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
    buildLog.assertTargetBuiltLocally(target.toString());

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Check that running a build again results in no builds since everything is up to
    // date.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(headerTarget, headerSymlinkTreeTarget, compileTarget, target),
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(headerTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(compileTarget.toString());
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
        ImmutableSet.of(headerTarget, headerSymlinkTreeTarget, compileTarget, target),
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(headerTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
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
        ImmutableSet.of(headerTarget, headerSymlinkTreeTarget, compileTarget),
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(headerTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
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

    BuildTarget target = BuildTargetFactory.newInstance("//foo:simple_with_header");
    String sourceName = "simple_with_header.cpp";
    String headerName = "simple_with_header.h";
    String headerFull = "foo/" + headerName;
    BuildTarget compileTarget = CxxCompilableEnhancer.createCompileBuildTarget(target, sourceName);
    BuildTarget headerTarget = CxxDescriptionEnhancer.createHeaderTarget(target);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target);

    // Do a clean build, verify that it succeeds, and check that all expected targets built
    // successfully.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(headerTarget, headerSymlinkTreeTarget, compileTarget, target),
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(headerTarget.toString());
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
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
        ImmutableSet.of(headerTarget, headerSymlinkTreeTarget, compileTarget, target),
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(headerTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
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
    BuildTarget target = BuildTargetFactory.newInstance("//foo:binary_with_dep");
    String sourceName = "foo.cpp";
    BuildTarget compileTarget = CxxCompilableEnhancer.createCompileBuildTarget(target, sourceName);
    BuildTarget headerTarget = CxxDescriptionEnhancer.createHeaderTarget(target);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target);

    // Setup variables pointing to the sources and targets of the library dep.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//foo:library_with_header");
    String depSourceName = "bar.cpp";
    String depSourceFull = "foo/" + depSourceName;
    String depHeaderName = "bar.h";
    String depHeaderFull = "foo/" + depHeaderName;
    BuildTarget depCompileTarget =
        CxxCompilableEnhancer.createCompileBuildTarget(depTarget, depSourceName);
    BuildTarget depHeaderTarget = CxxDescriptionEnhancer.createHeaderTarget(depTarget);
    BuildTarget depHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(depTarget);
    BuildTarget depArchiveTarget = CxxDescriptionEnhancer.createStaticLibraryBuildTarget(depTarget);

    // Do a clean build, verify that it succeeds, and check that all expected targets built
    // successfully.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(
            depHeaderTarget,
            depHeaderSymlinkTreeTarget,
            depCompileTarget,
            depArchiveTarget,
            headerTarget,
            headerSymlinkTreeTarget,
            compileTarget,
            target),
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(depHeaderTarget.toString());
    buildLog.assertTargetBuiltLocally(depHeaderSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(depCompileTarget.toString());
    buildLog.assertTargetBuiltLocally(depArchiveTarget.toString());
    buildLog.assertTargetBuiltLocally(headerTarget.toString());
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
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
            depHeaderTarget,
            depHeaderSymlinkTreeTarget,
            depCompileTarget,
            depArchiveTarget,
            headerTarget,
            headerSymlinkTreeTarget,
            compileTarget,
            target),
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(depHeaderTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(depHeaderSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(depCompileTarget.toString());
    buildLog.assertTargetBuiltLocally(depArchiveTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
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
            depHeaderTarget,
            depHeaderSymlinkTreeTarget,
            depCompileTarget,
            depArchiveTarget,
            headerTarget,
            headerSymlinkTreeTarget,
            compileTarget,
            target),
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(depHeaderTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(depHeaderSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(depCompileTarget.toString());
    buildLog.assertTargetBuiltLocally(depArchiveTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(compileTarget.toString());
    buildLog.assertTargetBuiltLocally(target.toString());
  }

  @Test
  public void testCxxBinaryWithGeneratedSourceAndHeader() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//foo:binary_without_dep").assertFailure();
  }

}
