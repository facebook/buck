/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx;

import static com.facebook.buck.cxx.CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL;
import static com.facebook.buck.cxx.toolchain.CxxFlavorSanitizer.sanitize;
import static com.facebook.buck.cxx.toolchain.CxxPlatformUtils.DEFAULT_DOWNWARD_API_CONFIG;
import static java.io.File.pathSeparator;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.InferHelper;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class CxxBinaryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));
  }

  @Test
  public void testInferCxxBinaryDepsCaching() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.enableDirCache();

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig, DEFAULT_DOWNWARD_API_CONFIG);
    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_deps");
    String inputBuildTargetName =
        inputBuildTarget.withFlavors(INFER_CAPTURE_ALL.getFlavor()).getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    for (BuildTarget buildTarget : buildLog.getAllTargets()) {
      buildLog.assertTargetWasFetchedFromCache(buildTarget);
    }

    /*
     * Check that if the file in the binary target changes, then all the deps will be fetched
     * from the cache
     */
    String sourceName = "src_with_deps.c";
    workspace.replaceFileContents("foo/" + sourceName, "10", "30");
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();

    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), inputBuildTarget, cxxPlatform, cxxBuckConfig);

    BuildTarget captureBuildTarget = cxxSourceRuleFactory.createInferCaptureBuildTarget(sourceName);

    // this is flavored, and denotes the analysis step (generates a local report)
    BuildTarget inferAnalysisTarget = inputBuildTarget.withFlavors(INFER_CAPTURE_ALL.getFlavor());

    // this is the flavored version of the top level target (the one give in input to buck)
    BuildTarget inferReportTarget = inputBuildTarget.withFlavors(INFER_CAPTURE_ALL.getFlavor());

    BuildTarget aggregatedDepsTarget =
        cxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    String bt;
    for (BuildTarget buildTarget : buildLog.getAllTargets()) {
      bt = buildTarget.toString();
      if (buildTarget
              .getFlavors()
              .contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
          || buildTarget.getFlavors().contains(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR)
          || bt.equals(inferAnalysisTarget.toString())
          || bt.equals(captureBuildTarget.toString())
          || bt.equals(inferReportTarget.toString())
          || bt.equals(aggregatedDepsTarget.toString())) {
        buildLog.assertTargetBuiltLocally(bt);
      } else {
        buildLog.assertTargetWasFetchedFromCache(buildTarget);
      }
    }
  }

  @Test
  @Ignore
  public void testInferCxxBinaryDepsInvalidateCacheWhenVersionChanges() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.enableDirCache();

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig, DEFAULT_DOWNWARD_API_CONFIG);
    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_deps");
    String inputBuildTargetName =
        inputBuildTarget.withFlavors(INFER_CAPTURE_ALL.getFlavor()).getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    for (BuildTarget buildTarget : buildLog.getAllTargets()) {
      buildLog.assertTargetWasFetchedFromCache(buildTarget);
    }

    /*
     * Check that if the version of infer changes, then all the infer-related targets are
     * recomputed
     */
    workspace.resetBuildLogFile();
    workspace.replaceFileContents("fake-infer/fake-bin/infer", "0.12345", "9.9999");
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();

    String sourceName = "src_with_deps.c";
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), inputBuildTarget, cxxPlatform, cxxBuckConfig);

    BuildTarget topCaptureBuildTarget =
        cxxSourceRuleFactory.createInferCaptureBuildTarget(sourceName);

    BuildTarget topInferReportTarget = inputBuildTarget.withFlavors(INFER_CAPTURE_ALL.getFlavor());

    BuildTarget depOneBuildTarget = BuildTargetFactory.newInstance("//foo:dep_one");
    String depOneSourceName = "dep_one.c";
    CxxSourceRuleFactory depOneSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), depOneBuildTarget, cxxPlatform, cxxBuckConfig);

    BuildTarget depOneCaptureBuildTarget =
        depOneSourceRuleFactory.createInferCaptureBuildTarget(depOneSourceName);

    BuildTarget depTwoBuildTarget = BuildTargetFactory.newInstance("//foo:dep_two");
    CxxSourceRuleFactory depTwoSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), depTwoBuildTarget, cxxPlatform, cxxBuckConfig);

    BuildTarget depTwoCaptureBuildTarget =
        depTwoSourceRuleFactory.createInferCaptureBuildTarget("dep_two.c");

    ImmutableSet<String> locallyBuiltTargets =
        ImmutableSet.of(
            cxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget().toString(),
            topCaptureBuildTarget.toString(),
            topInferReportTarget.toString(),
            depOneSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget().toString(),
            depOneCaptureBuildTarget.toString(),
            depTwoSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget().toString(),
            depTwoCaptureBuildTarget.toString());

    // check that infer-related targets are getting rebuilt
    for (String t : locallyBuiltTargets) {
      buildLog.assertTargetBuiltLocally(t);
    }

    Set<String> builtFromCacheTargets =
        FluentIterable.from(buildLog.getAllTargets())
            // Filter out header symlink tree rules, as they are always built locally.
            .filter(
                target ->
                    (!target
                            .getFlavors()
                            .contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
                        && !target
                            .getFlavors()
                            .contains(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR)))
            .transform(Object::toString)
            // Filter out any rules that are explicitly built locally.
            .filter(Predicates.not(locallyBuiltTargets::contains))
            .toSet();

    // check that all the other targets are fetched from the cache
    for (String t : builtFromCacheTargets) {
      buildLog.assertTargetWasFetchedFromCache(t);
    }
  }

  @Test
  public void testInferCxxBinaryWithoutDeps() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig, DEFAULT_DOWNWARD_API_CONFIG);
    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:simple");
    String inputBuildTargetName =
        inputBuildTarget.withFlavors(INFER_CAPTURE_ALL.getFlavor()).getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that all the required build targets have been generated.
     */
    String sourceName = "simple.cpp";
    String sourceFull = "foo/" + sourceName;

    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), inputBuildTarget, cxxPlatform, cxxBuckConfig);
    // this is unflavored, but bounded to the InferCapture build rule
    BuildTarget captureBuildTarget = cxxSourceRuleFactory.createInferCaptureBuildTarget(sourceName);
    // this is unflavored, but necessary to run the compiler successfully
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            inputBuildTarget, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());
    // this is flavored, and denotes the analysis step (generates a local report)
    BuildTarget inferAnalysisTarget = inputBuildTarget.withFlavors(INFER_CAPTURE_ALL.getFlavor());

    // this is flavored and corresponds to the top level target (the one give in input to buck)
    BuildTarget inferReportTarget = inputBuildTarget.withFlavors(INFER_CAPTURE_ALL.getFlavor());

    BuildTarget aggregatedDepsTarget =
        cxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    ImmutableSortedSet.Builder<BuildTarget> targetsBuilder =
        ImmutableSortedSet.<BuildTarget>naturalOrder()
            .add(
                aggregatedDepsTarget,
                headerSymlinkTreeTarget,
                captureBuildTarget,
                inferAnalysisTarget,
                inferReportTarget);

    BuckBuildLog buildLog = workspace.getBuildLog();
    assertThat(buildLog.getAllTargets(), containsInAnyOrder(targetsBuilder.build().toArray()));
    buildLog.assertTargetBuiltLocally(aggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget);
    buildLog.assertTargetBuiltLocally(captureBuildTarget);
    buildLog.assertTargetBuiltLocally(inferAnalysisTarget);
    buildLog.assertTargetBuiltLocally(inferReportTarget);

    /*
     * Check that running a build again results in no builds since nothing has changed.
     */
    workspace.resetBuildLogFile(); // clear for new build
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertThat(buildLog.getAllTargets(), equalTo(ImmutableSet.of(inferReportTarget)));
    buildLog.assertTargetHadMatchingRuleKey(inferReportTarget);

    /*
     * Check that changing the source file results in running the capture/analysis rules again.
     */
    workspace.resetBuildLogFile();
    workspace.replaceFileContents(sourceFull, "*s = 42;", "");
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();
    targetsBuilder =
        ImmutableSortedSet.<BuildTarget>naturalOrder()
            .add(
                aggregatedDepsTarget,
                captureBuildTarget,
                inferAnalysisTarget,
                inferReportTarget,
                headerSymlinkTreeTarget);
    assertThat(buildLog.getAllTargets(), equalTo(targetsBuilder.build()));
    buildLog.assertTargetBuiltLocally(captureBuildTarget);
    buildLog.assertTargetBuiltLocally(inferAnalysisTarget);
    buildLog.assertTargetHadMatchingRuleKey(aggregatedDepsTarget);
  }

  @Test
  public void testInferCxxBinaryWithDeps() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig, DEFAULT_DOWNWARD_API_CONFIG);
    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_deps");
    String inputBuildTargetName =
        inputBuildTarget.withFlavors(INFER_CAPTURE_ALL.getFlavor()).getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that all the required build targets have been generated.
     */
    String sourceName = "src_with_deps.c";
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), inputBuildTarget, cxxPlatform, cxxBuckConfig);
    // 1. create the targets of binary_with_deps
    // this is unflavored, but bounded to the InferCapture build rule
    BuildTarget topCaptureBuildTarget =
        cxxSourceRuleFactory.createInferCaptureBuildTarget(sourceName);

    // this is unflavored, but necessary to run the compiler successfully
    BuildTarget topHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            inputBuildTarget, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());

    // this is flavored and corresponds to the top level target (the one give in input to buck)
    BuildTarget topInferReportTarget = inputBuildTarget.withFlavors(INFER_CAPTURE_ALL.getFlavor());

    BuildTarget topAggregatedDepsTarget =
        cxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    // 2. create the targets of dep_one
    BuildTarget depOneBuildTarget = BuildTargetFactory.newInstance("//foo:dep_one");
    String depOneSourceName = "dep_one.c";
    String depOneSourceFull = "foo/" + depOneSourceName;
    CxxSourceRuleFactory depOneSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), depOneBuildTarget, cxxPlatform, cxxBuckConfig);

    BuildTarget depOneCaptureBuildTarget =
        depOneSourceRuleFactory.createInferCaptureBuildTarget(depOneSourceName);

    BuildTarget depOneHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depOneBuildTarget, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());

    BuildTarget depOneExportedHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depOneBuildTarget,
            HeaderVisibility.PUBLIC,
            CxxPlatformUtils.getHeaderModeForDefaultPlatform(tmp.getRoot()).getFlavor());

    BuildTarget depOneAggregatedDepsTarget =
        depOneSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    // 3. create the targets of dep_two
    BuildTarget depTwoBuildTarget = BuildTargetFactory.newInstance("//foo:dep_two");
    CxxSourceRuleFactory depTwoSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), depTwoBuildTarget, cxxPlatform, cxxBuckConfig);

    BuildTarget depTwoCaptureBuildTarget =
        depTwoSourceRuleFactory.createInferCaptureBuildTarget("dep_two.c");

    BuildTarget depTwoHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depTwoBuildTarget, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());

    BuildTarget depTwoExportedHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depTwoBuildTarget,
            HeaderVisibility.PUBLIC,
            CxxPlatformUtils.getHeaderModeForDefaultPlatform(tmp.getRoot()).getFlavor());

    BuildTarget depTwoAggregatedDepsTarget =
        depTwoSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    ImmutableSet<BuildTarget> buildTargets =
        ImmutableSet.of(
            topAggregatedDepsTarget,
            topCaptureBuildTarget,
            topHeaderSymlinkTreeTarget,
            topInferReportTarget,
            depOneAggregatedDepsTarget,
            depOneCaptureBuildTarget,
            depOneHeaderSymlinkTreeTarget,
            depOneExportedHeaderSymlinkTreeTarget,
            depTwoAggregatedDepsTarget,
            depTwoCaptureBuildTarget,
            depTwoHeaderSymlinkTreeTarget,
            depTwoExportedHeaderSymlinkTreeTarget);
    // Check all the targets are in the buildLog
    assertThat(workspace.getBuildLog().getAllTargets(), equalTo(buildTargets));

    /*
     * Check that running a build again results in no builds since nothing has changed.
     */
    workspace.resetBuildLogFile(); // clear for new build
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetHadMatchingRuleKey(inputBuildTargetName);

    /*
     * Check that if a library source file changes then the capture/analysis rules run again on
     * the main target and on dep_one only.
     */
    workspace.resetBuildLogFile();
    workspace.replaceFileContents(depOneSourceFull, "flag > 0", "flag < 0");
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildTargets =
        ImmutableSortedSet.of(
            topInferReportTarget, // report runs again
            topCaptureBuildTarget, // cached
            depOneAggregatedDepsTarget,
            depOneHeaderSymlinkTreeTarget,
            depOneExportedHeaderSymlinkTreeTarget,
            depOneCaptureBuildTarget, // capture of the changed file runs again
            depTwoCaptureBuildTarget // cached
            );
    assertThat(buildLog.getAllTargets(), equalTo(buildTargets));
    buildLog.assertTargetBuiltLocally(topInferReportTarget);
    buildLog.assertTargetHadMatchingRuleKey(topCaptureBuildTarget);
    buildLog.assertTargetBuiltLocally(depOneCaptureBuildTarget);
    buildLog.assertTargetHadMatchingRuleKey(depOneAggregatedDepsTarget);
    buildLog.assertTargetHadMatchingRuleKey(depTwoCaptureBuildTarget);
  }

  @Test
  public void testInferCxxBinaryWithDepsEmitsAllTheDependenciesResultsDirs() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_chain_deps")
            .withFlavors(INFER_CAPTURE_ALL.getFlavor());

    // Build the given target and check that it succeeds.
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildPaths.getGenDir(filesystem.getBuckPaths(), inputBuildTarget)
                    .resolve(CxxInferCaptureTransitiveRule.OUTPUT_PATH))));

    Set<String> loggedDeps =
        getUniqueLines(
            workspace.getFileContents(
                BuildPaths.getGenDir(filesystem.getBuckPaths(), inputBuildTarget)
                    .resolve(CxxInferCaptureTransitiveRule.OUTPUT_PATH)));

    String sanitizedChainDepOne = sanitize("chain_dep_one.c.o");
    String sanitizedTopChain = sanitize("top_chain.c.o");
    String sanitizedChainDepTwo = sanitize("chain_dep_two.c.o");

    BuildTarget captureTopChainTarget =
        BuildTargetFactory.newInstance(
            "//foo:binary_with_chain_deps#default,infer-capture-" + sanitizedTopChain);
    BuildTarget captureChainDepOneTarget =
        BuildTargetFactory.newInstance(
            "//foo:chain_dep_one#default,infer-capture-" + sanitizedChainDepOne);
    BuildTarget captureChainDepTwoTarget =
        BuildTargetFactory.newInstance(
            "//foo:chain_dep_two#default,infer-capture-" + sanitizedChainDepTwo);

    AbsPath basePath = filesystem.getRootPath().toRealPath();
    Set<String> expectedOutput =
        ImmutableSet.of(
            captureTopChainTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-capture-"
                + sanitizedTopChain
                + "]\t"
                + basePath.resolve(
                    BuildPaths.getGenDir(filesystem.getBuckPaths(), captureTopChainTarget)
                        .resolve(CxxInferCaptureRule.RESULT_DIR_PATH)),
            captureChainDepOneTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-capture-"
                + sanitizedChainDepOne
                + "]\t"
                + basePath.resolve(
                    BuildPaths.getGenDir(filesystem.getBuckPaths(), captureChainDepOneTarget)
                        .resolve(CxxInferCaptureRule.RESULT_DIR_PATH)),
            captureChainDepTwoTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-capture-"
                + sanitizedChainDepTwo
                + "]\t"
                + basePath.resolve(
                    BuildPaths.getGenDir(filesystem.getBuckPaths(), captureChainDepTwoTarget)
                        .resolve(CxxInferCaptureRule.RESULT_DIR_PATH)));

    assertThat(loggedDeps, equalTo(expectedOutput));
  }

  private static void registerCell(
      ProjectWorkspace cellToModifyConfigOf,
      String cellName,
      ProjectWorkspace cellToRegisterAsCellName)
      throws IOException {
    TestDataHelper.overrideBuckconfig(
        cellToModifyConfigOf,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of(
                cellName, cellToRegisterAsCellName.getPath(".").normalize().toString())));
  }

  @Test
  public void inferShouldBeAbleToUseMultipleXCell() throws IOException {

    AbsPath rootWorkspacePath = tmp.getRoot();
    // create infertest workspace
    InferHelper.setupWorkspace(this, rootWorkspacePath, "infertest");

    // create infertest/inter-cell/multi-cell/primary sub-workspace as infer-configured one
    AbsPath primaryRootPath = tmp.newFolder().toRealPath().normalize();
    ProjectWorkspace primary =
        InferHelper.setupCxxInferWorkspace(
            this,
            primaryRootPath,
            Optional.empty(),
            "infertest/inter-cell/multi-cell/primary",
            Optional.of(rootWorkspacePath.resolve("fake-infer")));

    // create infertest/inter-cell/multi-cell/secondary sub-workspace
    AbsPath secondaryRootPath = tmp.newFolder().toRealPath().normalize();
    ProjectWorkspace secondary =
        InferHelper.setupWorkspace(
            this, secondaryRootPath, "infertest/inter-cell/multi-cell/secondary");

    // register cells
    registerCell(primary, "secondary", secondary);

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//:cxxbinary").withFlavors(INFER_CAPTURE_ALL.getFlavor());

    // run from primary workspace
    ProcessResult result =
        primary.runBuckBuild(
            InferHelper.getCxxCLIConfigurationArgs(
                rootWorkspacePath.resolve("fake-infer").getPath(),
                Optional.empty(),
                inputBuildTarget));

    result.assertSuccess();

    ProjectFilesystem filesystem = primary.getProjectFileSystem();
    RelPath depsPath =
        BuildPaths.getGenDir(filesystem.getBuckPaths(), inputBuildTarget)
            .resolve(CxxInferCaptureTransitiveRule.OUTPUT_PATH);
    List<String> lines = filesystem.readLines(depsPath.getPath());
    assertThat(lines, hasSize(2));
  }

  @Test
  public void testInferCxxBinaryShowOutput() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.enableDirCache();

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_diamond_deps")
            .withFlavors(INFER_CAPTURE_ALL.getFlavor());
    String buildTargetName = inputBuildTarget.getFullyQualifiedName();

    ProcessResult processResult = workspace.runBuckBuild("--show-full-output", buildTargetName);
    processResult.assertSuccess();

    ProjectFilesystem filesystem = workspace.getProjectFileSystem();
    String expectedLine =
        String.join(
            " ",
            buildTargetName,
            filesystem
                .resolve(
                    BuildPaths.getGenDir(filesystem.getBuckPaths(), inputBuildTarget)
                        .resolve(CxxInferCaptureTransitiveRule.OUTPUT_PATH))
                .toString());

    assertThat(processResult.getStdout().trim(), equalTo(expectedLine));
  }

  @Test
  public void testInferCxxBinaryWithDiamondDepsEmitsAllBuildRulesInvolvedWhenCacheHit()
      throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.enableDirCache();
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_diamond_deps")
            .withFlavors(INFER_CAPTURE_ALL.getFlavor());
    String buildTargetName = inputBuildTarget.getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", buildTargetName).assertSuccess();

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", buildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    ImmutableSet<BuildTarget> allInvolvedTargets = buildLog.getAllTargets();
    assertThat(allInvolvedTargets, hasSize(1)); // Only main target should be fetched from cache
    for (BuildTarget bt : allInvolvedTargets) {
      buildLog.assertTargetWasFetchedFromCache(bt);
    }

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildPaths.getGenDir(filesystem.getBuckPaths(), inputBuildTarget)
                    .resolve(CxxInferCaptureTransitiveRule.OUTPUT_PATH))));

    Set<String> loggedDeps =
        getUniqueLines(
            workspace.getFileContents(
                BuildPaths.getGenDir(filesystem.getBuckPaths(), inputBuildTarget)
                    .resolve(CxxInferCaptureTransitiveRule.OUTPUT_PATH)));

    String sanitizedSimpleCpp = sanitize("simple.cpp.o");
    String sanitizedDepOne = sanitize("dep_one.c.o");
    String sanitizedDepTwo = sanitize("dep_two.c.o");
    String sanitizedSrcWithDeps = sanitize("src_with_deps.c.o");
    BuildTarget simpleCppTarget =
        BuildTargetFactory.newInstance(
            "//foo:simple_lib#default,infer-capture-" + sanitizedSimpleCpp);
    BuildTarget depOneTarget =
        BuildTargetFactory.newInstance(
            "//foo:diamond_dep_one#default,infer-capture-" + sanitizedDepOne);
    BuildTarget depTwoTarget =
        BuildTargetFactory.newInstance(
            "//foo:diamond_dep_two#default,infer-capture-" + sanitizedDepTwo);
    BuildTarget srcWithDepsTarget =
        BuildTargetFactory.newInstance(
            "//foo:binary_with_diamond_deps#default,infer-capture-" + sanitizedSrcWithDeps);

    AbsPath basePath = filesystem.getRootPath().toRealPath();
    Set<String> expectedOutput =
        ImmutableSet.of(
            createInferLogLine(filesystem, srcWithDepsTarget, basePath).getFormattedString(),
            createInferLogLine(filesystem, depOneTarget, basePath).getFormattedString(),
            createInferLogLine(filesystem, depTwoTarget, basePath).getFormattedString(),
            createInferLogLine(filesystem, simpleCppTarget, basePath).getFormattedString());

    assertThat(loggedDeps, equalTo(expectedOutput));
  }

  @Test
  public void testInferCaptureAllCxxBinaryWithDiamondDepsEmitsAllBuildRulesInvolvedWhenCacheHit()
      throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.enableDirCache();
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_diamond_deps")
            .withFlavors(INFER_CAPTURE_ALL.getFlavor());
    String buildTargetName = inputBuildTarget.getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", buildTargetName).assertSuccess();

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", buildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    ImmutableSet<BuildTarget> allInvolvedTargets = buildLog.getAllTargets();
    for (BuildTarget bt : allInvolvedTargets) {
      buildLog.assertTargetWasFetchedFromCache(bt);
    }

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildPaths.getGenDir(filesystem.getBuckPaths(), inputBuildTarget)
                    .resolve(CxxInferCaptureTransitiveRule.OUTPUT_PATH))));

    Set<String> loggedDeps =
        getUniqueLines(
            workspace.getFileContents(
                BuildPaths.getGenDir(filesystem.getBuckPaths(), inputBuildTarget)
                    .resolve(CxxInferCaptureTransitiveRule.OUTPUT_PATH)));

    String sanitizedSimpleCpp = sanitize("simple.cpp.o");
    String sanitizedDepOne = sanitize("dep_one.c.o");
    String sanitizedDepTwo = sanitize("dep_two.c.o");
    String sanitizedSrcWithDeps = sanitize("src_with_deps.c.o");
    BuildTarget simpleCppTarget =
        BuildTargetFactory.newInstance(
            "//foo:simple_lib#default,infer-capture-" + sanitizedSimpleCpp);
    BuildTarget depOneTarget =
        BuildTargetFactory.newInstance(
            "//foo:diamond_dep_one#default,infer-capture-" + sanitizedDepOne);
    BuildTarget depTwoTarget =
        BuildTargetFactory.newInstance(
            "//foo:diamond_dep_two#default,infer-capture-" + sanitizedDepTwo);
    BuildTarget srcWithDepsTarget =
        BuildTargetFactory.newInstance(
            "//foo:binary_with_diamond_deps#default,infer-capture-" + sanitizedSrcWithDeps);

    AbsPath basePath = filesystem.getRootPath().toRealPath();
    Set<String> expectedOutput =
        ImmutableSet.of(
            createInferLogLine(filesystem, srcWithDepsTarget, basePath).getFormattedString(),
            createInferLogLine(filesystem, depOneTarget, basePath).getFormattedString(),
            createInferLogLine(filesystem, depTwoTarget, basePath).getFormattedString(),
            createInferLogLine(filesystem, simpleCppTarget, basePath).getFormattedString());

    assertThat(loggedDeps, equalTo(expectedOutput));
  }

  private InferLogLine createInferLogLine(
      ProjectFilesystem filesystem, BuildTarget buildTarget, AbsPath basePath) {
    return InferLogLine.of(
        buildTarget,
        basePath.resolve(
            BuildPaths.getGenDir(filesystem.getBuckPaths(), buildTarget)
                .resolve(CxxInferCaptureRule.RESULT_DIR_PATH)));
  }

  @Test
  public void testInferCxxBinaryWithUnusedDepsDoesNotRebuildWhenUnusedHeaderChanges()
      throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.enableDirCache();

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_unused_header");
    String inputBuildTargetName =
        inputBuildTarget.withFlavors(INFER_CAPTURE_ALL.getFlavor()).getFullyQualifiedName();

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig, DEFAULT_DOWNWARD_API_CONFIG);

    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), inputBuildTarget, cxxPlatform, cxxBuckConfig);

    BuildTarget simpleOneCppCaptureTarget =
        cxxSourceRuleFactory.createInferCaptureBuildTarget("simple_one.cpp");

    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that when the unused-header is changed, no builds are triggered
     */
    workspace.resetBuildLogFile();
    workspace.replaceFileContents("foo/unused_header.h", "int* input", "int* input, int* input2");
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    BuckBuildLog.BuildLogEntry simpleOnceCppCaptureTargetEntry =
        buildLog.getLogEntry(simpleOneCppCaptureTarget);

    assertThat(
        simpleOnceCppCaptureTargetEntry.getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.FETCHED_FROM_CACHE_MANIFEST_BASED)));

    /*
     * Check that when the used-header is changed, then a build is triggered
     */
    workspace.resetBuildLogFile();
    workspace.replaceFileContents("foo/used_header.h", "int* input", "int* input, int* input2");
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(simpleOneCppCaptureTarget);
  }

  @Test
  public void testInferCxxBinaryWithDiamondDepsEmitsAllTransitiveCaptureRulesOnce()
      throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_diamond_deps")
            .withFlavors(INFER_CAPTURE_ALL.getFlavor());

    // Build the given target and check that it succeeds.
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildPaths.getGenDir(filesystem.getBuckPaths(), inputBuildTarget)
                    .resolve(CxxInferCaptureTransitiveRule.OUTPUT_PATH))));

    Set<String> loggedDeps =
        getUniqueLines(
            workspace.getFileContents(
                BuildPaths.getGenDir(filesystem.getBuckPaths(), inputBuildTarget)
                    .resolve(CxxInferCaptureTransitiveRule.OUTPUT_PATH)));

    String sanitizedSimpleCpp = sanitize("simple.cpp.o");
    String sanitizedDepOne = sanitize("dep_one.c.o");
    String sanitizedDepTwo = sanitize("dep_two.c.o");
    String sanitizedSrcWithDeps = sanitize("src_with_deps.c.o");
    BuildTarget simpleCppTarget =
        BuildTargetFactory.newInstance(
            "//foo:simple_lib#default,infer-capture-" + sanitizedSimpleCpp);
    BuildTarget depOneTarget =
        BuildTargetFactory.newInstance(
            "//foo:diamond_dep_one#default,infer-capture-" + sanitizedDepOne);
    BuildTarget depTwoTarget =
        BuildTargetFactory.newInstance(
            "//foo:diamond_dep_two#default,infer-capture-" + sanitizedDepTwo);
    BuildTarget srcWithDepsTarget =
        BuildTargetFactory.newInstance(
            "//foo:binary_with_diamond_deps#default,infer-capture-" + sanitizedSrcWithDeps);

    AbsPath basePath = filesystem.getRootPath().toRealPath();
    Set<String> expectedOutput =
        ImmutableSet.of(
            srcWithDepsTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-capture-"
                + sanitizedSrcWithDeps
                + "]\t"
                + basePath.resolve(
                    BuildPaths.getGenDir(filesystem.getBuckPaths(), srcWithDepsTarget)
                        .resolve(CxxInferCaptureRule.RESULT_DIR_PATH)),
            depOneTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-capture-"
                + sanitizedDepOne
                + "]\t"
                + basePath.resolve(
                    BuildPaths.getGenDir(filesystem.getBuckPaths(), depOneTarget)
                        .resolve(CxxInferCaptureRule.RESULT_DIR_PATH)),
            depTwoTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-capture-"
                + sanitizedDepTwo
                + "]\t"
                + basePath.resolve(
                    BuildPaths.getGenDir(filesystem.getBuckPaths(), depTwoTarget)
                        .resolve(CxxInferCaptureRule.RESULT_DIR_PATH)),
            simpleCppTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-capture-"
                + sanitizedSimpleCpp
                + "]\t"
                + basePath.resolve(
                    BuildPaths.getGenDir(filesystem.getBuckPaths(), simpleCppTarget)
                        .resolve(CxxInferCaptureRule.RESULT_DIR_PATH)));

    assertThat(loggedDeps, equalTo(expectedOutput));
  }

  @Test
  public void testInferCxxBinarySkipsBlockListedFiles() throws IOException {
    ProjectWorkspace workspace =
        InferHelper.setupCxxInferWorkspace(this, tmp, Optional.of(".*one\\.c"));
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_chain_deps");
    String inputBuildTargetName =
        inputBuildTarget.withFlavors(INFER_CAPTURE_ALL.getFlavor()).getFullyQualifiedName();

    // Build the given target and check that it succeeds.
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    // Check that the cfg associated with chain_dep_one.c does not exist
    assertFalse(
        "Cfg file for chain_dep_one.c should not exist",
        Files.exists(
            workspace
                .getPath(
                    BuildPaths.getGenDir(
                            filesystem.getBuckPaths(),
                            BuildTargetFactory.newInstance(
                                "//foo:chain_dep_one#default,infer-capture-"
                                    + sanitize("chain_dep_one.c.o")))
                        .resolve(CxxInferCaptureRule.RESULT_DIR_PATH))
                .resolve("captured/chain_dep_one.c_captured/chain_dep_one.c.cfg")));

    // Check that the remaining files still have their cfgs
    assertTrue(
        "Expected cfg for chain_dep_two.c not found",
        Files.exists(
            workspace
                .getPath(
                    BuildPaths.getGenDir(
                            filesystem.getBuckPaths(),
                            BuildTargetFactory.newInstance(
                                "//foo:chain_dep_two#default,infer-capture-"
                                    + sanitize("chain_dep_two.c.o")))
                        .resolve(CxxInferCaptureRule.RESULT_DIR_PATH))
                .resolve("captured/chain_dep_two.c_captured/chain_dep_two.c.cfg")));
    assertTrue(
        "Expected cfg for top_chain.c not found",
        Files.exists(
            workspace.getPath(
                BuildPaths.getGenDir(
                        filesystem.getBuckPaths(),
                        BuildTargetFactory.newInstance(
                            "//foo:binary_with_chain_deps#default,infer-capture-"
                                + sanitize("top_chain.c.o")))
                    .resolve(CxxInferCaptureRule.RESULT_DIR_PATH)
                    .resolve("captured/top_chain.c_captured/top_chain.c.cfg"))));
  }

  @Test
  public void testInferCxxBinaryRunsOnAllFilesWhenBlockListIsNotSpecified() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_chain_deps");
    String inputBuildTargetName =
        inputBuildTarget.withFlavors(INFER_CAPTURE_ALL.getFlavor()).getFullyQualifiedName();

    // Build the given target and check that it succeeds.
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    // Check that all cfgs have been created
    assertTrue(
        "Expected cfg for chain_dep_one.c not found",
        Files.exists(
            workspace
                .getPath(
                    BuildPaths.getGenDir(
                            filesystem.getBuckPaths(),
                            BuildTargetFactory.newInstance(
                                "//foo:chain_dep_one#default,infer-capture-"
                                    + sanitize("chain_dep_one.c.o")))
                        .resolve(CxxInferCaptureRule.RESULT_DIR_PATH))
                .resolve("captured/chain_dep_one.c_captured/chain_dep_one.c.cfg")));
    assertTrue(
        "Expected cfg for chain_dep_two.c not found",
        Files.exists(
            workspace
                .getPath(
                    BuildPaths.getGenDir(
                            filesystem.getBuckPaths(),
                            BuildTargetFactory.newInstance(
                                "//foo:chain_dep_two#default,infer-capture-"
                                    + sanitize("chain_dep_two.c.o")))
                        .resolve(CxxInferCaptureRule.RESULT_DIR_PATH))
                .resolve("captured/chain_dep_two.c_captured/chain_dep_two.c.cfg")));
    assertTrue(
        "Expected cfg for top_chain.c not found",
        Files.exists(
            workspace
                .getPath(
                    BuildPaths.getGenDir(
                            filesystem.getBuckPaths(),
                            BuildTargetFactory.newInstance(
                                "//foo:binary_with_chain_deps#default,infer-capture-"
                                    + sanitize("top_chain.c.o")))
                        .resolve(CxxInferCaptureRule.RESULT_DIR_PATH))
                .resolve("captured/top_chain.c_captured/top_chain.c.cfg")));
  }

  @Test
  public void testInferCxxBinaryWithCachedDepsGetsAllItsTransitiveDeps() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.enableDirCache();

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_chain_deps")
            .withFlavors(INFER_CAPTURE_ALL.getFlavor());

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    for (BuildTarget buildTarget : buildLog.getAllTargets()) {
      buildLog.assertTargetWasFetchedFromCache(buildTarget);
    }

    /*
     * Check that if the file in the top target changes, then all the transitive deps will be
     * fetched from the cache (even those that are not direct dependencies).
     * Make sure there's the specs file of the dependency that has distance 2 from
     * the binary target.
     */
    String sourceName = "top_chain.c";
    workspace.replaceFileContents("foo/" + sourceName, "*p += 1", "*p += 10");
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();
    BuckBuildLog buildLog2 = workspace.getBuildLog();
    BuildTarget topBuildTarget =
        BuildTargetFactory.newInstance(
            "//foo:binary_with_chain_deps#default,infer-capture-" + sanitize("top_chain.c.o"));
    BuildTarget depOneBuildTarget =
        BuildTargetFactory.newInstance(
            "//foo:chain_dep_one#default,infer-capture-" + sanitize("chain_dep_one.c.o"));
    BuildTarget depTwoBuildTarget =
        BuildTargetFactory.newInstance(
            "//foo:chain_dep_two#default,infer-capture-" + sanitize("chain_dep_two.c.o"));
    buildLog2.assertTargetBuiltLocally(topBuildTarget);
    buildLog2.assertTargetWasFetchedFromCache(depOneBuildTarget);
    buildLog2.assertTargetWasFetchedFromCache(depTwoBuildTarget);
  }

  @Test
  @Ignore
  public void testChangingCompilerPathForcesRebuild() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    BuildTarget target = BuildTargetFactory.newInstance("//foo:simple");
    BuildTarget linkTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target, Optional.empty());

    // Get the real location of the compiler executable.
    String executable = Platform.detect() == Platform.MACOS ? "clang++" : "g++";
    Path executableLocation =
        new ExecutableFinder()
            .getOptionalExecutable(Paths.get(executable), EnvVariablesProvider.getSystemEnv())
            .orElse(Paths.get("/usr/bin", executable));

    // Write script as faux clang++/g++ binary
    AbsPath firstCompilerPath = tmp.newFolder("path1");
    AbsPath firstCompiler = firstCompilerPath.resolve(executable);
    filesystem.writeContentsToPath(
        "#!/bin/sh\n" + "exec " + executableLocation + " \"$@\"\n", firstCompiler.getPath());

    // Write script as slightly different faux clang++/g++ binary
    AbsPath secondCompilerPath = tmp.newFolder("path2");
    AbsPath secondCompiler = secondCompilerPath.resolve(executable);
    filesystem.writeContentsToPath(
        "#!/bin/sh\n"
            + "exec "
            + executableLocation
            + " \"$@\"\n"
            + "# Comment to make hash different.\n",
        secondCompiler.getPath());

    // Make the second faux clang++/g++ binary executable
    MostFiles.makeExecutable(secondCompiler);

    // Run two builds, each with different compiler "binaries".  In the first
    // instance, both binaries are in the PATH but the first binary is not
    // marked executable so is not picked up.
    workspace
        .runBuckCommandWithEnvironmentOverrides(
            workspace.getDestPath(),
            ImmutableMap.of(
                "PATH",
                firstCompilerPath
                    + pathSeparator
                    + secondCompilerPath
                    + pathSeparator
                    + EnvVariablesProvider.getSystemEnv().get("PATH")),
            "build",
            target.getFullyQualifiedName())
        .assertSuccess();

    workspace.resetBuildLogFile();

    // Now, make the first faux clang++/g++ binary executable.  In this second
    // instance, both binaries are still in the PATH but the first binary is
    // now marked executable and so is picked up; causing a rebuild.
    MostFiles.makeExecutable(firstCompiler);

    workspace
        .runBuckCommandWithEnvironmentOverrides(
            workspace.getDestPath(),
            ImmutableMap.of(
                "PATH",
                firstCompilerPath
                    + pathSeparator
                    + secondCompilerPath
                    + pathSeparator
                    + EnvVariablesProvider.getSystemEnv().get("PATH")),
            "build",
            target.getFullyQualifiedName())
        .assertSuccess();

    // Make sure the binary change caused a rebuild.
    workspace.getBuildLog().assertTargetBuiltLocally(linkTarget);
  }

  @Test
  public void testLinkMapIsNotCached() throws Exception {
    // Currently we only support Apple platforms for generating link maps.
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    BuildTarget target = BuildTargetFactory.newInstance("//foo:simple");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"));

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(target);
    assertThat(Files.exists(Paths.get(outputPath + "-LinkMap.txt")), is(true));
  }

  @Test
  public void testLinkMapIsCached() throws Exception {
    // Currently we only support Apple platforms for generating link maps.
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    BuildTarget target = BuildTargetFactory.newInstance("//foo:simple");
    workspace
        .runBuckCommand("build", "-c", "cxx.cache_binaries=true", target.getFullyQualifiedName())
        .assertSuccess();

    Path outputPath =
        workspace.getPath(BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"));

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace
        .runBuckCommand("build", "-c", "cxx.cache_binaries=true", target.toString())
        .assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetWasFetchedFromCache(target);
    assertThat(Files.exists(Paths.get(outputPath + "-LinkMap.txt")), is(true));
  }

  @Test
  public void testSimpleCxxBinaryBuilds() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig, DEFAULT_DOWNWARD_API_CONFIG);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:simple");
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), target, cxxPlatform, cxxBuckConfig);
    BuildTarget binaryTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target, Optional.empty());
    String sourceName = "simple.cpp";
    String sourceFull = "foo/" + sourceName;
    BuildTarget compileTarget = cxxSourceRuleFactory.createCompileBuildTarget(sourceName);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());
    BuildTarget aggregatedDepsTarget =
        cxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    // Do a clean build, verify that it succeeds, and check that all expected targets built
    // successfully.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    assertThat(
        buildLog.getAllTargets(),
        equalTo(
            ImmutableSet.of(
                aggregatedDepsTarget,
                headerSymlinkTreeTarget,
                compileTarget,
                binaryTarget,
                target)));

    buildLog.assertTargetBuiltLocally(aggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget);
    buildLog.assertTargetBuiltLocally(compileTarget);
    buildLog.assertTargetBuiltLocally(binaryTarget);
    buildLog.assertTargetBuiltLocally(target);

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Check that running a build again results in no builds since everything is up to
    // date.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertThat(buildLog.getAllTargets(), equalTo(ImmutableSet.of(target, binaryTarget)));

    buildLog.assertTargetHadMatchingRuleKey(binaryTarget);
    buildLog.assertTargetHadMatchingRuleKey(target);

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(sourceFull, "{}", "{ return 0; }");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertThat(
        buildLog.getAllTargets(),
        equalTo(
            ImmutableSet.of(
                aggregatedDepsTarget,
                compileTarget,
                binaryTarget,
                headerSymlinkTreeTarget,
                target)));

    buildLog.assertTargetHadMatchingRuleKey(aggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(compileTarget);
    assertThat(
        buildLog.getLogEntry(binaryTarget).getSuccessType().get(),
        not(equalTo(BuildRuleSuccessType.MATCHING_RULE_KEY)));

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(sourceFull, "{ return 0; }", "won't compile");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertFailure();
    buildLog = workspace.getBuildLog();
    assertThat(
        buildLog.getAllTargets(),
        equalTo(
            ImmutableSet.of(
                aggregatedDepsTarget,
                compileTarget,
                binaryTarget,
                headerSymlinkTreeTarget,
                target)));

    buildLog.assertTargetHadMatchingRuleKey(aggregatedDepsTarget);
    assertThat(buildLog.getLogEntry(binaryTarget).getStatus(), equalTo(BuildRuleStatus.FAIL));
    assertThat(buildLog.getLogEntry(target).getStatus(), equalTo(BuildRuleStatus.FAIL));
  }

  @Test
  public void testSimpleCxxBinaryWithoutHeader() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//foo:simple_without_header").assertFailure();
  }

  @Test
  public void testSimpleCxxBinaryWithHeader() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig, DEFAULT_DOWNWARD_API_CONFIG);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:simple_with_header");
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), target, cxxPlatform, cxxBuckConfig);
    BuildTarget binaryTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target, Optional.empty());
    String sourceName = "simple_with_header.cpp";
    String headerName = "simple_with_header.h";
    String headerFull = "foo/" + headerName;
    BuildTarget compileTarget = cxxSourceRuleFactory.createCompileBuildTarget(sourceName);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());
    BuildTarget aggregatedDepsTarget =
        cxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    // Do a clean build, verify that it succeeds, and check that all expected targets built
    // successfully.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertThat(
        buildLog.getAllTargets(),
        equalTo(
            ImmutableSet.of(
                aggregatedDepsTarget,
                headerSymlinkTreeTarget,
                compileTarget,
                binaryTarget,
                target)));

    buildLog.assertTargetBuiltLocally(aggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget);
    buildLog.assertTargetBuiltLocally(compileTarget);
    buildLog.assertTargetBuiltLocally(binaryTarget);
    buildLog.assertTargetBuiltLocally(target);

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(headerFull, "blah = 5", "blah = 6");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertThat(
        buildLog.getAllTargets(),
        equalTo(
            ImmutableSet.of(
                headerSymlinkTreeTarget,
                aggregatedDepsTarget,
                compileTarget,
                binaryTarget,
                target)));

    buildLog.assertTargetHadMatchingInputRuleKey(headerSymlinkTreeTarget);
    buildLog.assertTargetBuiltLocally(aggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(compileTarget);
    assertThat(
        buildLog.getLogEntry(binaryTarget).getSuccessType().get(),
        not(equalTo(BuildRuleSuccessType.MATCHING_RULE_KEY)));
  }

  @Test
  public void testSimpleCxxBinaryMissingDependencyOnCxxLibraryWithHeader() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//foo:binary_without_dep").assertFailure();
  }

  @Test
  public void testSimpleCxxBinaryWithDependencyOnCxxLibraryWithHeader() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();

    // Setup variables pointing to the sources and targets of the top-level binary rule.
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig, DEFAULT_DOWNWARD_API_CONFIG);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:binary_with_dep");
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), target, cxxPlatform, cxxBuckConfig);
    BuildTarget binaryTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target, Optional.empty());
    String sourceName = "foo.cpp";
    BuildTarget compileTarget = cxxSourceRuleFactory.createCompileBuildTarget(sourceName);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());
    BuildTarget aggregatedDepsTarget =
        cxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    // Setup variables pointing to the sources and targets of the library dep.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//foo:library_with_header");
    CxxSourceRuleFactory depCxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), depTarget, cxxPlatform, cxxBuckConfig);
    String depSourceName = "bar.cpp";
    String depSourceFull = "foo/" + depSourceName;
    String depHeaderName = "bar.h";
    String depHeaderFull = "foo/" + depHeaderName;
    BuildTarget depCompileTarget = depCxxSourceRuleFactory.createCompileBuildTarget(depSourceName);
    BuildTarget depHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depTarget, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());
    BuildTarget depHeaderExportedSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depTarget,
            HeaderVisibility.PUBLIC,
            CxxPlatformUtils.getHeaderModeForDefaultPlatform(tmp.getRoot()).getFlavor());
    BuildTarget depArchiveTarget =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            depTarget, cxxPlatform.getFlavor(), PicType.PDC, Optional.empty());
    BuildTarget depAggregatedDepsTarget =
        depCxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    ImmutableList.Builder<BuildTarget> builder = ImmutableList.builder();
    builder.add(
        depAggregatedDepsTarget,
        depHeaderSymlinkTreeTarget,
        depHeaderExportedSymlinkTreeTarget,
        depCompileTarget,
        depArchiveTarget,
        depTarget,
        aggregatedDepsTarget,
        headerSymlinkTreeTarget,
        compileTarget,
        binaryTarget,
        target);

    // Do a clean build, verify that it succeeds, and check that all expected targets built
    // successfully.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertThat(
        buildLog.getAllTargets(),
        containsInAnyOrder(builder.build().toArray(new BuildTarget[] {})));
    buildLog.assertTargetBuiltLocally(depHeaderSymlinkTreeTarget);
    buildLog.assertTargetBuiltLocally(depCompileTarget);
    buildLog.assertTargetBuiltLocally(depArchiveTarget);
    buildLog.assertTargetBuiltLocally(depTarget);
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget);
    buildLog.assertTargetBuiltLocally(compileTarget);
    buildLog.assertTargetBuiltLocally(binaryTarget);
    buildLog.assertTargetBuiltLocally(target);

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(depHeaderFull, "int x", "int y");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();

    builder = ImmutableList.builder();
    builder.add(
        depAggregatedDepsTarget,
        depCompileTarget,
        depArchiveTarget,
        depTarget,
        depHeaderSymlinkTreeTarget,
        depHeaderExportedSymlinkTreeTarget,
        headerSymlinkTreeTarget,
        aggregatedDepsTarget,
        compileTarget,
        binaryTarget,
        target);

    assertThat(
        buildLog.getAllTargets(),
        containsInAnyOrder(builder.build().toArray(new BuildTarget[] {})));
    buildLog.assertTargetBuiltLocally(depAggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(depCompileTarget);
    buildLog.assertTargetHadMatchingInputRuleKey(depArchiveTarget);
    buildLog.assertTargetHadMatchingRuleKey(depHeaderSymlinkTreeTarget);
    buildLog.assertTargetHadMatchingInputRuleKey(depHeaderExportedSymlinkTreeTarget);
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget);
    buildLog.assertTargetHadMatchingRuleKey(depTarget);
    buildLog.assertTargetBuiltLocally(aggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(compileTarget);
    assertThat(
        buildLog.getLogEntry(binaryTarget).getSuccessType().get(),
        not(equalTo(BuildRuleSuccessType.MATCHING_RULE_KEY)));

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(depSourceFull, "x + 5", "x + 6");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();

    builder = ImmutableList.builder();
    builder.add(
        depAggregatedDepsTarget,
        depCompileTarget,
        depArchiveTarget,
        depTarget,
        depHeaderExportedSymlinkTreeTarget,
        depHeaderSymlinkTreeTarget,
        compileTarget,
        binaryTarget,
        target);

    assertThat(
        buildLog.getAllTargets(),
        containsInAnyOrder(builder.build().toArray(new BuildTarget[] {})));
    buildLog.assertTargetHadMatchingRuleKey(depAggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(depCompileTarget);
    buildLog.assertTargetBuiltLocally(depArchiveTarget);
    buildLog.assertTargetHadMatchingRuleKey(depTarget);
    buildLog.assertTargetHadMatchingRuleKey(compileTarget);
    buildLog.assertTargetBuiltLocally(binaryTarget);
  }

  @Test
  public void testIncrementalThinLtoBinaryWithDependency() throws IOException {
    assumeThat(Platform.detect(), oneOf(Platform.LINUX, Platform.MACOS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "incremental_thinlto", tmp);

    workspace.setUp();
    workspace.runBuckBuild("//:bin#incremental-thinlto");

    Path indexResult =
        workspace
            .getGenPath(
                BuildTargetFactory.newInstance("//:bin#incremental-thinlto,thinindex"), "%s")
            .resolve("thinlto.indices");

    assertTrue(Files.exists(indexResult.resolve("main.cpp.o.thinlto.bc")));
    assertTrue(Files.exists(indexResult.resolve("main.cpp.o.imports")));

    String indexContents =
        new String(
            Files.readAllBytes(indexResult.resolve("main.cpp.o.thinlto.bc")),
            StandardCharsets.UTF_8);
    if (Platform.detect() == Platform.MACOS) {
      assertThat(indexContents, containsString("-Wl,-thinlto_emit_indexes"));
      assertThat(indexContents, containsString("-Wl,-thinlto_emit_imports"));
      assertThat(
          indexContents,
          containsString(
              "-Xlinker -thinlto_new_prefix -Xlinker "
                  + BuildTargetPaths.getGenPath(
                          workspace.getProjectFileSystem().getBuckPaths(),
                          BuildTargetFactory.newInstance("//:bin#incremental-thinlto,thinindex"),
                          "%s")
                      .resolve("thinlto.indices")));
    } else if (Platform.detect() == Platform.LINUX) {
      assertThat(
          indexContents, containsString("-Wl,-plugin-opt,thinlto-index-only=thinlto.objects"));
      assertThat(indexContents, containsString("-Wl,-plugin-opt,thinlto-emit-imports-files"));
      assertThat(
          indexContents,
          containsString(
              "-Xlinker -plugin-opt -Xlinker 'thinlto-prefix-replace=;"
                  + BuildTargetPaths.getGenPath(
                          workspace.getProjectFileSystem().getBuckPaths(),
                          BuildTargetFactory.newInstance("//:bin#incremental-thinlto,thinindex"),
                          "%s")
                      .resolve("thinlto.indices")));
    }

    // Since we don't have the full thinLTO toolchain, we're just going to verify that the
    // -fthinlto-index
    // parameter is populated correctly.
    Path optResult =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance(
                "//:bin#default,incremental-thinlto,optimize-main.cpp.o.o55eba575"),
            "%s");
    String optContents =
        new String(
            Files.readAllBytes(optResult.resolve("ppandcompile.argsfile")), StandardCharsets.UTF_8);
    assertThat(Files.exists(optResult.resolve("ppandcompile.argsfile")), equalTo(true));
    assertThat(
        optContents,
        containsString(
            "-fthinlto-index="
                + BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(),
                    BuildTargetFactory.newInstance("//:bin#incremental-thinlto,thinindex"),
                    "%s")
                + "/thinlto.indices/"
                + BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(),
                    BuildTargetFactory.newInstance(
                        "//:bin#compile-main.cpp.oa5b6a1ba,default,incremental-thinlto"),
                    "%s")
                + "/main.cpp.o.thinlto.bc"));
  }

  @Test
  public void testCxxBinaryDepfileBuildWithChangedHeader() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "cxx_binary_depfile_build_with_changed_header", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "//:bin");
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "cxx_binary_depfile_build_with_added_header", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "//:bin");
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:bin#binary");
    buildLog.assertTargetBuiltLocally("//:bin#compile-" + sanitize("bin.c.o") + ",default");
    buildLog.assertTargetBuiltLocally("//:lib1#default,static");

    workspace.resetBuildLogFile();

    workspace.replaceFileContents("BUCK", "[\"lib1.h\"]", "[\"lib1.h\", \"lib2.h\"]");

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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//foo:binary_without_dep").assertFailure();
  }

  @Test
  public void testHeaderNamespace() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "header_namespace", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//:test").assertSuccess();
  }

  @Test
  public void resolveHeadersBehindSymlinkTreesInError() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "resolved", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    workspace.writeContentsToPath("#invalid_pragma", "lib2.h");

    BuildTarget target = BuildTargetFactory.newInstance("//:bin");
    ProcessResult result = workspace.runBuckCommand("build", target.toString());
    result.assertFailure();

    // Verify that the preprocessed source contains no references to the symlink tree used to
    // setup the headers.
    String error = result.getStderr();
    BuckPaths buckPaths = filesystem.getBuckPaths();
    assertThat(error, not(containsString(buckPaths.getScratchDir().toAbsolutePath().toString())));
    assertThat(error, not(containsString(buckPaths.getGenDir().toString())));
    assertThat(error, containsString("In file included from lib1.h:1"));
    assertThat(error, containsString("from bin.h:1"));
    assertThat(error, containsString("from bin.cpp:1:"));
    assertThat(error, containsString("lib2.h:1:2: error: invalid preprocessing"));
  }

  @Test
  public void ndkCxxPlatforms() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();
    boolean isPriorNdk17 = AssumeAndroidPlatform.get(workspace).isArmAvailable();
    String armAbiString = isPriorNdk17 ? "arm, " : "";
    workspace.writeContentsToPath(
        "[ndk]\n"
            + "  gcc_version = 4.9\n"
            + ("  cpu_abis = " + armAbiString + "armv7, arm64, x86\n")
            + "  app_platform = android-21\n",
        ".buckconfig");

    if (isPriorNdk17) {
      workspace.runBuckCommand("build", "//foo:simple#android-arm").assertSuccess();
    }
    workspace.runBuckCommand("build", "//foo:simple#android-armv7").assertSuccess();
    workspace.runBuckCommand("build", "//foo:simple#android-arm64").assertSuccess();
    workspace.runBuckCommand("build", "//foo:simple#android-x86").assertSuccess();
  }

  @Test
  public void linkerFlags() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "linker_flags", tmp);
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

  private void platformLinkerFlags(ProjectWorkspace workspace, String target) {
    workspace.runBuckBuild("//:binary_matches_default_exactly_" + target).assertSuccess();
    workspace.runBuckBuild("//:binary_matches_default_" + target).assertSuccess();
    ProcessResult result = workspace.runBuckBuild("//:binary_no_match_" + target);
    result.assertFailure();
    assertThat(result.getStderr(), containsString("reference"));
    workspace.runBuckBuild("//:binary_with_library_matches_default_" + target).assertSuccess();
    workspace
        .runBuckBuild("//:binary_with_prebuilt_library_matches_default_" + target)
        .assertSuccess();
  }

  @Test
  public void platformLinkerFlags() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "platform_linker_flags", tmp);
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "preprocessing_per_file_flags", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:bin");
    result.assertSuccess();
  }

  @Test
  public void correctPerFileFlagsUsedForCompilation() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "compiling_per_file_flags", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:working-bin");
    result.assertSuccess();
  }

  @Test
  public void incorrectPerFileFlagsUsedForCompilation() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "compiling_per_file_flags", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:broken-bin");
    result.assertFailure();
  }

  @Test
  public void platformPreprocessorFlags() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "platform_preprocessor_flags", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:binary_matches_default_exactly").assertSuccess();
    workspace.runBuckBuild("//:binary_matches_default").assertSuccess();
    ProcessResult result = workspace.runBuckBuild("//:binary_no_match");
    result.assertFailure();
    assertThat(result.getStderr(), containsString("#error"));
    workspace.runBuckBuild("//:binary_with_library_matches_default").assertSuccess();
  }

  @Test
  public void platformCompilerFlags() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "platform_compiler_flags", tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[cxx]\n  cxxflags = -Wall -Werror", ".buckconfig");
    workspace.runBuckBuild("//:binary_matches_default_exactly").assertSuccess();
    workspace.runBuckBuild("//:binary_matches_default").assertSuccess();
    ProcessResult result = workspace.runBuckBuild("//:binary_no_match");
    result.assertFailure();
    assertThat(result.getStderr(), allOf(containsString("non-void"), containsString("function")));
    workspace.runBuckBuild("//:binary_with_library_matches_default").assertSuccess();
  }

  @Test
  public void platformHeaders() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "platform_headers", tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[cxx]\n  cxxflags = -Wall -Werror", ".buckconfig");
    workspace.runBuckBuild("//:binary_matches_default_exactly").assertSuccess();
    workspace.runBuckBuild("//:binary_matches_default").assertSuccess();
    ProcessResult result = workspace.runBuckBuild("//:binary_no_match");
    result.assertFailure();
    assertThat(result.getStderr(), containsString("header.hpp"));
    workspace.runBuckBuild("//:binary_with_library_matches_default").assertSuccess();
  }

  @Test
  public void platformSources() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "platform_sources", tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[cxx]\n  cxxflags = -Wall -Werror", ".buckconfig");
    workspace.runBuckBuild("//:binary_matches_default_exactly").assertSuccess();
    workspace.runBuckBuild("//:binary_matches_default").assertSuccess();
    ProcessResult result = workspace.runBuckBuild("//:binary_no_match");
    result.assertFailure();
    assertThat(result.getStderr(), containsString("answer()"));
    workspace.runBuckBuild("//:binary_with_library_matches_default").assertSuccess();
  }

  @Test
  public void buildABinaryIfACxxLibraryDepOnlyDeclaresHeaders() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_binary_headers_only", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:binary");

    result.assertSuccess();
  }

  @Test
  public void buildABinaryIfACxxBinaryTransitivelyDepOnlyDeclaresHeaders() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_binary_headers_only", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:transitive");
    System.out.println(result.getStdout());
    System.err.println(result.getStderr());

    result.assertSuccess();
  }

  @Test
  public void buildBinaryWithSharedDependencies() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "shared_library", tmp);
    workspace.setUp();
    ProcessResult processResult = workspace.runBuckBuild("//:clowny_binary");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString("in the dependencies have the same output filename"));
  }

  @Test
  public void buildBinaryWithPerFileFlags() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "per_file_flags", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckBuild("//:binary");
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "static_pic_link_style", tmp);
    workspace.setUp();
    workspace
        .runBuckCommand(
            "build",
            // This should only work (on some architectures) if PIC was used to build all included
            // object files.
            "--config",
            "cxx.cxxldflags=-shared",
            "//:bar")
        .assertSuccess();
  }

  @Test
  public void testStrippedBinaryProducesBothUnstrippedAndStrippedOutputs()
      throws IOException, InterruptedException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    BuildTarget unstrippedTarget = BuildTargetFactory.newInstance("//:test");
    BuildTarget strippedTarget =
        unstrippedTarget.withAppendedFlavors(StripStyle.DEBUGGING_SYMBOLS.getFlavor());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "header_namespace", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    workspace
        .runBuckCommand(
            "build", "--config", "cxx.cxxflags=-g", strippedTarget.getFullyQualifiedName())
        .assertSuccess();

    Path strippedPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
                strippedTarget.withAppendedFlavors(CxxStrip.RULE_FLAVOR),
                "%s"));
    Path unstrippedPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), unstrippedTarget, "%s"));

    String strippedOut =
        workspace.runCommand("dsymutil", "-s", strippedPath.toString()).getStdout().orElse("");
    String unstrippedOut =
        workspace.runCommand("dsymutil", "-s", unstrippedPath.toString()).getStdout().orElse("");

    assertThat(strippedOut, containsStringIgnoringCase("dyld_stub_binder"));
    assertThat(unstrippedOut, containsStringIgnoringCase("dyld_stub_binder"));

    assertThat(strippedOut, not(containsStringIgnoringCase("test.cpp")));
    assertThat(unstrippedOut, containsStringIgnoringCase("test.cpp"));
  }

  @Test
  public void testStrippedBinaryCanBeFetchedFromCacheAlone() throws Exception {
    assumeThat(Platform.detect(), oneOf(Platform.LINUX, Platform.MACOS));

    BuildTarget strippedTarget =
        BuildTargetFactory.newInstance("//:test")
            .withFlavors(StripStyle.DEBUGGING_SYMBOLS.getFlavor());
    BuildTarget unstrippedTarget =
        strippedTarget.withoutFlavors(StripStyle.FLAVOR_DOMAIN.getFlavors());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "header_namespace", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    workspace
        .runBuckCommand(
            "build", "--config", "cxx.cxxflags=-g", strippedTarget.getFullyQualifiedName())
        .assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace
        .runBuckCommand(
            "build", "--config", "cxx.cxxflags=-g", strippedTarget.getFullyQualifiedName())
        .assertSuccess();

    Path strippedPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
                strippedTarget.withAppendedFlavors(CxxStrip.RULE_FLAVOR),
                "%s"));
    Path unstrippedPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), unstrippedTarget, "%s"));

    assertThat(Files.exists(strippedPath), equalTo(true));
    assertThat(Files.exists(unstrippedPath), equalTo(false));
  }

  @Test
  public void stripRuleCanBeMadeUncachable() throws Exception {
    assumeThat(Platform.detect(), oneOf(Platform.LINUX, Platform.MACOS));

    BuildTarget strippedTarget =
        BuildTargetFactory.newInstance("//:test")
            .withFlavors(StripStyle.DEBUGGING_SYMBOLS.getFlavor());
    BuildTarget unstrippedTarget =
        strippedTarget.withoutFlavors(StripStyle.FLAVOR_DOMAIN.getFlavors());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "header_namespace", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    workspace
        .runBuckCommand(
            "build",
            "--config",
            "cxx.cxxflags=-g",
            "--config",
            "cxx.cache_strips=false",
            strippedTarget.getFullyQualifiedName())
        .assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace
        .runBuckCommand(
            "build",
            "--config",
            "cxx.cxxflags=-g",
            "--config",
            "cxx.cache_strips=false",
            strippedTarget.getFullyQualifiedName())
        .assertSuccess();

    Path strippedPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
                strippedTarget.withAppendedFlavors(CxxStrip.RULE_FLAVOR),
                "%s"));
    Path unstrippedPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), unstrippedTarget, "%s"));

    // The unstripped path should be materialized because the strip rule is set to not cache.
    assertTrue(Files.exists(strippedPath));
    assertTrue(Files.exists(unstrippedPath));
  }

  @Test
  public void testStrippedBinaryOutputDiffersFromUnstripped() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    BuildTarget unstrippedTarget = BuildTargetFactory.newInstance("//:test");
    BuildTarget strippedTarget =
        unstrippedTarget.withFlavors(StripStyle.DEBUGGING_SYMBOLS.getFlavor());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "header_namespace", tmp);
    workspace.setUp();
    ProcessResult strippedResult =
        workspace.runBuckCommand(
            "targets", "--show-output", strippedTarget.getFullyQualifiedName());
    strippedResult.assertSuccess();

    ProcessResult unstrippedResult =
        workspace.runBuckCommand(
            "targets", "--show-output", unstrippedTarget.getFullyQualifiedName());
    unstrippedResult.assertSuccess();

    String strippedOutput = strippedResult.getStdout().split(" ")[1];
    String unstrippedOutput = unstrippedResult.getStdout().split(" ")[1];
    assertThat(strippedOutput, not(equalTo(unstrippedOutput)));
  }

  @Test
  public void testBuildingWithAndWithoutLinkerMap() throws Exception {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    BuildTarget withoutLinkerMapTarget =
        target.withAppendedFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "header_namespace", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    workspace
        .runBuckCommand("build", "--config", "cxx.cxxflags=-g", target.getFullyQualifiedName())
        .assertSuccess();

    Path binaryWithLinkerMapPath =
        workspace.getPath(BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"));
    Path linkerMapPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s-LinkMap.txt"));
    assertThat(Files.exists(binaryWithLinkerMapPath), equalTo(true));
    assertThat(Files.exists(linkerMapPath), equalTo(true));

    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();

    workspace
        .runBuckCommand(
            "build", "--config", "cxx.cxxflags=-g", withoutLinkerMapTarget.getFullyQualifiedName())
        .assertSuccess();

    Path binaryWithoutLinkerMapPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), withoutLinkerMapTarget, "%s"));
    linkerMapPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), withoutLinkerMapTarget, "%s-LinkMap.txt"));
    assertThat(Files.exists(binaryWithoutLinkerMapPath), equalTo(true));
    assertThat(Files.exists(linkerMapPath), equalTo(false));
  }

  @Test
  public void testDisablingLinkCaching() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    workspace.runBuckBuild("-c", "cxx.cache_links=false", "//foo:simple").assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache");
    workspace.runBuckBuild("-c", "cxx.cache_links=false", "//foo:simple").assertSuccess();
    workspace
        .getBuildLog()
        .assertTargetBuiltLocally(
            CxxDescriptionEnhancer.createCxxLinkTarget(
                BuildTargetFactory.newInstance("//foo:simple"), Optional.empty()));
  }

  @Test
  public void testThinArchives() throws IOException {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(
            new CxxBuckConfig(FakeBuckConfig.empty()), DEFAULT_DOWNWARD_API_CONFIG);
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    assumeTrue(
        cxxPlatform
            .getAr()
            .resolve(ruleResolver, UnconfiguredTargetConfiguration.INSTANCE)
            .supportsThinArchives());
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    workspace
        .runBuckBuild(
            "-c",
            "cxx.cache_links=false",
            "-c",
            "cxx.archive_contents=thin",
            "//foo:binary_with_dep")
        .assertSuccess();
    ImmutableSortedSet<Path> initialObjects =
        findFiles(
            tmp.getRoot().resolve("buck-out/gen"),
            tmp.getRoot().getFileSystem().getPathMatcher("glob:**/*.o"));

    // sanity check
    assertFalse(initialObjects.isEmpty());

    workspace.runBuckCommand("clean", "--keep-cache");
    workspace
        .runBuckBuild(
            "-c",
            "cxx.cache_links=false",
            "-c",
            "cxx.archive_contents=thin",
            "//foo:binary_with_dep")
        .assertSuccess();
    workspace
        .getBuildLog()
        .assertTargetBuiltLocally(
            CxxDescriptionEnhancer.createCxxLinkTarget(
                BuildTargetFactory.newInstance("//foo:binary_with_dep"), Optional.empty()));
    ImmutableSortedSet<Path> subsequentObjects =
        findFiles(
            tmp.getRoot().resolve("buck-out/gen"),
            tmp.getRoot().getFileSystem().getPathMatcher("glob:**/*.o"));
    assertThat(initialObjects, equalTo(subsequentObjects));
  }

  /**
   * Tests that, if a file has to be rebuilt, but its header dependencies do not, that the header
   * tree is still generated into the correct location.
   */
  @Test
  public void headersShouldBeSetUpCorrectlyOnRebuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "cxx_binary_dep_header_tree_materialize", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    workspace.runBuckBuild("//:bin").assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache");
    workspace.copyFile("bin.c.new", "bin.c");
    workspace.runBuckBuild("//:bin").assertSuccess();
    BuckBuildLog log = workspace.getBuildLog();
    log.assertTargetBuiltLocally("//:bin#binary");
  }

  /** Tests --config cxx.declared_platforms */
  @Test
  public void testDeclaredPlatforms() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "declared_platforms", tmp);
    workspace.setUp();
    workspace
        .runBuckCommand("query", "-c", "cxx.declared_platforms=my-favorite-platform", "//:simple")
        .assertSuccess();
  }

  @Test
  public void testDeclaredPlatformsWithDefaultPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "declared_platforms", tmp);
    workspace.setUp();
    workspace
        .runBuckCommand("query", "-c", "cxx.declared_platforms=my-favorite-platform", "//:defaults")
        .assertSuccess();

    // Currently failing
    workspace
        .runBuckCommand(
            "query", "-c", "cxx.declared_platforms=my-favorite-platform", "//:default_platform")
        .assertFailure();
  }

  @Test
  public void targetsInPlatformSpecificFlagsDoNotBecomeDependencies() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "targets_in_platform_specific_flags_do_not_become_dependencies", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckBuild(":bin");
    result.assertSuccess();
  }

  @Test
  public void conflictingHeadersBuildFails() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "headers_conflicts", tmp);
    workspace.setUp();
    String errorMsg = workspace.runBuckBuild(":main").assertFailure().getStderr();
    assertTrue(
        errorMsg.contains(
            "has dependencies using headers that can be included using the same path"));
  }

  @Test
  public void conflictingHeadersWithWhitelistSucceeds() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "headers_conflicts", tmp);
    workspace.setUp();
    workspace
        .runBuckBuild("-c", "cxx.conflicting_header_basename_whitelist=public.h", ":main")
        .assertSuccess();
  }

  @Test
  public void testLinkMapCreated() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_binary_linkmap", tmp);
    workspace.setUp();
    workspace.runBuckBuild(":binary#linkmap").assertSuccess();
  }

  @Test
  public void testLinkMapNotCreated() throws IOException {
    assumeThat(Platform.detect(), is(Platform.LINUX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_binary_linkmap", tmp);
    workspace.setUp();
    try {
      workspace.runBuckBuild(":binary#linkmap");
    } catch (HumanReadableException e) {
      assertEquals(
          "Linker for target //:binary#linkmap does not support linker maps.",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testRunFlavors() throws IOException {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_flavors", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//bin:bin").assertSuccess("build //bin:bin1");
    workspace.runBuckCommand("run", "//bin:bin").assertSuccess("run //bin:bin1");
    workspace.runBuckCommand("build", "//bin:bin#default").assertSuccess("build //bin:bin#default");
    workspace.runBuckCommand("run", "//bin:bin#default").assertSuccess("run //bin:bin#default");
    workspace.runBuckCommand("build", "//bin:bin1").assertSuccess("build //bin:bin1");
    workspace.runBuckCommand("run", "//bin:bin1").assertSuccess("run //bin:bin1");
  }

  @Test
  public void testResources() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_test_resources", tmp);
    workspace.setUp();
    Path path =
        workspace.getDestPath().resolve(workspace.buildAndReturnRelativeOutput("//foo:bin"));
    File file = new File(path + ".resources.json");
    Map<String, String> result =
        new ObjectMapper().readValue(file, new TypeReference<Map<String, String>>() {});
    assertThat(
        result,
        equalTo(
            ImmutableMap.of(
                "foo/resource.txt",
                "resource/resource.txt",
                "foo/lib_resource.txt",
                "../../../../foo/lib_resource.txt")));
  }

  @Test
  public void inplaceSharedBinaryAvoidsHashedBuckOutCompatHardLinking() throws IOException {
    assumeThat(Platform.detect(), is(not(Platform.WINDOWS)));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "shared_link_style", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:bar");
    Path path =
        workspace.buildAndReturnOutput(
            "-c",
            "project.buck_out_links_to_hashed_paths=hardlink",
            target.getFullyQualifiedName());
    Path linkedPath = BuildPaths.removeHashFrom(path, target).get();
    assertTrue(Files.isSymbolicLink(linkedPath));
  }

  private ImmutableSortedSet<Path> findFiles(AbsPath root, PathMatcher matcher) throws IOException {
    ImmutableSortedSet.Builder<Path> files = ImmutableSortedSet.naturalOrder();
    Files.walkFileTree(
        root.getPath(),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (matcher.matches(file)) {
              files.add(file);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return files.build();
  }

  private static ImmutableSet<String> getUniqueLines(String str) {
    return ImmutableSet.copyOf(Splitter.on('\n').omitEmptyStrings().split(str));
  }
}
