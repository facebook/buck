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

import static com.facebook.buck.cxx.CxxFlavorSanitizer.sanitize;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.InferHelper;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class CxxBinaryIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testInferCxxBinaryDepsCaching() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(
        this,
        tmp,
        Optional.<String>absent());
    workspace.enableDirCache(); // enable the cache

    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(
        new CxxBuckConfig(FakeBuckConfig.builder().build()));
    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_deps");
    String inputBuildTargetName = inputBuildTarget
        .withFlavors(CxxInferEnhancer.InferFlavors.INFER.get())
        .getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    for (BuildTarget buildTarget : buildLog.getAllTargets()) {
      buildLog.assertTargetWasFetchedFromCache(buildTarget.toString());
    }

    /*
     * Check that if the file in the binary target changes, then all the deps will be fetched
     * from the cache
     */
    String sourceName = "src_with_deps.c";
    workspace.replaceFileContents("foo/" + sourceName, "10", "30");
    workspace.runBuckCommand("clean").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();

    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(
        workspace.getDestPath(),
        inputBuildTarget,
        cxxPlatform);

    BuildTarget captureBuildTarget =
        cxxSourceRuleFactory.createInferCaptureBuildTarget(sourceName);

    // this is flavored, and denotes the analysis step (generates a local report)
    BuildTarget inferAnalysisTarget = inputBuildTarget
        .withFlavors(CxxInferEnhancer.InferFlavors.INFER_ANALYZE.get());

    // this is the flavored version of the top level target (the one give in input to buck)
    BuildTarget inferReportTarget = inputBuildTarget
        .withFlavors(CxxInferEnhancer.InferFlavors.INFER.get());

    String bt;
    for (BuildTarget buildTarget : buildLog.getAllTargets()) {
      bt = buildTarget.toString();
      if (bt.equals(inferAnalysisTarget.toString()) ||
          bt.equals(captureBuildTarget.toString()) ||
          bt.equals(inferReportTarget.toString())) {
        buildLog.assertTargetBuiltLocally(bt);
      } else {
        buildLog.assertTargetWasFetchedFromCache(buildTarget.toString());
      }
    }
  }

  @Test
  public void testInferCxxBinaryDepsInvalidateCacheWhenVersionChanges() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(
        this,
        tmp,
        Optional.<String>absent());
    workspace.enableDirCache(); // enable the cache

    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(
        new CxxBuckConfig(FakeBuckConfig.builder().build()));
    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_deps");
    final String inputBuildTargetName = inputBuildTarget.withFlavors(
        CxxInferEnhancer.InferFlavors.INFER.get()).getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    for (BuildTarget buildTarget : buildLog.getAllTargets()) {
      buildLog.assertTargetWasFetchedFromCache(buildTarget.toString());
    }

    /*
     * Check that if the version of infer changes, then all the infer-related targets are
     * recomputed
     */
    workspace.resetBuildLogFile();
    workspace.replaceFileContents("fake-infer/fake-bin/infer", "0.12345", "9.9999");
    workspace.runBuckCommand("clean").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();


    String sourceName = "src_with_deps.c";
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(
        workspace.getDestPath(),
        inputBuildTarget,
        cxxPlatform);

    BuildTarget topCaptureBuildTarget =
        cxxSourceRuleFactory.createInferCaptureBuildTarget(sourceName);

    BuildTarget topInferAnalysisTarget =
        inputBuildTarget.withFlavors(CxxInferEnhancer.InferFlavors.INFER_ANALYZE.get());

    BuildTarget topInferReportTarget = inputBuildTarget.withFlavors(
        CxxInferEnhancer.InferFlavors.INFER.get());

    BuildTarget depOneBuildTarget =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:dep_one");
    String depOneSourceName = "dep_one.c";
    CxxSourceRuleFactory depOneSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), depOneBuildTarget, cxxPlatform);

    BuildTarget depOneCaptureBuildTarget =
        depOneSourceRuleFactory.createInferCaptureBuildTarget(depOneSourceName);

    BuildTarget depOneInferAnalysisTarget =
        depOneCaptureBuildTarget.withFlavors(
            cxxPlatform.getFlavor(),
            CxxInferEnhancer.InferFlavors.INFER_ANALYZE.get());

    BuildTarget depTwoBuildTarget =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:dep_two");
    CxxSourceRuleFactory depTwoSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), depTwoBuildTarget, cxxPlatform);

    BuildTarget depTwoCaptureBuildTarget =
        depTwoSourceRuleFactory.createInferCaptureBuildTarget("dep_two.c");

    BuildTarget depTwoInferAnalysisTarget =
        depTwoCaptureBuildTarget.withFlavors(
            cxxPlatform.getFlavor(),
            CxxInferEnhancer.InferFlavors.INFER_ANALYZE.get());

    ImmutableSet<String> locallyBuiltTargets = ImmutableSet.of(
        topCaptureBuildTarget.toString(),
        topInferAnalysisTarget.toString(),
        topInferReportTarget.toString(),
        depOneCaptureBuildTarget.toString(),
        depOneInferAnalysisTarget.toString(),
        depTwoCaptureBuildTarget.toString(),
        depTwoInferAnalysisTarget.toString());

    // check that infer-related targets are getting rebuilt
    for (String t : locallyBuiltTargets) {
      buildLog.assertTargetBuiltLocally(t);
    }

    Set <String> builtFromCacheTargets = Sets.newHashSet(
        Iterables.transform(
            buildLog.getAllTargets(), new Function<BuildTarget, String>() {
              @Override
              public String apply(BuildTarget input) {
                return input.toString();
              }
            })
    );
    builtFromCacheTargets.removeAll(locallyBuiltTargets);

    // check that all the other targets are fetched from the cache
    for (String t : builtFromCacheTargets) {
      buildLog.assertTargetWasFetchedFromCache(t);
    }
  }

  @Test
  public void testInferCxxBinaryWithoutDeps() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(
        this,
        tmp,
        Optional.<String>absent());

    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(
        new CxxBuckConfig(FakeBuckConfig.builder().build()));
    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:simple");
    String inputBuildTargetName = inputBuildTarget
        .withFlavors(CxxInferEnhancer.InferFlavors.INFER.get())
        .getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that all the required build targets have been generated.
     */
    String sourceName = "simple.cpp";
    String sourceFull = "foo/" + sourceName;

    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(
        workspace.getDestPath(),
        inputBuildTarget,
        cxxPlatform);
    // this is unflavored, but bounded to the InferCapture build rule
    BuildTarget captureBuildTarget =
        cxxSourceRuleFactory.createInferCaptureBuildTarget(sourceName);
    // this is unflavored, but necessary to run the compiler successfully
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            inputBuildTarget,
            cxxPlatform.getFlavor(),
            HeaderVisibility.PRIVATE);
    // this is flavored, and denotes the analysis step (generates a local report)
    BuildTarget inferAnalysisTarget =
        inputBuildTarget.withFlavors(CxxInferEnhancer.InferFlavors.INFER_ANALYZE.get());

    // this is flavored and corresponds to the top level target (the one give in input to buck)
    BuildTarget inferReportTarget =
        inputBuildTarget.withFlavors(CxxInferEnhancer.InferFlavors.INFER.get());

    ImmutableSet<BuildTarget> expectedTargets = ImmutableSet.<BuildTarget>builder()
        .addAll(
            ImmutableSet.of(
                headerSymlinkTreeTarget,
                captureBuildTarget,
                inferAnalysisTarget,
                inferReportTarget))
        .build();

    BuckBuildLog buildLog = workspace.getBuildLog();
    assertEquals(expectedTargets, buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(captureBuildTarget.toString());
    buildLog.assertTargetBuiltLocally(inferAnalysisTarget.toString());
    buildLog.assertTargetBuiltLocally(inferReportTarget.toString());

    /*
     * Check that running a build again results in no builds since nothing has changed.
     */
    workspace.resetBuildLogFile(); // clear for new build
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(ImmutableSet.of(inferReportTarget), buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(inferReportTarget.toString());

    /*
     * Check that changing the source file results in running the capture/analysis rules again.
     */
    workspace.resetBuildLogFile();
    workspace.replaceFileContents(sourceFull, "*s = 42;", "");
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(
        expectedTargets,
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(captureBuildTarget.toString());
    buildLog.assertTargetBuiltLocally(inferAnalysisTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
  }

  @Test
  public void testInferCxxBinaryWithDeps() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(
        this,
        tmp,
        Optional.<String>absent());

    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(
        new CxxBuckConfig(FakeBuckConfig.builder().build()));
    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:binary_with_deps");
    String inputBuildTargetName = inputBuildTarget
        .withFlavors(CxxInferEnhancer.InferFlavors.INFER.get())
        .getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that all the required build targets have been generated.
     */
    String sourceName = "src_with_deps.c";
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(
        workspace.getDestPath(),
        inputBuildTarget,
        cxxPlatform);
    // 1. create the targets of binary_with_deps
    // this is unflavored, but bounded to the InferCapture build rule
    BuildTarget topCaptureBuildTarget =
        cxxSourceRuleFactory.createInferCaptureBuildTarget(sourceName);

    // this is unflavored, but necessary to run the compiler successfully
    BuildTarget topHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            inputBuildTarget,
            cxxPlatform.getFlavor(),
            HeaderVisibility.PRIVATE);

    // this is flavored, and denotes the analysis step (generates a local report)
    BuildTarget topInferAnalysisTarget = inputBuildTarget
        .withFlavors(CxxInferEnhancer.InferFlavors.INFER_ANALYZE.get());

    // this is flavored and corresponds to the top level target (the one give in input to buck)
    BuildTarget topInferReportTarget = inputBuildTarget
        .withFlavors(CxxInferEnhancer.InferFlavors.INFER.get());

    // 2. create the targets of dep_one
    BuildTarget depOneBuildTarget =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:dep_one");
    String depOneSourceName = "dep_one.c";
    String depOneSourceFull = "foo/" + depOneSourceName;
    CxxSourceRuleFactory depOneSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), depOneBuildTarget, cxxPlatform);

    BuildTarget depOneCaptureBuildTarget =
        depOneSourceRuleFactory.createInferCaptureBuildTarget(depOneSourceName);

    BuildTarget depOneHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depOneBuildTarget,
            cxxPlatform.getFlavor(),
            HeaderVisibility.PRIVATE);

    BuildTarget depOneExportedHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depOneBuildTarget,
            cxxPlatform.getFlavor(),
            HeaderVisibility.PUBLIC);

    BuildTarget depOneInferAnalysisTarget =
        depOneCaptureBuildTarget.withFlavors(
            cxxPlatform.getFlavor(),
            CxxInferEnhancer.InferFlavors.INFER_ANALYZE.get());

    // 3. create the targets of dep_two
    BuildTarget depTwoBuildTarget =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:dep_two");
    CxxSourceRuleFactory depTwoSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), depTwoBuildTarget, cxxPlatform);

    BuildTarget depTwoCaptureBuildTarget =
        depTwoSourceRuleFactory.createInferCaptureBuildTarget("dep_two.c");

    BuildTarget depTwoHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depTwoBuildTarget,
            cxxPlatform.getFlavor(),
            HeaderVisibility.PRIVATE);

    BuildTarget depTwoExportedHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depTwoBuildTarget,
            cxxPlatform.getFlavor(),
            HeaderVisibility.PUBLIC);

    BuildTarget depTwoInferAnalysisTarget =
        depTwoCaptureBuildTarget.withFlavors(
            cxxPlatform.getFlavor(),
            CxxInferEnhancer.InferFlavors.INFER_ANALYZE.get());

    // Check all the targets are in the buildLog
    assertEquals(
        ImmutableSet.of(
            topCaptureBuildTarget,
            topHeaderSymlinkTreeTarget,
            topInferAnalysisTarget,
            topInferReportTarget,
            depOneCaptureBuildTarget,
            depOneHeaderSymlinkTreeTarget,
            depOneExportedHeaderSymlinkTreeTarget,
            depOneInferAnalysisTarget,
            depTwoCaptureBuildTarget,
            depTwoHeaderSymlinkTreeTarget,
            depTwoExportedHeaderSymlinkTreeTarget,
            depTwoInferAnalysisTarget),
        workspace.getBuildLog().getAllTargets());

    /*
     * Check that running a build again results in no builds since nothing has changed.
     */
    workspace.resetBuildLogFile(); // clear for new build
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertEquals(ImmutableSet.of(topInferReportTarget), buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(topInferReportTarget.toString());

    /*
     * Check that if a library source file changes then the capture/analysis rules run again on
     * the main target and on dep_one only.
     */
    workspace.resetBuildLogFile();
    workspace.replaceFileContents(depOneSourceFull, "flag > 0", "flag < 0");
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(
            topInferAnalysisTarget, // analysis runs again
            topInferReportTarget, // report runs again
            topCaptureBuildTarget, // cached
            depTwoInferAnalysisTarget, // cached
            depOneCaptureBuildTarget, // capture of the changed file runs again
            depOneExportedHeaderSymlinkTreeTarget, // cached
            depOneHeaderSymlinkTreeTarget, // cached
            depOneInferAnalysisTarget), // analysis of the library runs again
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(topInferAnalysisTarget.toString());
    buildLog.assertTargetBuiltLocally(topInferReportTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(topCaptureBuildTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(depTwoInferAnalysisTarget.toString());
    buildLog.assertTargetBuiltLocally(depOneCaptureBuildTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(depOneExportedHeaderSymlinkTreeTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(depOneHeaderSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(depOneInferAnalysisTarget.toString());
  }

  @Test
  public void testInferCxxBinaryWithDepsEmitsAllTheDependenciesResultsDirs() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(
        this,
        tmp,
        Optional.<String>absent());

    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_chain_deps")
        .withFlavors(CxxInferEnhancer.InferFlavors.INFER.get());

    // Build the given target and check that it succeeds.
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildTargets.getGenPath(inputBuildTarget, "infer-%s/infer-deps.txt"))));

    String loggedDeps = workspace.getFileContents(
        BuildTargets.getGenPath(inputBuildTarget, "infer-%s/infer-deps.txt"));

    String sanitizedChainDepOne = sanitize("chain_dep_one.c.o");
    String sanitizedTopChain = sanitize("top_chain.c.o");
    String sanitizedChainDepTwo = sanitize("chain_dep_two.c.o");

    BuildTarget analyzeTopChainTarget = BuildTargetFactory.newInstance(
        "//foo:binary_with_chain_deps#infer-analyze");
    BuildTarget captureTopChainTarget = BuildTargetFactory.newInstance(
        "//foo:binary_with_chain_deps#default,infer-capture-" + sanitizedTopChain);
    BuildTarget analyzeChainDepOneTarget = BuildTargetFactory.newInstance(
        "//foo:chain_dep_one#default,infer-analyze");
    BuildTarget captureChainDepOneTarget = BuildTargetFactory.newInstance(
        "//foo:chain_dep_one#default,infer-capture-" + sanitizedChainDepOne);
    BuildTarget analyzeChainDepTwoTarget = BuildTargetFactory.newInstance(
        "//foo:chain_dep_two#default,infer-analyze");
    BuildTarget captureChainDepTwoTarget = BuildTargetFactory.newInstance(
        "//foo:chain_dep_two#default,infer-capture-" + sanitizedChainDepTwo);
    String expectedOutput = Joiner.on('\n').join(
        ImmutableList.of(
            analyzeTopChainTarget.getFullyQualifiedName() + "\t" +
                "[infer-analyze]\t" +
                BuildTargets.getGenPath(analyzeTopChainTarget, "infer-analysis-%s"),
            captureTopChainTarget.getFullyQualifiedName() + "\t" +
                "[default, infer-capture-" + sanitizedTopChain + "]\t" +
                BuildTargets.getGenPath(captureTopChainTarget, "infer-out-%s"),
            analyzeChainDepOneTarget.getFullyQualifiedName() + "\t" +
                "[default, infer-analyze]\t" +
                BuildTargets.getGenPath(analyzeChainDepOneTarget, "infer-analysis-%s"),
            captureChainDepOneTarget.getFullyQualifiedName() + "\t" +
                "[default, infer-capture-" + sanitizedChainDepOne + "]\t" +
                BuildTargets.getGenPath(captureChainDepOneTarget, "infer-out-%s"),
            analyzeChainDepTwoTarget.getFullyQualifiedName() + "\t" +
                "[default, infer-analyze]\t" +
                BuildTargets.getGenPath(analyzeChainDepTwoTarget, "infer-analysis-%s"),
            captureChainDepTwoTarget.getFullyQualifiedName() + "\t" +
                "[default, infer-capture-" + sanitizedChainDepTwo + "]\t" +
                BuildTargets.getGenPath(captureChainDepTwoTarget, "infer-out-%s")));

    assertEquals(expectedOutput + "\n", loggedDeps);
  }

  @Test
  public void testInferCxxBinaryWithDiamondDepsHasRuntimeDepsOfAllCaptureRulesWhenCacheHits(
  ) throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(
        this,
        tmp,
        Optional.<String>absent());
    workspace.enableDirCache(); // enable the cache

    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance(
        "//foo:binary_with_diamond_deps");
    String inputBuildTargetName = inputBuildTarget
        .withFlavors(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.get())
        .getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    for (BuildTarget buildTarget : buildLog.getAllTargets()) {
      buildLog.assertTargetWasFetchedFromCache(buildTarget.toString());
    }

    /*
    * Check that runtime deps have been fetched from cache as well
    */
    assertTrue(
        "This file was expected to exist because it's declared as runtime dep",
        Files.exists(
            workspace.getPath(
                BuildTargets
                    .getGenPath(
                        BuildTargetFactory.newInstance(
                            "//foo:simple_lib#default,infer-capture-" +
                                sanitize("simple.cpp.o")),
                        "infer-out-%s")
                    .resolve("captured/simple.cpp_captured/simple.cpp.cfg"))));
    assertTrue(
        "This file was expected to exist because it's declared as runtime dep",
        Files.exists(
            workspace.getPath(
                BuildTargets
                    .getGenPath(
                        BuildTargetFactory.newInstance(
                            "//foo:diamond_dep_one#default,infer-capture-" +
                                sanitize("dep_one.c.o")),
                        "infer-out-%s")
                    .resolve("captured/dep_one.c_captured/dep_one.c.cfg"))));
    assertTrue(
        "This file was expected to exist because it's declared as runtime dep",
        Files.exists(
            workspace.getPath(
                BuildTargets
                    .getGenPath(
                        BuildTargetFactory.newInstance(
                            "//foo:diamond_dep_two#default,infer-capture-" +
                                sanitize("dep_two.c.o")),
                        "infer-out-%s")
                    .resolve("captured/dep_two.c_captured/dep_two.c.cfg"))));
    assertTrue(
        "This file was expected to exist because it's declared as runtime dep",
        Files.exists(
            workspace.getPath(
                BuildTargets
                    .getGenPath(
                        BuildTargetFactory.newInstance(
                            "//foo:binary_with_diamond_deps#default,infer-capture-" +
                                sanitize("src_with_deps.c.o")),
                        "infer-out-%s")
                    .resolve("captured/src_with_deps.c_captured/src_with_deps.c.cfg"))));
  }

  @Test
  public void testInferCxxBinaryWithDiamondDepsEmitsAllTransitiveCaptureRulesOnce(
  ) throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(
        this,
        tmp,
        Optional.<String>absent());

    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance(
        "//foo:binary_with_diamond_deps")
        .withFlavors(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.get());

    // Build the given target and check that it succeeds.
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildTargets.getGenPath(inputBuildTarget, "infer-%s/infer-deps.txt"))));

    String loggedDeps = workspace.getFileContents(
        BuildTargets.getGenPath(inputBuildTarget, "infer-%s/infer-deps.txt"));

    String sanitizedSimpleCpp = sanitize("simple.cpp.o");
    String sanitizedDepOne = sanitize("dep_one.c.o");
    String sanitizedDepTwo = sanitize("dep_two.c.o");
    String sanitizedSrcWithDeps = sanitize("src_with_deps.c.o");
    BuildTarget simpleCppTarget = BuildTargetFactory.newInstance(
        "//foo:simple_lib#default,infer-capture-" + sanitizedSimpleCpp);
    BuildTarget depOneTarget = BuildTargetFactory.newInstance(
        "//foo:diamond_dep_one#default,infer-capture-" + sanitizedDepOne);
    BuildTarget depTwoTarget = BuildTargetFactory.newInstance(
        "//foo:diamond_dep_two#default,infer-capture-" + sanitizedDepTwo);
    BuildTarget srcWithDepsTarget = BuildTargetFactory.newInstance(
        "//foo:binary_with_diamond_deps#default,infer-capture-" + sanitizedSrcWithDeps);


    String expectedOutput = Joiner.on('\n').join(
        ImmutableList.of(
            srcWithDepsTarget.getFullyQualifiedName() + "\t" +
                "[default, infer-capture-" + sanitizedSrcWithDeps + "]\t" +
                BuildTargets.getGenPath(srcWithDepsTarget, "infer-out-%s"),
            depOneTarget.getFullyQualifiedName() + "\t" +
                "[default, infer-capture-" + sanitizedDepOne + "]\t" +
                BuildTargets.getGenPath(depOneTarget, "infer-out-%s"),
            depTwoTarget.getFullyQualifiedName() + "\t" +
                "[default, infer-capture-" + sanitizedDepTwo + "]\t" +
                BuildTargets.getGenPath(depTwoTarget, "infer-out-%s"),
            simpleCppTarget.getFullyQualifiedName() + "\t" +
                "[default, infer-capture-" + sanitizedSimpleCpp + "]\t" +
                BuildTargets.getGenPath(simpleCppTarget, "infer-out-%s")));

    assertEquals(expectedOutput + "\n", loggedDeps);
  }

  @Test
  public void testInferCxxBinarySkipsBlacklistedFiles() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(
        this,
        tmp,
        Optional.of(".*one\\.c"));

    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_chain_deps");
    String inputBuildTargetName = inputBuildTarget
        .withFlavors(CxxInferEnhancer.InferFlavors.INFER.get())
        .getFullyQualifiedName();

    // Build the given target and check that it succeeds.
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    // Check that the cfg associated with chain_dep_one.c does not exist
    assertFalse(
        "Cfg file for chain_dep_one.c should not exist",
        Files.exists(
            workspace.getPath(
                BuildTargets
                    .getGenPath(
                        BuildTargetFactory.newInstance(
                            "//foo:chain_dep_one#default,infer-capture-" +
                                sanitize("chain_dep_one.c.o")),
                        "infer-out-%s")
                    .resolve("captured/chain_dep_one.c_captured/chain_dep_one.c.cfg"))));

    // Check that the remaining files still have their cfgs
    assertTrue(
        "Expected cfg for chain_dep_two.c not found",
        Files.exists(
            workspace.getPath(
                BuildTargets
                    .getGenPath(
                        BuildTargetFactory.newInstance(
                            "//foo:chain_dep_two#default,infer-capture-" +
                                sanitize("chain_dep_two.c.o")),
                        "infer-out-%s")
                    .resolve("captured/chain_dep_two.c_captured/chain_dep_two.c.cfg"))));
    assertTrue(
        "Expected cfg for top_chain.c not found",
        Files.exists(
            workspace.getPath(
                BuildTargets
                    .getGenPath(
                        BuildTargetFactory.newInstance(
                            "//foo:binary_with_chain_deps#infer-analyze"),
                        "infer-analysis-%s")
                    .resolve("captured/top_chain.c_captured/top_chain.c.cfg"))));
  }

  @Test
  public void testInferCxxBinaryRunsOnAllFilesWhenBlacklistIsNotSpecified() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(
        this,
        tmp,
        Optional.<String>absent());

    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_chain_deps");
    String inputBuildTargetName = inputBuildTarget
        .withFlavors(CxxInferEnhancer.InferFlavors.INFER.get())
        .getFullyQualifiedName();

    // Build the given target and check that it succeeds.
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    // Check that all cfgs have been created
    assertTrue(
        "Expected cfg for chain_dep_one.c not found",
        Files.exists(
            workspace.getPath(
                BuildTargets
                    .getGenPath(
                        BuildTargetFactory.newInstance(
                            "//foo:chain_dep_one#default,infer-capture-" +
                                sanitize("chain_dep_one.c.o")),
                        "infer-out-%s")
                    .resolve("captured/chain_dep_one.c_captured/chain_dep_one.c.cfg"))));
    assertTrue(
        "Expected cfg for chain_dep_two.c not found",
        Files.exists(
            workspace.getPath(
                BuildTargets
                    .getGenPath(
                        BuildTargetFactory.newInstance(
                            "//foo:chain_dep_two#default,infer-capture-" +
                                sanitize("chain_dep_two.c.o")),
                        "infer-out-%s")
                    .resolve("captured/chain_dep_two.c_captured/chain_dep_two.c.cfg"))));
    assertTrue(
        "Expected cfg for top_chain.c not found",
        Files.exists(
            workspace.getPath(
                BuildTargets
                    .getGenPath(
                        BuildTargetFactory.newInstance(
                            "//foo:binary_with_chain_deps#infer-analyze"),
                        "infer-analysis-%s")
                    .resolve("captured/top_chain.c_captured/top_chain.c.cfg"))));
  }

  @Test
  public void testInferCxxBinaryWithCachedDepsGetsAllItsTransitiveDeps() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(
        this,
        tmp,
        Optional.<String>absent());
    workspace.enableDirCache(); // enable the cache

    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_chain_deps")
        .withFlavors(CxxInferEnhancer.InferFlavors.INFER.get());

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    for (BuildTarget buildTarget : buildLog.getAllTargets()) {
      buildLog.assertTargetWasFetchedFromCache(buildTarget.toString());
    }

    /*
     * Check that if the file in the top target changes, then all the transitive deps will be
     * fetched from the cache (even those that are not direct dependencies).
     * Make sure there's the specs file of the dependency that has distance 2 from
     * the binary target.
     */
    String sourceName = "top_chain.c";
    workspace.replaceFileContents("foo/" + sourceName, "*p += 1", "*p += 10");
    workspace.runBuckCommand("clean").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();

    // Check all the buildrules were fetched from the cache (and there's the specs file)
    assertTrue(
        "Expected specs file for func_ret_null() in chain_dep_two.c not found",
        Files.exists(
            workspace.getPath(
                BuildTargets
                    .getGenPath(
                        BuildTargetFactory.newInstance(
                            "//foo:chain_dep_two#default,infer-analyze"),
                        "infer-analysis-%s/specs/mockedSpec.specs"))));
  }

  @Test
  public void testInferCxxBinaryMergesAllReportsOfDependencies() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(
        this,
        tmp,
        Optional.<String>absent());

    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_chain_deps")
        .withFlavors(CxxInferEnhancer.InferFlavors.INFER.get());

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();

    String reportPath =
        BuildTargets.getGenPath(inputBuildTarget, "infer-%s/report.json").toString();
    List<Object> bugs = InferHelper.loadInferReport(workspace, reportPath);

    // check that the merge step has merged a total of 3 bugs, one for each target
    // (chain_dep_two, chain_dep_one, binary_with_chain_deps)
    Assert.assertThat(
        "3 bugs expected in " + reportPath + " not found",
        bugs.size(),
        Matchers.equalTo(3));
  }

  @Test
  public void testInferCxxBinaryWritesSpecsListFilesOfTransitiveDependencies() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(
        this,
        tmp,
        Optional.<String>absent());

    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_chain_deps")
        .withFlavors(CxxInferEnhancer.InferFlavors.INFER.get());

    //Build the given target and check that it succeeds.
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();

    String specsPathList = BuildTargets
        .getGenPath(
            inputBuildTarget.withFlavors(CxxInferEnhancer.InferFlavors.INFER_ANALYZE.get()),
            "infer-analysis-%s/specs_path_list.txt")
        .toString();
    String out = workspace.getFileContents(specsPathList);

    ImmutableList<Path> paths = FluentIterable.of(out.split("\n")).transform(
        new Function<String, Path>() {
          @Override
          public Path apply(String input) {
            return new File(input).toPath();
          }
        }).toList();

    assertSame("There must be 2 paths in total", paths.size(), 2);

    for (Path path : paths) {
      assertTrue("Path must be absolute", path.isAbsolute());
      assertTrue("Path must exist", Files.exists(path));
    }
  }

  public void doTestSimpleCxxBinaryBuilds(
      String preprocessMode,
      boolean expectPreprocessorOutput) throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple", tmp);
    workspace.setUp();
    workspace.writeContentsToPath(
        String.format("[cxx]\npreprocess_mode = %s\n", preprocessMode),
        ".buckconfig");
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(
        new CxxBuckConfig(FakeBuckConfig.builder().build()));
    BuildTarget target = BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:simple");
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(
        workspace.getDestPath(),
        target,
        cxxPlatform);
    BuildTarget binaryTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target);
    String sourceName = "simple.cpp";
    String sourceFull = "foo/" + sourceName;
    BuildTarget preprocessTarget =
        cxxSourceRuleFactory.createPreprocessBuildTarget(sourceName, CxxSource.Type.CXX);
    BuildTarget compileTarget = cxxSourceRuleFactory.createCompileBuildTarget(sourceName);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            target,
            cxxPlatform.getFlavor(),
            HeaderVisibility.PRIVATE);

    // Do a clean build, verify that it succeeds, and check that all expected targets built
    // successfully.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    ImmutableSet<BuildTarget> expectedTargets = ImmutableSet.<BuildTarget>builder()
        .addAll(
            ImmutableSet.of(
                headerSymlinkTreeTarget,
                compileTarget,
                binaryTarget,
                target))
        .addAll(
            (expectPreprocessorOutput
                ? ImmutableSet.of(preprocessTarget)
                : ImmutableSet.<BuildTarget>of()))
        .build();

    assertEquals(expectedTargets, buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget.toString());
    if (expectPreprocessorOutput) {
      buildLog.assertTargetBuiltLocally(preprocessTarget.toString());
    }
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
        ImmutableSet.of(target, binaryTarget),
        buildLog.getAllTargets());
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
    assertEquals(expectedTargets, buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    if (expectPreprocessorOutput) {
      buildLog.assertTargetBuiltLocally(preprocessTarget.toString());
    }
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
    assertThat(
        buildLog.getLogEntry(binaryTarget).getSuccessType().get(),
        Matchers.not(Matchers.equalTo(BuildRuleSuccessType.MATCHING_RULE_KEY)));
    buildLog.assertTargetBuiltLocally(target.toString());

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(sourceFull, "{ return 0; }", "won't compile");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertFailure();
    buildLog = workspace.getBuildLog();
    assertEquals(expectedTargets, buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    if (expectPreprocessorOutput) {
      buildLog.assertTargetBuiltLocally(preprocessTarget.toString());
    }
    assertThat(
        buildLog.getLogEntry(binaryTarget).getStatus(),
        Matchers.equalTo(BuildRuleStatus.CANCELED));
    assertThat(
        buildLog.getLogEntry(target).getStatus(),
        Matchers.equalTo(BuildRuleStatus.CANCELED));
  }

  @Test
  public void testSimpleCxxBinaryBuildsInSeparateMode() throws IOException {
    doTestSimpleCxxBinaryBuilds("separate", true /* expectPreprocessorOutput */);
  }

  @Test
  public void testSimpleCxxBinaryBuildsInPipedMode() throws IOException {
    doTestSimpleCxxBinaryBuilds("piped", false /* expectPreprocessorOutput */);
  }

  @Test
  public void testSimpleCxxBinaryBuildsInCombinedMode() throws IOException {
    doTestSimpleCxxBinaryBuilds("combined", false /* expectPreprocessorOutput */);
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

    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(
        new CxxBuckConfig(FakeBuckConfig.builder().build()));
    BuildTarget target =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:simple_with_header");
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(
        workspace.getDestPath(),
        target,
        cxxPlatform);
    BuildTarget binaryTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target);
    String sourceName = "simple_with_header.cpp";
    String headerName = "simple_with_header.h";
    String headerFull = "foo/" + headerName;
    BuildTarget preprocessTarget =
        cxxSourceRuleFactory.createPreprocessBuildTarget(sourceName, CxxSource.Type.CXX);
    BuildTarget compileTarget =
        cxxSourceRuleFactory.createCompileBuildTarget(sourceName);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            target,
            cxxPlatform.getFlavor(),
            HeaderVisibility.PRIVATE);

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
    workspace.replaceFileContents(headerFull, "blah = 5", "blah = 6");

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
    assertThat(
        buildLog.getLogEntry(binaryTarget).getSuccessType().get(),
        Matchers.not(Matchers.equalTo(BuildRuleSuccessType.MATCHING_RULE_KEY)));
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
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(
        new CxxBuckConfig(FakeBuckConfig.builder().build()));
    BuildTarget target =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:binary_with_dep");
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(
        workspace.getDestPath(),
        target,
        cxxPlatform);
    BuildTarget binaryTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target);
    String sourceName = "foo.cpp";
    BuildTarget preprocessTarget =
        cxxSourceRuleFactory.createPreprocessBuildTarget(sourceName, CxxSource.Type.CXX);
    BuildTarget compileTarget = cxxSourceRuleFactory.createCompileBuildTarget(sourceName);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            target,
            cxxPlatform.getFlavor(),
            HeaderVisibility.PRIVATE);

    // Setup variables pointing to the sources and targets of the library dep.
    BuildTarget depTarget =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:library_with_header");
    CxxSourceRuleFactory depCxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), depTarget, cxxPlatform);
    String depSourceName = "bar.cpp";
    String depSourceFull = "foo/" + depSourceName;
    String depHeaderName = "bar.h";
    String depHeaderFull = "foo/" + depHeaderName;
    BuildTarget depPreprocessTarget =
        depCxxSourceRuleFactory.createPreprocessBuildTarget(depSourceName, CxxSource.Type.CXX);
    BuildTarget depCompileTarget =
        depCxxSourceRuleFactory.createCompileBuildTarget(depSourceName);
    BuildTarget depHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depTarget,
            cxxPlatform.getFlavor(),
            HeaderVisibility.PRIVATE);
    BuildTarget depHeaderExportedSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depTarget,
            cxxPlatform.getFlavor(),
            HeaderVisibility.PUBLIC);
    BuildTarget depArchiveTarget =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            depTarget,
            cxxPlatform.getFlavor(),
            CxxSourceRuleFactory.PicType.PDC);

    // Do a clean build, verify that it succeeds, and check that all expected targets built
    // successfully.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(
            depHeaderSymlinkTreeTarget,
            depHeaderExportedSymlinkTreeTarget,
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
            depHeaderExportedSymlinkTreeTarget,
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
    buildLog.assertTargetHadMatchingRuleKey(depHeaderExportedSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(depPreprocessTarget.toString());
    buildLog.assertTargetBuiltLocally(depCompileTarget.toString());
    buildLog.assertTargetHadMatchingInputRuleKey(depArchiveTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(depTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(preprocessTarget.toString());
    buildLog.assertTargetBuiltLocally(compileTarget.toString());
    assertThat(
        buildLog.getLogEntry(binaryTarget).getSuccessType().get(),
        Matchers.not(Matchers.equalTo(BuildRuleSuccessType.MATCHING_RULE_KEY)));
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
            depHeaderExportedSymlinkTreeTarget,
            depPreprocessTarget,
            depCompileTarget,
            depArchiveTarget,
            depTarget,
            compileTarget,
            binaryTarget,
            target),
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(depHeaderSymlinkTreeTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(depHeaderExportedSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(depPreprocessTarget.toString());
    buildLog.assertTargetBuiltLocally(depCompileTarget.toString());
    buildLog.assertTargetBuiltLocally(depArchiveTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(depTarget.toString());
    buildLog.assertTargetHadMatchingRuleKey(compileTarget.toString());
    buildLog.assertTargetBuiltLocally(binaryTarget.toString());
    buildLog.assertTargetBuiltLocally(target.toString());
  }

  @Test
  public void testCxxBinaryDepfileBuildWithChangedHeader() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "cxx_binary_depfile_build_with_changed_header", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result =  workspace.runBuckCommand("build", "//:bin");
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:bin#binary");
    buildLog.assertTargetBuiltLocally("//:bin#compile-" + sanitize("bin.c.o") + ",default");
    buildLog.assertTargetBuiltLocally("//:lib1#default,static");

    workspace.resetBuildLogFile();

    workspace.replaceFileContents("lib2.h", "hello", "world");

    result = workspace.runBuckCommand("build", "//:bin");
    result.assertSuccess();

    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:bin#binary");
    buildLog.assertTargetHadMatchingDepfileRuleKey(
        "//:bin#compile-" + sanitize("bin.c.o") + ",default");
    buildLog.assertTargetBuiltLocally("//:lib1#default,static");
  }

  @Test
  public void testCxxBinaryDepfileBuildWithAddedHeader() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "cxx_binary_depfile_build_with_added_header", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result =  workspace.runBuckCommand("build", "//:bin");
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:bin#binary");
    buildLog.assertTargetBuiltLocally("//:bin#compile-" + sanitize("bin.c.o") + ",default");
    buildLog.assertTargetBuiltLocally("//:lib1#default,static");

    workspace.resetBuildLogFile();

    workspace.replaceFileContents("BUCK", "['lib1.h']", "['lib1.h', 'lib2.h']");

    result = workspace.runBuckCommand("build", "//:bin");
    result.assertSuccess();

    buildLog = workspace.getBuildLog();
    buildLog.assertTargetHadMatchingInputRuleKey("//:bin#binary");
    buildLog.assertTargetHadMatchingDepfileRuleKey(
        "//:bin#compile-" + sanitize("bin.c.o") + ",default");
    buildLog.assertTargetHadMatchingInputRuleKey("//:lib1#default,static");
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
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(new CxxBuckConfig(buckConfig));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "resolved", tmp);
    workspace.setUp();

    workspace.writeContentsToPath("", "lib2.h");

    BuildTarget target = BuildTargetFactory.newInstance("//:bin");
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(
        workspace.getDestPath(),
        target,
        cxxPlatform);
    workspace.runBuckCommand("build", target.toString()).assertSuccess();

    // Verify that the preprocessed source contains no references to the symlink tree used to
    // setup the headers.
    BuildTarget ppTarget =
        cxxSourceRuleFactory.createPreprocessBuildTarget("bin.cpp", CxxSource.Type.CXX);
    Path output =
        cxxSourceRuleFactory.getPreprocessOutputPath(
            ppTarget,
            CxxSource.Type.CXX,
            "bin.cpp");
    String contents = workspace.getFileContents(output.toString());
    assertThat(contents, Matchers.not(Matchers.containsString(BuckConstant.SCRATCH_DIR)));
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
    assertThat(error, Matchers.not(Matchers.containsString(BuckConstant.SCRATCH_DIR)));
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
    workspace.runBuckBuild("//:binary_with_library_dep").assertSuccess();
    workspace.runBuckBuild("//:binary_with_exported_flags_library_dep").assertFailure("--bad-flag");
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
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:binary_no_match_" + target);
    result.assertFailure();
    assertThat(result.getStderr(), Matchers.containsString("reference"));
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

  @Test
  public void perFileFlagsUsedForPreprocessing() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "preprocessing_per_file_flags", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:bin");
    result.assertSuccess();
  }

  @Test
  public void correctPerFileFlagsUsedForCompilation() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "compiling_per_file_flags", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:working-bin");
    result.assertSuccess();
  }

  @Test
  public void incorrectPerFileFlagsUsedForCompilation() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "compiling_per_file_flags", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:broken-bin");
    result.assertFailure();
  }

  @Test
  public void platformPreprocessorFlags() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "platform_preprocessor_flags", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:binary_matches_default_exactly").assertSuccess();
    workspace.runBuckBuild("//:binary_matches_default").assertSuccess();
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:binary_no_match");
    result.assertFailure();
    assertThat(result.getStderr(), Matchers.containsString("#error"));
    workspace.runBuckBuild("//:binary_with_library_matches_default").assertSuccess();
  }

  @Test
  public void platformCompilerFlags() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "platform_compiler_flags", tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[cxx]\n  cxxflags = -Wall -Werror", ".buckconfig");
    workspace.runBuckBuild("//:binary_matches_default_exactly").assertSuccess();
    workspace.runBuckBuild("//:binary_matches_default").assertSuccess();
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:binary_no_match");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.allOf(
            Matchers.containsString("non-void"),
            Matchers.containsString("function")));
    workspace.runBuckBuild("//:binary_with_library_matches_default").assertSuccess();
  }

  @Test
  public void platformHeaders() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "platform_headers", tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[cxx]\n  cxxflags = -Wall -Werror", ".buckconfig");
    workspace.runBuckBuild("//:binary_matches_default_exactly").assertSuccess();
    workspace.runBuckBuild("//:binary_matches_default").assertSuccess();
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:binary_no_match");
    result.assertFailure();
    assertThat(result.getStderr(), Matchers.containsString("header.hpp"));
    workspace.runBuckBuild("//:binary_with_library_matches_default").assertSuccess();
  }

  @Test
  public void platformSources() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "platform_sources", tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[cxx]\n  cxxflags = -Wall -Werror", ".buckconfig");
    workspace.runBuckBuild("//:binary_matches_default_exactly").assertSuccess();
    workspace.runBuckBuild("//:binary_matches_default").assertSuccess();
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:binary_no_match");
    result.assertFailure();
    assertThat(result.getStderr(), Matchers.containsString("answer()"));
    workspace.runBuckBuild("//:binary_with_library_matches_default").assertSuccess();
  }

  @Test
  public void buildABinaryIfACxxLibraryDepOnlyDeclaresHeaders() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "cxx_binary_headers_only", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:binary");

    result.assertSuccess();
  }

  @Test
  public void buildABinaryIfACxxBinaryTransitivelyDepOnlyDeclaresHeaders() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "cxx_binary_headers_only", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:transitive");
    System.out.println(result.getStdout());
    System.err.println(result.getStderr());

    result.assertSuccess();
  }

  @Test
  public void buildBinaryWithSharedDependencies() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "shared_library", tmp);
    workspace.setUp();
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:binary");
    result.assertSuccess();
  }

  @Test
  public void buildBinaryWithPerFileFlags() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "per_file_flags", tmp);
    workspace.setUp();
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:binary");
    result.assertSuccess();
  }

  @Test
  public void runBinaryUsingSharedLinkStyle() throws IOException {
    assumeThat(Platform.detect(), oneOf(Platform.LINUX, Platform.MACOS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "shared_link_style", tmp);
    workspace.setUp();
    workspace.runBuckCommand("run", "//:bar").assertSuccess();
  }

  @Test
  public void genruleUsingBinaryUsingSharedLinkStyle() throws IOException {
    assumeThat(Platform.detect(), oneOf(Platform.LINUX, Platform.MACOS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "shared_link_style", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:gen").assertSuccess();
  }

  @Test
  public void shBinaryAsLinker() throws IOException {
    assumeThat(Platform.detect(), oneOf(Platform.LINUX, Platform.MACOS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "step_test", tmp);
    workspace.setUp();
    workspace.runBuckBuild("-c", "cxx.ld=//:cxx", "//:binary_with_unused_header").assertSuccess();
  }

  @Test
  public void buildBinaryUsingStaticPicLinkStyle() throws IOException {
    assumeThat(Platform.detect(), oneOf(Platform.LINUX, Platform.MACOS));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "static_pic_link_style", tmp);
    workspace.setUp();
    workspace.runBuckCommand(
        "build",
        // This should only work (on some architectures) if PIC was used to build all included
        // object files.
        "--config", "cxx.cxxldflags=-shared",
        "//:bar")
        .assertSuccess();
  }

  @Test
  public void testStrippedBinaryProducesBothUnstrippedAndStrippedOutputs()
      throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    BuildTarget unstrippedTarget = BuildTargetFactory.newInstance("//:test");
    BuildTarget strippedTarget = unstrippedTarget.withFlavors(
        StripStyle.DEBUGGING_SYMBOLS.getFlavor());

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "header_namespace", tmp);
    workspace.setUp();
    workspace.runBuckCommand(
        "build",
        "--config", "cxx.cxxflags=-g",
        strippedTarget.getFullyQualifiedName()).assertSuccess();

    Path strippedPath = workspace.getPath(BuildTargets.getGenPath(
        BuildTarget.builder(strippedTarget)
            .addFlavors(CxxStrip.RULE_FLAVOR)
            .build(),
        "%s"));
    Path unstrippedPath = workspace.getPath(BuildTargets.getGenPath(unstrippedTarget, "%s"));

    String strippedOut = workspace.runCommand("dsymutil", "-s", strippedPath.toString())
        .getStdout().or("");
    String unstrippedOut = workspace.runCommand("dsymutil", "-s", unstrippedPath.toString())
        .getStdout().or("");

    assertThat(strippedOut, Matchers.containsStringIgnoringCase("dyld_stub_binder"));
    assertThat(unstrippedOut, Matchers.containsStringIgnoringCase("dyld_stub_binder"));

    assertThat(strippedOut, Matchers.not(Matchers.containsStringIgnoringCase("test.cpp")));
    assertThat(unstrippedOut, Matchers.containsStringIgnoringCase("test.cpp"));
  }

  @Test
  public void testStrippedBinaryCanBeFetchedFromCacheAlone()
      throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);

    BuildTarget strippedTarget = BuildTargetFactory.newInstance("//:test")
        .withFlavors(StripStyle.DEBUGGING_SYMBOLS.getFlavor());
    BuildTarget unstrippedTarget = strippedTarget.withoutFlavors(
        StripStyle.FLAVOR_DOMAIN.getFlavors());

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "header_namespace", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    workspace.runBuckCommand(
        "build",
        "--config", "cxx.cxxflags=-g",
        strippedTarget.getFullyQualifiedName()).assertSuccess();
    workspace.runBuckCommand("clean").assertSuccess();
    workspace.runBuckCommand(
        "build",
        "--config", "cxx.cxxflags=-g",
        strippedTarget.getFullyQualifiedName()).assertSuccess();

    Path strippedPath = workspace.getPath(BuildTargets.getGenPath(
        BuildTarget.builder(strippedTarget)
            .addFlavors(CxxStrip.RULE_FLAVOR)
            .build(),
        "%s"));
    Path unstrippedPath = workspace.getPath(BuildTargets.getGenPath(unstrippedTarget, "%s"));

    assertThat(Files.exists(strippedPath), Matchers.equalTo(true));
    assertThat(Files.exists(unstrippedPath), Matchers.equalTo(false));
  }

  @Test
  public void testStrippedBinaryOutputDiffersFromUnstripped()
      throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    BuildTarget unstrippedTarget = BuildTargetFactory.newInstance("//:test");
    BuildTarget strippedTarget = unstrippedTarget.withFlavors(
        StripStyle.DEBUGGING_SYMBOLS.getFlavor());

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "header_namespace", tmp);
    workspace.setUp();
    ProjectWorkspace.ProcessResult strippedResult = workspace.runBuckCommand(
        "targets", "--show-output", strippedTarget.getFullyQualifiedName());
    strippedResult.assertSuccess();

    ProjectWorkspace.ProcessResult unstrippedResult = workspace.runBuckCommand(
        "targets", "--show-output", unstrippedTarget.getFullyQualifiedName());
    unstrippedResult.assertSuccess();

    String strippedOutput = strippedResult.getStdout().split(" ")[1];
    String unstrippedOutput = unstrippedResult.getStdout().split(" ")[1];
    assertThat(strippedOutput, Matchers.not(Matchers.equalTo(unstrippedOutput)));
  }

}
