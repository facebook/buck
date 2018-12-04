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

package com.facebook.buck.features.apple.project;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleBinaryBuilder;
import com.facebook.buck.apple.AppleBundleBuilder;
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
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProviderBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
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
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  private XCodeDescriptions xcodeDescriptions;
  private Cell rootCell;
  private HalideBuckConfig halideBuckConfig;
  private CxxBuckConfig cxxBuckConfig;
  private AppleConfig appleConfig;
  private SwiftBuckConfig swiftBuckConfig;

  private TargetGraph targetGraph;
  private TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceNode;
  private TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceWithExtraSchemeNode;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    xcodeDescriptions =
        XCodeDescriptionsFactory.create(BuckPluginManagerFactory.createPluginManager());
    rootCell = (new TestCellBuilder()).build();
    ProjectFilesystem projectFilesystem = rootCell.getFilesystem();
    BuckConfig fakeBuckConfig = FakeBuckConfig.builder().build();
    halideBuckConfig = HalideLibraryBuilder.createDefaultHalideConfig(projectFilesystem);
    cxxBuckConfig = new CxxBuckConfig(fakeBuckConfig);
    appleConfig = fakeBuckConfig.getView(AppleConfig.class);
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

    BuildTarget bazTestTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//baz", "xctest");
    BuildTarget fooBinTestTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "bin-xctest");
    BuildTarget fooTestTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "lib-xctest");

    BuildTarget barLibTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//bar", "libbar");
    TargetNode<?> barLibNode = AppleLibraryBuilder.createBuilder(barLibTarget).build();

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "lib");
    TargetNode<?> fooLibNode =
        AppleLibraryBuilder.createBuilder(fooLibTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .setTests(ImmutableSortedSet.of(fooTestTarget))
            .build();

    BuildTarget fooBinBinaryTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "binbinary");
    TargetNode<?> fooBinBinaryNode =
        AppleBinaryBuilder.createBuilder(fooBinBinaryTarget)
            .setDeps(ImmutableSortedSet.of(fooLibTarget))
            .build();

    BuildTarget fooBinTarget = BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "bin");
    TargetNode<?> fooBinNode =
        AppleBundleBuilder.createBuilder(fooBinTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .setBinary(fooBinBinaryTarget)
            .setTests(ImmutableSortedSet.of(fooBinTestTarget))
            .build();

    BuildTarget bazLibTarget = BuildTargetFactory.newInstance(rootCell.getRoot(), "//baz", "lib");
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

    BuildTarget quxBinTarget = BuildTargetFactory.newInstance(rootCell.getRoot(), "//qux", "bin");
    TargetNode<?> quxBinNode =
        AppleBinaryBuilder.createBuilder(quxBinTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .build();

    BuildTarget workspaceTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "workspace");
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
      throws IOException, InterruptedException {
    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
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
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(
        projectGenerators, MoreExecutors.newDirectExecutorService());

    ProjectGenerator fooProjectGenerator = projectGenerators.get(Paths.get("foo"));
    ProjectGenerator barProjectGenerator = projectGenerators.get(Paths.get("bar"));
    ProjectGenerator bazProjectGenerator = projectGenerators.get(Paths.get("baz"));
    ProjectGenerator quxProjectGenerator = projectGenerators.get(Paths.get("qux"));

    assertNull("The Qux project should not be generated at all", quxProjectGenerator);

    assertNotNull("The Foo project should have been generated", fooProjectGenerator);

    assertNotNull("The Bar project should have been generated", barProjectGenerator);

    assertNotNull("The Baz project should have been generated", bazProjectGenerator);

    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(), "//foo:bin");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(), "//foo:lib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(), "//foo:bin-xctest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(), "//foo:lib-xctest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        barProjectGenerator.getGeneratedProject(), "//bar:libbar");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        bazProjectGenerator.getGeneratedProject(), "//baz:lib");
  }

  @Test
  public void combinedProjectShouldDiscoverDependenciesAndTests()
      throws IOException, InterruptedException {
    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            true /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
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
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(
        projectGenerators, MoreExecutors.newDirectExecutorService());

    assertTrue(
        "Combined project generation should not populate the project generators map",
        projectGenerators.isEmpty());

    Optional<ProjectGenerator> projectGeneratorOptional = generator.getCombinedProjectGenerator();
    assertTrue(
        "Combined project generator should be present", projectGeneratorOptional.isPresent());
    ProjectGenerator projectGenerator = projectGeneratorOptional.get();

    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(), "//foo:bin");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(), "//foo:lib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(), "//foo:bin-xctest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(), "//foo:lib-xctest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(), "//bar:libbar");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(), "//baz:lib");
  }

  @Test
  public void workspaceAndProjectsWithoutTests() throws IOException, InterruptedException {
    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder().build(),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
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
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(
        projectGenerators, MoreExecutors.newDirectExecutorService());

    ProjectGenerator fooProjectGenerator = projectGenerators.get(Paths.get("foo"));
    ProjectGenerator barProjectGenerator = projectGenerators.get(Paths.get("bar"));
    ProjectGenerator bazProjectGenerator = projectGenerators.get(Paths.get("baz"));
    ProjectGenerator quxProjectGenerator = projectGenerators.get(Paths.get("qux"));

    assertNull("The Qux project should not be generated at all", quxProjectGenerator);

    assertNull("The Baz project should not be generated at all", bazProjectGenerator);

    assertNotNull("The Foo project should have been generated", fooProjectGenerator);

    assertNotNull("The Bar project should have been generated", barProjectGenerator);

    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(), "//foo:bin");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(), "//foo:lib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        barProjectGenerator.getGeneratedProject(), "//bar:libbar");
  }

  @Test
  public void workspaceAndProjectsWithoutDependenciesTests()
      throws IOException, InterruptedException {
    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldGenerateProjectSchemes(true)
                .build(),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
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

    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(
        projectGenerators, MoreExecutors.newDirectExecutorService());

    // one scheme for the workspace and then one for each project
    assertEquals(generator.getSchemeGenerators().size(), 3);

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
        hasItem(
            withNameAndBuildingFor("bin", equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT))));
    assertThat( // foo:bin-xctest
        mainSchemeBuildActionEntries,
        hasItem(withNameAndBuildingFor("bin-xctest", equalTo(BuildFor.TEST_ONLY))));
    assertThat( // foo:lib
        mainSchemeBuildActionEntries,
        hasItem(
            withNameAndBuildingFor("lib", equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT))));
    assertThat( // bar:libbar
        mainSchemeBuildActionEntries,
        hasItem(
            withNameAndBuildingFor("libbar", equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT))));

    XCScheme.TestAction mainSchemeTestAction = mainScheme.get().getTestAction().get();
    assertThat(mainSchemeTestAction.getTestables(), hasSize(1));
    assertThat( // foo:bin-xctest
        mainSchemeTestAction.getTestables(), hasItem(withName("bin-xctest")));

    // validate project specific (foo) scheme values
    Optional<XCScheme> fooScheme = generator.getSchemeGenerators().get("foo").getOutputScheme();

    assertThat(fooScheme.isPresent(), is(true));

    XCScheme.BuildAction fooSchemeBuildAction = fooScheme.get().getBuildAction().get();
    List<BuildActionEntry> fooSchemeBuildActionEntries =
        fooSchemeBuildAction.getBuildActionEntries();
    assertThat(fooSchemeBuildActionEntries, hasSize(3));
    assertThat( // foo:bin
        fooSchemeBuildActionEntries,
        hasItem(
            withNameAndBuildingFor("bin", equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT))));
    assertThat( // foo:bin-xctest
        fooSchemeBuildActionEntries,
        hasItem(withNameAndBuildingFor("bin-xctest", equalTo(BuildFor.TEST_ONLY))));
    assertThat( // foo:lib
        fooSchemeBuildActionEntries,
        hasItem(
            withNameAndBuildingFor("lib", equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT))));

    XCScheme.TestAction fooSchemeTestAction = fooScheme.get().getTestAction().get();
    assertThat(fooSchemeTestAction.getTestables(), hasSize(1));
    assertThat( // foo:bin-xctest
        fooSchemeTestAction.getTestables(), hasItem(withName("bin-xctest")));

    // validate project specific (bar) scheme values
    Optional<XCScheme> barScheme = generator.getSchemeGenerators().get("bar").getOutputScheme();

    assertThat(barScheme.isPresent(), is(true));

    XCScheme.BuildAction barSchemeBuildAction = barScheme.get().getBuildAction().get();
    List<BuildActionEntry> barSchemeBuildActionEntries =
        barSchemeBuildAction.getBuildActionEntries();
    assertThat(barSchemeBuildActionEntries, hasSize(1));
    assertThat( // bar:libbar
        barSchemeBuildActionEntries,
        hasItem(
            withNameAndBuildingFor("libbar", equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT))));

    XCScheme.TestAction barSchemeTestAction = barScheme.get().getTestAction().get();
    assertThat(barSchemeTestAction.getTestables(), hasSize(0));
  }

  @Test
  public void requiredBuildTargets() throws IOException, InterruptedException {
    BuildTarget genruleTarget = BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "gen");
    TargetNode<GenruleDescriptionArg> genrule =
        GenruleBuilder.newGenruleBuilder(genruleTarget).setOut("source.m").build();

    BuildTarget libraryTarget = BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "lib");
    TargetNode<AppleLibraryDescriptionArg> library =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(DefaultBuildTargetSourcePath.of(genruleTarget))))
            .build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "workspace"))
            .setSrcTarget(Optional.of(libraryTarget))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(genrule, library, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder().build(),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
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
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(
        projectGenerators, MoreExecutors.newDirectExecutorService());

    assertEquals(generator.getRequiredBuildTargets(), ImmutableSet.of(genruleTarget));
  }

  @Test
  public void requiredBuildTargetsForCombinedProject() throws IOException, InterruptedException {
    BuildTarget genruleTarget = BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "gen");
    TargetNode<GenruleDescriptionArg> genrule =
        GenruleBuilder.newGenruleBuilder(genruleTarget).setOut("source.m").build();

    BuildTarget libraryTarget = BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "lib");
    TargetNode<AppleLibraryDescriptionArg> library =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(DefaultBuildTargetSourcePath.of(genruleTarget))))
            .build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "workspace"))
            .setSrcTarget(Optional.of(libraryTarget))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(genrule, library, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder().build(),
            true /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
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
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(
        projectGenerators, MoreExecutors.newDirectExecutorService());

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

    BuildTarget bazTestTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//baz", "BazTest");
    BuildTarget fooBinTestTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "FooBinTest");
    BuildTarget fooTestTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "FooLibTest");

    BuildTarget barLibTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//bar", "BarLib");
    TargetNode<?> barLibNode = AppleLibraryBuilder.createBuilder(barLibTarget).build();

    BuildTarget fooLibTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "FooLib");
    TargetNode<?> fooLibNode =
        AppleLibraryBuilder.createBuilder(fooLibTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .setTests(ImmutableSortedSet.of(fooTestTarget))
            .build();

    BuildTarget fooBinBinaryTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "FooBinBinary");
    TargetNode<?> fooBinBinaryNode =
        AppleBinaryBuilder.createBuilder(fooBinBinaryTarget)
            .setDeps(ImmutableSortedSet.of(fooLibTarget))
            .build();

    BuildTarget fooBinTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "FooBin");
    TargetNode<?> fooBinNode =
        AppleBundleBuilder.createBuilder(fooBinTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .setBinary(fooBinBinaryTarget)
            .setTests(ImmutableSortedSet.of(fooBinTestTarget))
            .build();

    BuildTarget bazLibTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//baz", "BazLib");
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

    BuildTarget quxBinTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//qux", "QuxBin");
    TargetNode<?> quxBinNode =
        AppleBinaryBuilder.createBuilder(quxBinTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .build();

    BuildTarget workspaceTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "workspace");
    workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(workspaceTarget)
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooBinTarget))
            .build();

    BuildTarget workspaceWithExtraSchemeTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//qux", "workspace");
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
            rootCell,
            targetGraph,
            workspaceWithExtraSchemeNode.getConstructorArg(),
            workspaceWithExtraSchemeNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
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
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(
        projectGenerators, MoreExecutors.newDirectExecutorService());

    ProjectGenerator fooProjectGenerator = projectGenerators.get(Paths.get("foo"));
    ProjectGenerator barProjectGenerator = projectGenerators.get(Paths.get("bar"));
    ProjectGenerator bazProjectGenerator = projectGenerators.get(Paths.get("baz"));
    ProjectGenerator quxProjectGenerator = projectGenerators.get(Paths.get("qux"));

    assertNotNull("The Qux project should have been generated", quxProjectGenerator);

    assertNotNull("The Foo project should have been generated", fooProjectGenerator);

    assertNotNull("The Bar project should have been generated", barProjectGenerator);

    assertNotNull("The Baz project should have been generated", bazProjectGenerator);

    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(), "//foo:FooBin");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(), "//foo:FooLib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(), "//foo:FooBinTest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(), "//foo:FooLibTest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        barProjectGenerator.getGeneratedProject(), "//bar:BarLib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        bazProjectGenerator.getGeneratedProject(), "//baz:BazLib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        quxProjectGenerator.getGeneratedProject(), "//qux:QuxBin");

    XCScheme mainScheme = generator.getSchemeGenerators().get("workspace").getOutputScheme().get();
    XCScheme.BuildAction mainSchemeBuildAction = mainScheme.getBuildAction().get();
    // I wish we could use Hamcrest contains() here, but we hit
    // https://code.google.com/p/hamcrest/issues/detail?id=190 if we do that.
    assertThat(mainSchemeBuildAction.getBuildActionEntries(), hasSize(2));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(0),
        withNameAndBuildingFor("BarLib", equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(1),
        withNameAndBuildingFor("QuxBin", equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));

    XCScheme fooScheme = generator.getSchemeGenerators().get("FooScheme").getOutputScheme().get();
    XCScheme.BuildAction fooSchemeBuildAction = fooScheme.getBuildAction().get();
    assertThat(fooSchemeBuildAction.getBuildActionEntries(), hasSize(6));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(0),
        withNameAndBuildingFor("BarLib", equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(1),
        withNameAndBuildingFor("FooLib", equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(2),
        withNameAndBuildingFor("FooBin", equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(3),
        withNameAndBuildingFor(
            "FooBinTest", equalTo(XCScheme.BuildActionEntry.BuildFor.TEST_ONLY)));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(4),
        withNameAndBuildingFor("BazLib", equalTo(XCScheme.BuildActionEntry.BuildFor.TEST_ONLY)));

    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(5),
        withNameAndBuildingFor(
            "FooLibTest", equalTo(XCScheme.BuildActionEntry.BuildFor.TEST_ONLY)));
  }

  @Test
  public void targetsForWorkspaceWithExtraTargets() throws IOException, InterruptedException {
    BuildTarget fooLibTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "FooLib");
    TargetNode<AppleLibraryDescriptionArg> fooLib =
        AppleLibraryBuilder.createBuilder(fooLibTarget).build();

    BuildTarget barLibTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//bar", "BarLib");
    TargetNode<AppleLibraryDescriptionArg> barLib =
        AppleLibraryBuilder.createBuilder(barLibTarget).build();

    BuildTarget bazLibTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//baz", "BazLib");
    TargetNode<AppleLibraryDescriptionArg> bazLib =
        AppleLibraryBuilder.createBuilder(bazLibTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "workspace"))
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooLibTarget))
            .setExtraTargets(ImmutableSortedSet.of(bazLibTarget))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(fooLib, barLib, bazLib, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
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
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(
        projectGenerators, MoreExecutors.newDirectExecutorService());

    ProjectGenerator fooProjectGenerator = projectGenerators.get(Paths.get("foo"));
    ProjectGenerator barProjectGenerator = projectGenerators.get(Paths.get("bar"));
    ProjectGenerator bazProjectGenerator = projectGenerators.get(Paths.get("baz"));

    assertNotNull("The Foo project should have been generated", fooProjectGenerator);

    assertNotNull("The Bar project should have been generated", barProjectGenerator);

    assertNotNull("The Baz project should have been generated", bazProjectGenerator);

    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(), "//foo:FooLib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        barProjectGenerator.getGeneratedProject(), "//bar:BarLib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        bazProjectGenerator.getGeneratedProject(), "//baz:BazLib");

    XCScheme mainScheme = generator.getSchemeGenerators().get("workspace").getOutputScheme().get();
    XCScheme.BuildAction mainSchemeBuildAction = mainScheme.getBuildAction().get();
    // I wish we could use Hamcrest contains() here, but we hit
    // https://code.google.com/p/hamcrest/issues/detail?id=190 if we do that.
    assertThat(mainSchemeBuildAction.getBuildActionEntries(), hasSize(3));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(0),
        withNameAndBuildingFor("FooLib", equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(1),
        withNameAndBuildingFor("BarLib", equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(2),
        withNameAndBuildingFor("BazLib", equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
  }

  @Test
  public void enablingParallelizeBuild() throws IOException, InterruptedException {
    BuildTarget fooLibTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "FooLib");
    TargetNode<AppleLibraryDescriptionArg> fooLib =
        AppleLibraryBuilder.createBuilder(fooLibTarget).build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "workspace"))
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooLibTarget))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(fooLib, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
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
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(
        projectGenerators, MoreExecutors.newDirectExecutorService());

    XCScheme mainScheme = generator.getSchemeGenerators().get("workspace").getOutputScheme().get();
    XCScheme.BuildAction mainSchemeBuildAction = mainScheme.getBuildAction().get();
    // I wish we could use Hamcrest contains() here, but we hit
    // https://code.google.com/p/hamcrest/issues/detail?id=190 if we do that.
    assertThat(mainSchemeBuildAction.getBuildActionEntries(), hasSize(1));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(0),
        withNameAndBuildingFor("FooLib", equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
    assertThat(mainSchemeBuildAction.getParallelizeBuild(), is(true));
  }

  @Test
  public void customRunnableSettings() throws IOException, InterruptedException {
    BuildTarget fooLibTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "FooLib");
    TargetNode<AppleLibraryDescriptionArg> fooLib =
        AppleLibraryBuilder.createBuilder(fooLibTarget).build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "workspace"))
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooLibTarget))
            .setExplicitRunnablePath(Optional.of("/some.app"))
            .setLaunchStyle(Optional.of(XCScheme.LaunchAction.LaunchStyle.WAIT))
            .setWatchInterface(Optional.of(WatchInterface.COMPLICATION))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(fooLib, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
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
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(
        projectGenerators, MoreExecutors.newDirectExecutorService());

    XCScheme mainScheme = generator.getSchemeGenerators().get("workspace").getOutputScheme().get();
    XCScheme.LaunchAction launchAction = mainScheme.getLaunchAction().get();
    assertThat(launchAction.getRunnablePath().get(), Matchers.equalTo("/some.app"));
    assertThat(
        launchAction.getLaunchStyle(), Matchers.equalTo(XCScheme.LaunchAction.LaunchStyle.WAIT));
    assertThat(
        launchAction.getWatchInterface().get(), Matchers.equalTo(WatchInterface.COMPLICATION));
  }

  @Test
  public void customPrePostActions() throws IOException, InterruptedException {
    BuildTarget fooLibTarget =
        BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "FooLib");
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
                BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "workspace"))
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooLibTarget))
            .setAdditionalSchemeActions(Optional.of(schemeActions))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(fooLib, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            xcodeDescriptions,
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ProjectGeneratorOptions.builder()
                .setShouldIncludeTests(true)
                .setShouldIncludeDependenciesTests(true)
                .build(),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
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
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(
        projectGenerators, MoreExecutors.newDirectExecutorService());

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
            .getFreshActionGraph(targetGraph.getSubgraph(ImmutableSet.of(input)))
            .getActionGraphBuilder();
  }
}
