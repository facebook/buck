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
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CxxLexYaccIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private void assumeExists(Path path) {
    assumeTrue(String.format("Cannot find %s", path), Files.exists(path));
  }

  @Before
  public void setUp() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    CxxPlatform cxxBuckConfig = DefaultCxxPlatforms.build(new FakeBuckConfig());
    assumeTrue(cxxBuckConfig.getLex().isPresent());
    assumeTrue(cxxBuckConfig.getYacc().isPresent());
    assumeExists(pathResolver.getPath(cxxBuckConfig.getLex().get()));
    assumeExists(pathResolver.getPath(cxxBuckConfig.getYacc().get()));
  }

  @Test
  public void testSimpleCxxBinaryLexYaccBuilds() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "lexyacc", tmp);
    workspace.setUp();

    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(new FakeBuckConfig());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:main");
    BuildTarget binaryTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target);
    String sourceName = "main.cpp";
    String yaccSourceName = "mainy.yy";
    String yaccSourceFull = "foo/" + yaccSourceName;
    BuildTarget yaccTarget = CxxDescriptionEnhancer.createYaccBuildTarget(target, yaccSourceName);
    BuildTarget yaccPreprocessTarget = CxxPreprocessables.createPreprocessBuildTarget(
        target,
        cxxPlatform.getFlavor(),
        CxxSource.Type.CXX,
        /* pic */ false,
        yaccSourceName + ".cc");
    BuildTarget yaccCompileTarget = CxxCompilableEnhancer.createCompileBuildTarget(
        target,
        cxxPlatform.getFlavor(),
        yaccSourceName + ".cc",
        /* pic */ false);
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
            yaccTarget,
            headerSymlinkTreeTarget,
            yaccPreprocessTarget,
            yaccCompileTarget,
            preprocessTarget,
            compileTarget,
            binaryTarget,
            target),
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(yaccTarget.toString());
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
    buildLog.assertTargetBuiltLocally(yaccCompileTarget.toString());
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
            yaccTarget,
            headerSymlinkTreeTarget,
            yaccPreprocessTarget,
            yaccCompileTarget,
            preprocessTarget,
            compileTarget,
            binaryTarget,
            target),
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(yaccTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(compileTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(yaccCompileTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(binaryTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(target.toString());

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(yaccSourceFull, "expression", "somethingElse");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(
            yaccTarget,
            headerSymlinkTreeTarget,
            yaccPreprocessTarget,
            yaccCompileTarget,
            preprocessTarget,
            compileTarget,
            binaryTarget,
            target),
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(yaccTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
    buildLog.assertTargetBuiltLocally(yaccCompileTarget.toString());
    buildLog.assertTargetBuiltLocally(binaryTarget.toString());
    buildLog.assertTargetBuiltLocally(target.toString());
  }

}
