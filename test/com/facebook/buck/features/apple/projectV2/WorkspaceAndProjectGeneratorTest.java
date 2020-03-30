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

package com.facebook.buck.features.apple.projectV2;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleBinaryBuilder;
import com.facebook.buck.apple.AppleBundleBuilder;
import com.facebook.buck.apple.AppleBundleDescriptionArg;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.apple.AppleLibraryDescriptionArg;
import com.facebook.buck.apple.AppleTestBuilder;
import com.facebook.buck.apple.XCodeDescriptions;
import com.facebook.buck.apple.XCodeDescriptionsFactory;
import com.facebook.buck.apple.xcode.XCScheme;
import com.facebook.buck.apple.xcode.XCScheme.AdditionalActions;
import com.facebook.buck.apple.xcode.XCScheme.BuildActionEntry;
import com.facebook.buck.apple.xcode.XCScheme.BuildActionEntry.BuildFor;
import com.facebook.buck.apple.xcode.XCScheme.LaunchAction.WatchInterface;
import com.facebook.buck.apple.xcode.XCScheme.SchemePrePostAction;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.ProductTypes;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProviderBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.TestTargetGraphCreationResultFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.impl.DefaultCxxPlatforms;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.features.apple.common.SchemeActionType;
import com.facebook.buck.features.apple.common.XcodeWorkspaceConfigDescriptionArg;
import com.facebook.buck.features.halide.HalideBuckConfig;
import com.facebook.buck.features.halide.HalideLibraryBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.shell.GenruleDescriptionArg;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.IncrementingFakeClock;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.core.AllOf;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class WorkspaceAndProjectGeneratorTest {

  private static final CxxPlatform DEFAULT_PLATFORM = CxxPlatformUtils.DEFAULT_PLATFORM;
  private static final Path OUTPUT_DIRECTORY = Paths.get("_gen");

  private XCodeDescriptions xcodeDescriptions;
  private Cells cells;
  private HalideBuckConfig halideBuckConfig;
  private CxxBuckConfig cxxBuckConfig;
  private AppleConfig appleConfig;
  private SwiftBuckConfig swiftBuckConfig;

  private TargetGraph targetGraph;
  private TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceNode;
  private TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceWithExtraSchemeNode;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    xcodeDescriptions =
        XCodeDescriptionsFactory.create(BuckPluginManagerFactory.createPluginManager());
    cells = (new TestCellBuilder()).build();
    ProjectFilesystem projectFilesystem = cells.getRootCell().getFilesystem();
    BuckConfig fakeBuckConfig = FakeBuckConfig.builder().build();
    halideBuckConfig = HalideLibraryBuilder.createDefaultHalideConfig(projectFilesystem);
    cxxBuckConfig = new CxxBuckConfig(fakeBuckConfig);
    appleConfig = AppleProjectHelper.createDefaultAppleConfig(projectFilesystem);
    swiftBuckConfig = new SwiftBuckConfig(fakeBuckConfig);
    setUpWorkspaceAndProjects();
  }

  private void setUpWorkspaceAndProjects() {
    // Create the following dep tree:
    // FooBin -has-test-> FooBinTest
    // |
    // V
    // FooLib -has-test-> FooLibTest
    // |                  |
    // V                  V
    // BarLib             BazLib -has-test-> BazLibTest
    // ^
    // |
    // QuxBin
    //
    //
    // FooBin and BazLib and FooLibTest use "tests" to specify their tests.
    //
    // Calling generate on FooBin should pull in everything except BazLibTest and QuxBin.

    BuildTarget bazTestTarget = BuildTargetFactory.newInstance("//baz", "xctest");
    BuildTarget fooBinTestTarget = BuildTargetFactory.newInstance("//foo", "bin-xctest");
    BuildTarget fooTestTarget = BuildTargetFactory.newInstance("//foo", "lib-xctest");

    BuildTarget barLibTarget = BuildTargetFactory.newInstance("//bar", "libbar");
    TargetNode<?> barLibNode = AppleLibraryBuilder.createBuilder(barLibTarget).build();

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo", "lib");
    TargetNode<?> fooLibNode =
        AppleLibraryBuilder.createBuilder(fooLibTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .setTests(ImmutableSortedSet.of(fooTestTarget))
            .build();

    BuildTarget fooBinBinaryTarget = BuildTargetFactory.newInstance("//foo", "binbinary");
    TargetNode<?> fooBinBinaryNode =
        AppleBinaryBuilder.createBuilder(fooBinBinaryTarget)
            .setDeps(ImmutableSortedSet.of(fooLibTarget))
            .build();

    BuildTarget fooBinTarget = BuildTargetFactory.newInstance("//foo", "bin");
    TargetNode<?> fooBinNode =
        AppleBundleBuilder.createBuilder(fooBinTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .setBinary(fooBinBinaryTarget)
            .setTests(ImmutableSortedSet.of(fooBinTestTarget))
            .build();

    BuildTarget bazLibTarget = BuildTargetFactory.newInstance("//baz", "lib");
    TargetNode<?> bazLibNode =
        AppleLibraryBuilder.createBuilder(bazLibTarget)
            .setDeps(ImmutableSortedSet.of(fooLibTarget))
            .setTests(ImmutableSortedSet.of(bazTestTarget))
            .build();

    TargetNode<?> bazTestNode =
        AppleTestBuilder.createBuilder(bazTestTarget)
            .setDeps(ImmutableSortedSet.of(bazLibTarget))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    TargetNode<?> fooTestNode =
        AppleTestBuilder.createBuilder(fooTestTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .setDeps(ImmutableSortedSet.of(bazLibTarget))
            .build();

    TargetNode<?> fooBinTestNode =
        AppleTestBuilder.createBuilder(fooBinTestTarget)
            .setDeps(ImmutableSortedSet.of(fooBinTarget))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    BuildTarget quxBinTarget = BuildTargetFactory.newInstance("//qux", "bin");
    TargetNode<?> quxBinNode =
        AppleBinaryBuilder.createBuilder(quxBinTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .build();

    BuildTarget workspaceTarget = BuildTargetFactory.newInstance("//foo", "workspace");
    workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(workspaceTarget)
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooBinTarget))
            .build();

    targetGraph =
        TargetGraphFactory.newInstance(
            barLibNode,
            fooLibNode,
            fooBinBinaryNode,
            fooBinNode,
            bazLibNode,
            bazTestNode,
            fooTestNode,
            fooBinTestNode,
            quxBinNode,
            workspaceNode);
  }

  private BuckEventBus getFakeBuckEventBus() {
    return BuckEventBusForTests.newInstance(new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1)));
  }

  @Test
  public void workspaceAndProjectsShouldDiscoverDependenciesAndTests()
      throws IOException, InterruptedException, ExecutionException {
    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            cells.getRootCell(),
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            FocusedTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            ImmutableSet.of(),
            "BUCK",
            getActionGraphBuilderForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            TestRuleKeyConfigurationFactory.create(),
            halideBuckConfig,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig,
            Optional.empty());
    WorkspaceAndProjectGenerator.Result result =
        generator.generateWorkspaceAndDependentProjects(MoreExecutors.newDirectExecutorService());

    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//foo:bin");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//foo:lib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//foo:bin-xctest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//foo:lib-xctest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//bar:libbar");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//baz:lib");
  }

  @Test
  public void workspaceAndProjectsWithoutTests()
      throws IOException, InterruptedException, ExecutionException {
    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            cells.getRootCell(),
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder().build(),
            FocusedTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            ImmutableSet.of(),
            "BUCK",
            getActionGraphBuilderForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            TestRuleKeyConfigurationFactory.create(),
            halideBuckConfig,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig,
            Optional.empty());
    WorkspaceAndProjectGenerator.Result result =
        generator.generateWorkspaceAndDependentProjects(MoreExecutors.newDirectExecutorService());

    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//foo:bin");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//foo:lib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//bar:libbar");
  }

  @Test
  public void workspaceAndProjectsWithoutDependenciesTests()
      throws IOException, InterruptedException {
    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            cells.getRootCell(),
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldGenerateProjectSchemes(true)
                .build(),
            FocusedTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            ImmutableSet.of(),
            "BUCK",
            getActionGraphBuilderForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            TestRuleKeyConfigurationFactory.create(),
            halideBuckConfig,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig,
            Optional.empty());

    generator.generateWorkspaceAndDependentProjects(MoreExecutors.newDirectExecutorService());
    assertEquals(generator.getSchemeGenerators().size(), 1);

    // validate main scheme values
    Optional<XCScheme> mainScheme =
        generator.getSchemeGenerators().get("workspace").getOutputScheme();

    assertThat(mainScheme.isPresent(), is(true));

    XCScheme.BuildAction mainSchemeBuildAction = mainScheme.get().getBuildAction().get();
    List<BuildActionEntry> mainSchemeBuildActionEntries =
        mainSchemeBuildAction.getBuildActionEntries();
    assertThat(mainSchemeBuildActionEntries, hasSize(4));
    assertThat( // foo:bin
        mainSchemeBuildActionEntries,
        hasItem(withNameAndBuildingFor("bin", equalTo(BuildFor.MAIN_EXECUTABLE))));
    assertThat( // foo:bin-xctest
        mainSchemeBuildActionEntries,
        hasItem(withNameAndBuildingFor("bin-xctest", equalTo(BuildFor.TEST_ONLY))));
    assertThat( // foo:lib
        mainSchemeBuildActionEntries,
        hasItem(
            withNameAndBuildingFor(
                "lib", equalTo(XCScheme.BuildActionEntry.BuildFor.INDEXING_ONLY))));
    assertThat( // bar:libbar
        mainSchemeBuildActionEntries,
        hasItem(
            withNameAndBuildingFor(
                "libbar", equalTo(XCScheme.BuildActionEntry.BuildFor.INDEXING_ONLY))));

    XCScheme.TestAction mainSchemeTestAction = mainScheme.get().getTestAction().get();
    assertThat(mainSchemeTestAction.getTestables(), hasSize(1));
    assertThat( // foo:bin-xctest
        mainSchemeTestAction.getTestables(), hasItem(withName("bin-xctest")));
  }

  @Test
  public void requiredBuildTargets() throws IOException, InterruptedException, ExecutionException {
    BuildTarget genruleTarget = BuildTargetFactory.newInstance("//foo", "gen");
    TargetNode<GenruleDescriptionArg> genrule =
        GenruleBuilder.newGenruleBuilder(genruleTarget).setOut("source.m").build();

    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//foo", "lib");
    TargetNode<AppleLibraryDescriptionArg> library =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(DefaultBuildTargetSourcePath.of(genruleTarget))))
            .build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTargetFactory.newInstance("//foo", "workspace"))
            .setSrcTarget(Optional.of(libraryTarget))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(genrule, library, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            cells.getRootCell(),
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder().build(),
            FocusedTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            ImmutableSet.of(),
            "BUCK",
            getActionGraphBuilderForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            TestRuleKeyConfigurationFactory.create(),
            halideBuckConfig,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig,
            Optional.empty());

    generator.generateWorkspaceAndDependentProjects(MoreExecutors.newDirectExecutorService());
    assertEquals(generator.getRequiredBuildTargets(), ImmutableSet.of(genruleTarget));
  }

  private void setUpWorkspaceWithSchemeAndProjects() {
    // Create the following dep tree:
    // FooBin -has-test-> FooBinTest
    // |
    // V
    // FooLib -has-test-> FooLibTest
    // |                  |
    // V                  V
    // BarLib             BazLib -has-test-> BazLibTest
    // ^
    // |
    // QuxBin
    //
    //
    // FooBin and BazLib and FooLibTest use "tests" to specify their tests.
    //
    // Calling generate on FooBin should pull in everything except BazLibTest and QuxBin.

    BuildTarget bazTestTarget = BuildTargetFactory.newInstance("//baz", "BazTest");
    BuildTarget fooBinTestTarget = BuildTargetFactory.newInstance("//foo", "FooBinTest");
    BuildTarget fooTestTarget = BuildTargetFactory.newInstance("//foo", "FooLibTest");

    BuildTarget barLibTarget = BuildTargetFactory.newInstance("//bar", "BarLib");
    TargetNode<?> barLibNode = AppleLibraryBuilder.createBuilder(barLibTarget).build();

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo", "FooLib");
    TargetNode<?> fooLibNode =
        AppleLibraryBuilder.createBuilder(fooLibTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .setTests(ImmutableSortedSet.of(fooTestTarget))
            .build();

    BuildTarget fooBinBinaryTarget = BuildTargetFactory.newInstance("//foo", "FooBinBinary");
    TargetNode<?> fooBinBinaryNode =
        AppleBinaryBuilder.createBuilder(fooBinBinaryTarget)
            .setDeps(ImmutableSortedSet.of(fooLibTarget))
            .build();

    BuildTarget fooBinTarget = BuildTargetFactory.newInstance("//foo", "FooBin");
    TargetNode<?> fooBinNode =
        AppleBundleBuilder.createBuilder(fooBinTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .setBinary(fooBinBinaryTarget)
            .setTests(ImmutableSortedSet.of(fooBinTestTarget))
            .build();

    BuildTarget bazLibTarget = BuildTargetFactory.newInstance("//baz", "BazLib");
    TargetNode<?> bazLibNode =
        AppleLibraryBuilder.createBuilder(bazLibTarget)
            .setDeps(ImmutableSortedSet.of(fooLibTarget))
            .setTests(ImmutableSortedSet.of(bazTestTarget))
            .build();

    TargetNode<?> bazTestNode =
        AppleTestBuilder.createBuilder(bazTestTarget)
            .setDeps(ImmutableSortedSet.of(bazLibTarget))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    TargetNode<?> fooTestNode =
        AppleTestBuilder.createBuilder(fooTestTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .setDeps(ImmutableSortedSet.of(bazLibTarget))
            .build();

    TargetNode<?> fooBinTestNode =
        AppleTestBuilder.createBuilder(fooBinTestTarget)
            .setDeps(ImmutableSortedSet.of(fooBinTarget))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    BuildTarget quxBinTarget = BuildTargetFactory.newInstance("//qux", "QuxBin");
    TargetNode<?> quxBinNode =
        AppleBinaryBuilder.createBuilder(quxBinTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .build();

    BuildTarget workspaceTarget = BuildTargetFactory.newInstance("//foo", "workspace");
    workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(workspaceTarget)
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooBinTarget))
            .build();

    BuildTarget workspaceWithExtraSchemeTarget =
        BuildTargetFactory.newInstance("//qux", "workspace");
    workspaceWithExtraSchemeNode =
        XcodeWorkspaceConfigBuilder.createBuilder(workspaceWithExtraSchemeTarget)
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(quxBinTarget))
            .setExtraSchemes(ImmutableSortedMap.of("FooScheme", workspaceTarget))
            .build();

    targetGraph =
        TargetGraphFactory.newInstance(
            barLibNode,
            fooLibNode,
            fooBinBinaryNode,
            fooBinNode,
            bazLibNode,
            bazTestNode,
            fooTestNode,
            fooBinTestNode,
            quxBinNode,
            workspaceNode,
            workspaceWithExtraSchemeNode);
  }

  @Test
  public void targetsForWorkspaceWithExtraSchemes() throws IOException, InterruptedException {
    setUpWorkspaceWithSchemeAndProjects();

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            cells.getRootCell(),
            targetGraph,
            workspaceWithExtraSchemeNode.getConstructorArg(),
            workspaceWithExtraSchemeNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            FocusedTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            ImmutableSet.of(),
            "BUCK",
            getActionGraphBuilderForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            TestRuleKeyConfigurationFactory.create(),
            halideBuckConfig,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig,
            Optional.empty());
    WorkspaceAndProjectGenerator.Result result =
        generator.generateWorkspaceAndDependentProjects(MoreExecutors.newDirectExecutorService());

    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//foo:FooBin");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//foo:FooLib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//foo:FooBinTest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//foo:FooLibTest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//bar:BarLib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//baz:BazLib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//qux:QuxBin");

    XCScheme mainScheme = generator.getSchemeGenerators().get("workspace").getOutputScheme().get();
    XCScheme.BuildAction mainSchemeBuildAction = mainScheme.getBuildAction().get();
    // I wish we could use Hamcrest contains() here, but we hit
    // https://code.google.com/p/hamcrest/issues/detail?id=190 if we do that.
    assertThat(mainSchemeBuildAction.getBuildActionEntries(), hasSize(2));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(0),
        withNameAndBuildingFor(
            "BarLib", equalTo(XCScheme.BuildActionEntry.BuildFor.INDEXING_ONLY)));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(1),
        withNameAndBuildingFor(
            "QuxBin", equalTo(XCScheme.BuildActionEntry.BuildFor.SCHEME_LIBRARY)));

    XCScheme fooScheme = generator.getSchemeGenerators().get("FooScheme").getOutputScheme().get();
    XCScheme.BuildAction fooSchemeBuildAction = fooScheme.getBuildAction().get();
    assertThat(fooSchemeBuildAction.getBuildActionEntries(), hasSize(6));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(0),
        withNameAndBuildingFor(
            "BarLib", equalTo(XCScheme.BuildActionEntry.BuildFor.INDEXING_ONLY)));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(1),
        withNameAndBuildingFor(
            "FooLib", equalTo(XCScheme.BuildActionEntry.BuildFor.INDEXING_ONLY)));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(2),
        withNameAndBuildingFor("FooBin", equalTo(BuildFor.MAIN_EXECUTABLE)));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(3),
        withNameAndBuildingFor(
            "FooBinTest", equalTo(XCScheme.BuildActionEntry.BuildFor.TEST_ONLY)));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(4),
        withNameAndBuildingFor(
            "BazLib", equalTo(XCScheme.BuildActionEntry.BuildFor.INDEXING_ONLY)));

    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(5),
        withNameAndBuildingFor(
            "FooLibTest", equalTo(XCScheme.BuildActionEntry.BuildFor.TEST_ONLY)));
  }

  @Test
  public void targetsForWorkspaceWithExtraTargets() throws IOException, InterruptedException {
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo", "FooLib");
    TargetNode<AppleLibraryDescriptionArg> fooLib =
        AppleLibraryBuilder.createBuilder(fooLibTarget).build();

    BuildTarget barLibTarget = BuildTargetFactory.newInstance("//bar", "BarLib");
    TargetNode<AppleLibraryDescriptionArg> barLib =
        AppleLibraryBuilder.createBuilder(barLibTarget).build();

    BuildTarget bazLibTarget = BuildTargetFactory.newInstance("//baz", "BazLib");
    TargetNode<AppleLibraryDescriptionArg> bazLib =
        AppleLibraryBuilder.createBuilder(bazLibTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTargetFactory.newInstance("//foo", "workspace"))
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooLibTarget))
            .setExtraTargets(ImmutableSortedSet.of(bazLibTarget))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(fooLib, barLib, bazLib, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            cells.getRootCell(),
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            FocusedTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            ImmutableSet.of(),
            "BUCK",
            getActionGraphBuilderForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            TestRuleKeyConfigurationFactory.create(),
            halideBuckConfig,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig,
            Optional.empty());
    WorkspaceAndProjectGenerator.Result result =
        generator.generateWorkspaceAndDependentProjects(MoreExecutors.newDirectExecutorService());

    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//foo:FooLib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//bar:BarLib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(result.project, "//baz:BazLib");

    XCScheme mainScheme = generator.getSchemeGenerators().get("workspace").getOutputScheme().get();
    XCScheme.BuildAction mainSchemeBuildAction = mainScheme.getBuildAction().get();
    // I wish we could use Hamcrest contains() here, but we hit
    // https://code.google.com/p/hamcrest/issues/detail?id=190 if we do that.
    assertThat(mainSchemeBuildAction.getBuildActionEntries(), hasSize(3));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(0),
        withNameAndBuildingFor(
            "FooLib", equalTo(XCScheme.BuildActionEntry.BuildFor.SCHEME_LIBRARY)));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(1),
        withNameAndBuildingFor(
            "BarLib", equalTo(XCScheme.BuildActionEntry.BuildFor.INDEXING_ONLY)));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(2),
        withNameAndBuildingFor(
            "BazLib", equalTo(XCScheme.BuildActionEntry.BuildFor.INDEXING_ONLY)));
  }

  @Test
  public void targetsWithModularDepsAreBuilt() throws IOException, InterruptedException {
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo", "FooLib");
    TargetNode<AppleLibraryDescriptionArg> fooLib =
        AppleLibraryBuilder.createBuilder(fooLibTarget).setModular(true).build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTargetFactory.newInstance("//foo", "workspace"))
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooLibTarget))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(fooLib, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            cells.getRootCell(),
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            FocusedTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            ImmutableSet.of(),
            "BUCK",
            getActionGraphBuilderForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            TestRuleKeyConfigurationFactory.create(),
            halideBuckConfig,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig,
            Optional.empty());
    generator.generateWorkspaceAndDependentProjects(MoreExecutors.newDirectExecutorService());

    BuildTarget expectedTarget =
        NodeHelper.getModularMapTarget(
            fooLib,
            HeaderMode.forModuleMapMode(appleConfig.moduleMapMode()),
            DEFAULT_PLATFORM.getFlavor());

    assertThat(generator.getRequiredBuildTargets(), hasSize(1));
    assertThat(
        generator.getRequiredBuildTargets().stream().findFirst().get().getFullyQualifiedName(),
        equalTo(expectedTarget.getFullyQualifiedName()));
  }

  @Test
  public void targetsForWorkspaceWithImplicitExtensionTargets()
      throws IOException, InterruptedException {
    BuildTarget barShareExtensionBinaryTarget =
        BuildTargetFactory.newInstance("//foo", "BarShareExtensionBinary");
    TargetNode<?> barShareExtensionBinary =
        AppleBinaryBuilder.createBuilder(barShareExtensionBinaryTarget).build();

    BuildTarget barShareExtensionTarget =
        BuildTargetFactory.newInstance("//foo", "BarShareExtension", DefaultCxxPlatforms.FLAVOR);
    TargetNode<AppleBundleDescriptionArg> barShareExtension =
        AppleBundleBuilder.createBuilder(barShareExtensionTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APPEX))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .setBinary(barShareExtensionBinaryTarget)
            .setXcodeProductType(Optional.of(ProductTypes.APP_EXTENSION.getIdentifier()))
            .build();

    BuildTarget barAppBinaryTarget = BuildTargetFactory.newInstance("//foo", "BarAppBinary");
    TargetNode<?> barAppBinary = AppleBinaryBuilder.createBuilder(barAppBinaryTarget).build();

    BuildTarget barAppTarget = BuildTargetFactory.newInstance("//foo", "BarApp");
    TargetNode<AppleBundleDescriptionArg> barApp =
        AppleBundleBuilder.createBuilder(barAppTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .setBinary(barAppBinaryTarget)
            .setDeps(ImmutableSortedSet.of(barShareExtensionTarget))
            .build();

    XcodeWorkspaceConfigDescriptionArg arg =
        XcodeWorkspaceConfigDescriptionArg.builder()
            .setName("workspace")
            .setSrcTarget(barAppTarget)
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            barShareExtensionBinary, barShareExtension, barAppBinary, barApp);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            cells.getRootCell(),
            targetGraph,
            arg,
            barAppTarget,
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            FocusedTargetMatcher.noFocus(),
            true /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            ImmutableSet.of(),
            "BUCK",
            getActionGraphBuilderForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            TestRuleKeyConfigurationFactory.create(),
            halideBuckConfig,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig,
            Optional.empty());
    generator.generateWorkspaceAndDependentProjects(MoreExecutors.newDirectExecutorService());

    assertThat(
        generator.getSchemeGenerators().get("BarApp").getOutputScheme().isPresent(), is(true));
    assertThat(
        generator
            .getSchemeGenerators()
            .get("BarApp+BarShareExtension")
            .getOutputScheme()
            .isPresent(),
        is(true));

    // Validate app scheme
    XCScheme appScheme = generator.getSchemeGenerators().get("BarApp").getOutputScheme().get();
    assertThat(appScheme.getWasCreatedForExtension(), is(false));
    XCScheme.BuildAction appSchemeBuildAction = appScheme.getBuildAction().get();
    assertThat(appSchemeBuildAction.getBuildActionEntries(), hasSize(2));
    assertThat(
        appSchemeBuildAction.getBuildActionEntries().get(0),
        withNameAndBuildingFor(
            "BarShareExtension", equalTo(XCScheme.BuildActionEntry.BuildFor.INDEXING_ONLY)));
    assertThat(
        appSchemeBuildAction.getBuildActionEntries().get(1),
        withNameAndBuildingFor("BarApp", equalTo(BuildFor.MAIN_EXECUTABLE)));

    // Validate extension scheme
    XCScheme extensionScheme =
        generator.getSchemeGenerators().get("BarApp+BarShareExtension").getOutputScheme().get();
    assertThat(extensionScheme.getWasCreatedForExtension(), is(true));
    XCScheme.BuildAction extensionSchemeBuildAction = extensionScheme.getBuildAction().get();
    assertThat(extensionSchemeBuildAction.getBuildActionEntries(), hasSize(2));
    assertThat(
        extensionSchemeBuildAction.getBuildActionEntries().get(0),
        withNameAndBuildingFor("BarApp", equalTo(BuildFor.MAIN_EXECUTABLE)));
    assertThat(
        extensionSchemeBuildAction.getBuildActionEntries().get(1),
        withNameAndBuildingFor(
            "BarShareExtension", equalTo(XCScheme.BuildActionEntry.BuildFor.INDEXING_ONLY)));
  }

  @Test
  public void enablingParallelizeBuild()
      throws IOException, InterruptedException, ExecutionException {
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo", "FooLib");
    TargetNode<AppleLibraryDescriptionArg> fooLib =
        AppleLibraryBuilder.createBuilder(fooLibTarget).build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTargetFactory.newInstance("//foo", "workspace"))
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooLibTarget))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(fooLib, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            cells.getRootCell(),
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            FocusedTargetMatcher.noFocus(),
            true /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            ImmutableSet.of(),
            "BUCK",
            getActionGraphBuilderForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            TestRuleKeyConfigurationFactory.create(),
            halideBuckConfig,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig,
            Optional.empty());
    generator.generateWorkspaceAndDependentProjects(MoreExecutors.newDirectExecutorService());

    XCScheme mainScheme = generator.getSchemeGenerators().get("workspace").getOutputScheme().get();
    XCScheme.BuildAction mainSchemeBuildAction = mainScheme.getBuildAction().get();
    // I wish we could use Hamcrest contains() here, but we hit
    // https://code.google.com/p/hamcrest/issues/detail?id=190 if we do that.
    assertThat(mainSchemeBuildAction.getBuildActionEntries(), hasSize(1));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(0),
        withNameAndBuildingFor(
            "FooLib", equalTo(XCScheme.BuildActionEntry.BuildFor.SCHEME_LIBRARY)));
    assertThat(mainSchemeBuildAction.getParallelizeBuild(), is(true));
  }

  @Test
  public void customRunnableSettings() throws IOException, InterruptedException {
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo", "FooLib");
    TargetNode<AppleLibraryDescriptionArg> fooLib =
        AppleLibraryBuilder.createBuilder(fooLibTarget).build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTargetFactory.newInstance("//foo", "workspace"))
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooLibTarget))
            .setExplicitRunnablePath(Optional.of("/some.app"))
            .setLaunchStyle(Optional.of(XCScheme.LaunchAction.LaunchStyle.WAIT))
            .setWatchInterface(Optional.of(WatchInterface.COMPLICATION))
            .setNotificationPayloadFile(Optional.of("SomeFile.apns"))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(fooLib, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            cells.getRootCell(),
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            FocusedTargetMatcher.noFocus(),
            true /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            ImmutableSet.of(),
            "BUCK",
            getActionGraphBuilderForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            TestRuleKeyConfigurationFactory.create(),
            halideBuckConfig,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig,
            Optional.empty());
    generator.generateWorkspaceAndDependentProjects(MoreExecutors.newDirectExecutorService());

    XCScheme mainScheme = generator.getSchemeGenerators().get("workspace").getOutputScheme().get();
    XCScheme.LaunchAction launchAction = mainScheme.getLaunchAction().get();
    assertThat(launchAction.getRunnablePath().get(), Matchers.equalTo("/some.app"));
    assertThat(
        launchAction.getLaunchStyle(), Matchers.equalTo(XCScheme.LaunchAction.LaunchStyle.WAIT));
    assertThat(
        launchAction.getWatchInterface().get(), Matchers.equalTo(WatchInterface.COMPLICATION));
    assertThat(launchAction.getNotificationPayloadFile().get(), Matchers.equalTo("SomeFile.apns"));
  }

  @Test
  public void customPrePostActions() throws IOException, InterruptedException {
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo", "FooLib");
    TargetNode<AppleLibraryDescriptionArg> fooLib =
        AppleLibraryBuilder.createBuilder(fooLibTarget).build();

    ImmutableMap<SchemeActionType, ImmutableMap<XCScheme.AdditionalActions, ImmutableList<String>>>
        schemeActions =
            ImmutableMap.of(
                SchemeActionType.BUILD,
                ImmutableMap.of(
                    AdditionalActions.PRE_SCHEME_ACTIONS, ImmutableList.of("echo yeha")),
                SchemeActionType.LAUNCH,
                ImmutableMap.of(
                    AdditionalActions.POST_SCHEME_ACTIONS, ImmutableList.of("echo takeoff")));
    TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTargetFactory.newInstance("//foo", "workspace"))
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooLibTarget))
            .setAdditionalSchemeActions(Optional.of(schemeActions))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(fooLib, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            cells.getRootCell(),
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            FocusedTargetMatcher.noFocus(),
            true /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            ImmutableSet.of(),
            "BUCK",
            getActionGraphBuilderForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            TestRuleKeyConfigurationFactory.create(),
            halideBuckConfig,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig,
            Optional.empty());
    generator.generateWorkspaceAndDependentProjects(MoreExecutors.newDirectExecutorService());

    XCScheme mainScheme = generator.getSchemeGenerators().get("workspace").getOutputScheme().get();
    XCScheme.BuildAction buildAction = mainScheme.getBuildAction().get();

    ImmutableList<SchemePrePostAction> preBuild = buildAction.getPreActions().get();
    assertEquals(preBuild.size(), 1);
    assertFalse(buildAction.getPostActions().isPresent());
    assertEquals(preBuild.iterator().next().getCommand(), "echo yeha");

    XCScheme.LaunchAction launchAction = mainScheme.getLaunchAction().get();
    ImmutableList<XCScheme.SchemePrePostAction> postLaunch = launchAction.getPostActions().get();
    assertEquals(postLaunch.size(), 1);
    assertFalse(launchAction.getPreActions().isPresent());
  }

  @Test
  public void testProjectStructureWithDuplicateBundle() throws IOException, InterruptedException {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//foo:lib");
    BuildTarget bundleTarget = BuildTargetFactory.newInstance("//foo:bundle");
    BuildTarget libraryWithFlavorTarget =
        BuildTargetFactory.newInstance("//foo:lib#iphonesimulator-x86_64");
    BuildTarget bundleWithFlavorTarget =
        BuildTargetFactory.newInstance("//foo:bundle#iphonesimulator-x86_64");

    TargetNode<?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("foo.h")))
            .build();
    TargetNode<?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setBinary(libraryTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(FakeSourcePath.of(("Info.plist")))
            .build();

    TargetNode<?> libraryWithFlavorNode =
        AppleLibraryBuilder.createBuilder(libraryWithFlavorTarget)
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("foo.h")))
            .build();
    TargetNode<?> bundleWithFlavorNode =
        AppleBundleBuilder.createBuilder(bundleWithFlavorTarget)
            .setBinary(libraryTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(FakeSourcePath.of(("Info.plist")))
            .build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTargetFactory.newInstance("//foo", "workspace"))
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(libraryTarget))
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            libraryNode, bundleNode, libraryWithFlavorNode, bundleWithFlavorNode, workspaceNode);

    ImmutableSet<Flavor> appleFlavors =
        ImmutableSet.of(InternalFlavor.of("iphonesimulator-x86_64"));
    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            cells.getRootCell(),
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            FocusedTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            appleFlavors,
            "BUCK",
            getActionGraphBuilderForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            TestRuleKeyConfigurationFactory.create(),
            halideBuckConfig,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig,
            Optional.empty());

    WorkspaceAndProjectGenerator.Result result =
        generator.generateWorkspaceAndDependentProjects(MoreExecutors.newDirectExecutorService());

    int count = 0;
    PBXProject project = result.project;
    for (PBXTarget target : project.getTargets()) {
      if (target.getProductName().equals("bundle")) {
        count++;
      }
    }
    assertSame(count, 1);
  }

  /**
   * Ensure target map filters out duplicated targets with an explicit and implicit static flavor.
   * Ensure the filtering prefers the main workspace target over other project targets.
   *
   * @throws IOException
   */
  @Test
  public void ruleToTargetMapFiltersDuplicatePBXTarget() throws IOException, InterruptedException {
    BuildTarget explicitStaticBuildTarget =
        BuildTargetFactory.newInstance("//foo", "lib", CxxDescriptionEnhancer.STATIC_FLAVOR);
    TargetNode<?> explicitStaticNode =
        AppleLibraryBuilder.createBuilder(explicitStaticBuildTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("foo.m"), ImmutableList.of("-foo")),
                    SourceWithFlags.of(FakeSourcePath.of("bar.m"))))
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("foo.h")))
            .build();

    BuildTarget implicitStaticBuildTarget = BuildTargetFactory.newInstance("//foo", "lib");
    TargetNode<?> implicitStaticNode =
        AppleLibraryBuilder.createBuilder(implicitStaticBuildTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("foo.m"), ImmutableList.of("-foo")),
                    SourceWithFlags.of(FakeSourcePath.of("bar.m"))))
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("foo.h")))
            .build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTargetFactory.newInstance("//foo", "workspace"))
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(explicitStaticBuildTarget))
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(explicitStaticNode, implicitStaticNode, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            cells.getRootCell(),
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            FocusedTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            ImmutableSet.of(),
            "BUCK",
            getActionGraphBuilderForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            TestRuleKeyConfigurationFactory.create(),
            halideBuckConfig,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig,
            Optional.empty());

    WorkspaceAndProjectGenerator.Result result =
        generator.generateWorkspaceAndDependentProjects(MoreExecutors.newDirectExecutorService());

    // `implicitStaticBuildTarget` should be filtered out since it duplicates
    // `explicitStaticBuildTarget`, the workspace target, which takes precedence.
    assertEquals(
        explicitStaticBuildTarget,
        Iterables.getOnlyElement(result.buildTargetToPBXTarget.keySet()));

    PBXTarget target = Iterables.getOnlyElement(result.buildTargetToPBXTarget.values());
    PBXTestUtils.assertHasSingleSourcesPhaseWithSourcesAndFlags(
        target,
        ImmutableMap.of(
            "foo.m", Optional.of("-foo"),
            "bar.m", Optional.empty()),
        cells.getRootCell().getFilesystem(),
        OUTPUT_DIRECTORY);
  }

  private Matcher<XCScheme.BuildActionEntry> buildActionEntryWithName(String name) {
    return new FeatureMatcher<XCScheme.BuildActionEntry, String>(
        equalTo(name), "BuildActionEntry named", "name") {
      @Override
      protected String featureValueOf(XCScheme.BuildActionEntry buildActionEntry) {
        return buildActionEntry.getBuildableReference().blueprintName;
      }
    };
  }

  private Matcher<XCScheme.BuildActionEntry> withNameAndBuildingFor(
      String name, Matcher<? super EnumSet<XCScheme.BuildActionEntry.BuildFor>> buildFor) {
    return AllOf.allOf(
        buildActionEntryWithName(name),
        new FeatureMatcher<XCScheme.BuildActionEntry, EnumSet<XCScheme.BuildActionEntry.BuildFor>>(
            buildFor, "Building for", "BuildFor") {
          @Override
          protected EnumSet<XCScheme.BuildActionEntry.BuildFor> featureValueOf(
              XCScheme.BuildActionEntry entry) {
            return entry.getBuildFor();
          }
        });
  }

  private Matcher<XCScheme.TestableReference> testableReferenceWithName(String name) {
    return new FeatureMatcher<XCScheme.TestableReference, String>(
        equalTo(name), "TestableReference named", "name") {
      @Override
      protected String featureValueOf(XCScheme.TestableReference testableReference) {
        return testableReference.getBuildableReference().blueprintName;
      }
    };
  }

  private Matcher<XCScheme.TestableReference> withName(String name) {
    return testableReferenceWithName(name);
  }

  private Function<TargetNode<?>, ActionGraphBuilder> getActionGraphBuilderForNodeFunction(
      TargetGraph targetGraph) {
    return input ->
        new ActionGraphProviderBuilder()
            .build()
            .getFreshActionGraph(
                TestTargetGraphCreationResultFactory.create(
                    targetGraph.getSubgraph(ImmutableSet.of(input))))
            .getActionGraphBuilder();
  }
}
