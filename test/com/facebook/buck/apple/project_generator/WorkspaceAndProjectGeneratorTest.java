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

package com.facebook.buck.apple.project_generator;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.AppleBinaryBuilder;
import com.facebook.buck.apple.AppleBundleBuilder;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.apple.AppleLibraryDescriptionArg;
import com.facebook.buck.apple.AppleTestBuilder;
import com.facebook.buck.apple.XcodeWorkspaceConfigBuilder;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescriptionArg;
import com.facebook.buck.apple.xcode.XCScheme;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.halide.HalideBuckConfig;
import com.facebook.buck.halide.HalideLibraryBuilder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.shell.GenruleDescriptionArg;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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

  private Cell rootCell;
  private HalideBuckConfig halideBuckConfig;
  private CxxBuckConfig cxxBuckConfig;
  private SwiftBuckConfig swiftBuckConfig;

  private TargetGraph targetGraph;
  private TargetNode<XcodeWorkspaceConfigDescriptionArg, XcodeWorkspaceConfigDescription>
      workspaceNode;
  private TargetNode<XcodeWorkspaceConfigDescriptionArg, XcodeWorkspaceConfigDescription>
      workspaceWithExtraSchemeNode;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws InterruptedException, IOException {
    rootCell = (new TestCellBuilder()).build();
    ProjectFilesystem projectFilesystem = rootCell.getFilesystem();
    halideBuckConfig = HalideLibraryBuilder.createDefaultHalideConfig(projectFilesystem);
    cxxBuckConfig = new CxxBuckConfig(FakeBuckConfig.builder().build());
    BuckConfig fakeBuckConfig = FakeBuckConfig.builder().build();
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

    BuildTarget bazTestTarget = BuildTarget.builder(rootCell.getRoot(), "//baz", "xctest").build();
    BuildTarget fooBinTestTarget =
        BuildTarget.builder(rootCell.getRoot(), "//foo", "bin-xctest").build();
    BuildTarget fooTestTarget =
        BuildTarget.builder(rootCell.getRoot(), "//foo", "lib-xctest").build();

    BuildTarget barLibTarget = BuildTarget.builder(rootCell.getRoot(), "//bar", "lib").build();
    TargetNode<?, ?> barLibNode = AppleLibraryBuilder.createBuilder(barLibTarget).build();

    BuildTarget fooLibTarget = BuildTarget.builder(rootCell.getRoot(), "//foo", "lib").build();
    TargetNode<?, ?> fooLibNode =
        AppleLibraryBuilder.createBuilder(fooLibTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .setTests(ImmutableSortedSet.of(fooTestTarget))
            .build();

    BuildTarget fooBinBinaryTarget =
        BuildTarget.builder(rootCell.getRoot(), "//foo", "binbinary").build();
    TargetNode<?, ?> fooBinBinaryNode =
        AppleBinaryBuilder.createBuilder(fooBinBinaryTarget)
            .setDeps(ImmutableSortedSet.of(fooLibTarget))
            .build();

    BuildTarget fooBinTarget = BuildTarget.builder(rootCell.getRoot(), "//foo", "bin").build();
    TargetNode<?, ?> fooBinNode =
        AppleBundleBuilder.createBuilder(fooBinTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(fooBinBinaryTarget)
            .setTests(ImmutableSortedSet.of(fooBinTestTarget))
            .build();

    BuildTarget bazLibTarget = BuildTarget.builder(rootCell.getRoot(), "//baz", "lib").build();
    TargetNode<?, ?> bazLibNode =
        AppleLibraryBuilder.createBuilder(bazLibTarget)
            .setDeps(ImmutableSortedSet.of(fooLibTarget))
            .setTests(ImmutableSortedSet.of(bazTestTarget))
            .build();

    TargetNode<?, ?> bazTestNode =
        AppleTestBuilder.createBuilder(bazTestTarget)
            .setDeps(ImmutableSortedSet.of(bazLibTarget))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .build();

    TargetNode<?, ?> fooTestNode =
        AppleTestBuilder.createBuilder(fooTestTarget)
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setDeps(ImmutableSortedSet.of(bazLibTarget))
            .build();

    TargetNode<?, ?> fooBinTestNode =
        AppleTestBuilder.createBuilder(fooBinTestTarget)
            .setDeps(ImmutableSortedSet.of(fooBinTarget))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .build();

    BuildTarget quxBinTarget = BuildTarget.builder(rootCell.getRoot(), "//qux", "bin").build();
    TargetNode<?, ?> quxBinNode =
        AppleBinaryBuilder.createBuilder(quxBinTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .build();

    BuildTarget workspaceTarget =
        BuildTarget.builder(rootCell.getRoot(), "//foo", "workspace").build();
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
    return BuckEventBusFactory.newInstance(new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1)));
  }

  @Test
  public void workspaceAndProjectsShouldDiscoverDependenciesAndTests()
      throws IOException, InterruptedException {
    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ImmutableSet.of(
                ProjectGenerator.Option.INCLUDE_TESTS,
                ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            "BUCK",
            getBuildRuleResolverForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            halideBuckConfig,
            cxxBuckConfig,
            swiftBuckConfig);
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
        barProjectGenerator.getGeneratedProject(), "//bar:lib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        bazProjectGenerator.getGeneratedProject(), "//baz:lib");
  }

  @Test
  public void combinedProjectShouldDiscoverDependenciesAndTests()
      throws IOException, InterruptedException {
    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ImmutableSet.of(
                ProjectGenerator.Option.INCLUDE_TESTS,
                ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS),
            true /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            "BUCK",
            getBuildRuleResolverForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            halideBuckConfig,
            cxxBuckConfig,
            swiftBuckConfig);
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
        projectGenerator.getGeneratedProject(), "//bar:lib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(), "//baz:lib");
  }

  @Test
  public void workspaceAndProjectsWithoutTests() throws IOException, InterruptedException {
    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ImmutableSet.of(),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            "BUCK",
            getBuildRuleResolverForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            halideBuckConfig,
            cxxBuckConfig,
            swiftBuckConfig);
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
        barProjectGenerator.getGeneratedProject(), "//bar:lib");
  }

  @Test
  public void workspaceAndProjectsWithoutDependenciesTests()
      throws IOException, InterruptedException {
    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ImmutableSet.of(ProjectGenerator.Option.INCLUDE_TESTS),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            "BUCK",
            getBuildRuleResolverForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            halideBuckConfig,
            cxxBuckConfig,
            swiftBuckConfig);

    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(
        projectGenerators, MoreExecutors.newDirectExecutorService());

    Optional<XCScheme> scheme =
        Iterables.getOnlyElement(generator.getSchemeGenerators().values()).getOutputScheme();

    assertThat(scheme.isPresent(), is(true));

    assertThat(
        "Test for project FooBin should have been generated",
        scheme.get().getBuildAction().get().getBuildActionEntries(),
        hasItem(
            withNameAndBuildingFor(
                "bin-xctest", equalTo(XCScheme.BuildActionEntry.BuildFor.TEST_ONLY))));

    assertThat(
        "Test for project FooLib should not be generated at all",
        scheme.get().getBuildAction().get().getBuildActionEntries(),
        not(
            hasItem(
                withNameAndBuildingFor(
                    "lib-xctest", equalTo(XCScheme.BuildActionEntry.BuildFor.TEST_ONLY)))));
  }

  @Test
  public void requiredBuildTargets() throws IOException, InterruptedException {
    BuildTarget genruleTarget = BuildTarget.builder(rootCell.getRoot(), "//foo", "gen").build();
    TargetNode<GenruleDescriptionArg, GenruleDescription> genrule =
        GenruleBuilder.newGenruleBuilder(genruleTarget).setOut("source.m").build();

    BuildTarget libraryTarget = BuildTarget.builder(rootCell.getRoot(), "//foo", "lib").build();
    TargetNode<AppleLibraryDescriptionArg, ?> library =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new DefaultBuildTargetSourcePath(genruleTarget))))
            .build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg, ?> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTarget.builder(rootCell.getRoot(), "//foo", "workspace").build())
            .setSrcTarget(Optional.of(libraryTarget))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(genrule, library, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ImmutableSet.of(),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            "BUCK",
            getBuildRuleResolverForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            halideBuckConfig,
            cxxBuckConfig,
            swiftBuckConfig);
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(
        projectGenerators, MoreExecutors.newDirectExecutorService());

    assertEquals(generator.getRequiredBuildTargets(), ImmutableSet.of(genruleTarget));
  }

  @Test
  public void requiredBuildTargetsForCombinedProject() throws IOException, InterruptedException {
    BuildTarget genruleTarget = BuildTarget.builder(rootCell.getRoot(), "//foo", "gen").build();
    TargetNode<GenruleDescriptionArg, GenruleDescription> genrule =
        GenruleBuilder.newGenruleBuilder(genruleTarget).setOut("source.m").build();

    BuildTarget libraryTarget = BuildTarget.builder(rootCell.getRoot(), "//foo", "lib").build();
    TargetNode<AppleLibraryDescriptionArg, ?> library =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new DefaultBuildTargetSourcePath(genruleTarget))))
            .build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg, ?> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTarget.builder(rootCell.getRoot(), "//foo", "workspace").build())
            .setSrcTarget(Optional.of(libraryTarget))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(genrule, library, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ImmutableSet.of(),
            true /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            "BUCK",
            getBuildRuleResolverForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            halideBuckConfig,
            cxxBuckConfig,
            swiftBuckConfig);
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

    BuildTarget bazTestTarget = BuildTarget.builder(rootCell.getRoot(), "//baz", "BazTest").build();
    BuildTarget fooBinTestTarget =
        BuildTarget.builder(rootCell.getRoot(), "//foo", "FooBinTest").build();
    BuildTarget fooTestTarget =
        BuildTarget.builder(rootCell.getRoot(), "//foo", "FooLibTest").build();

    BuildTarget barLibTarget = BuildTarget.builder(rootCell.getRoot(), "//bar", "BarLib").build();
    TargetNode<?, ?> barLibNode = AppleLibraryBuilder.createBuilder(barLibTarget).build();

    BuildTarget fooLibTarget = BuildTarget.builder(rootCell.getRoot(), "//foo", "FooLib").build();
    TargetNode<?, ?> fooLibNode =
        AppleLibraryBuilder.createBuilder(fooLibTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .setTests(ImmutableSortedSet.of(fooTestTarget))
            .build();

    BuildTarget fooBinBinaryTarget =
        BuildTarget.builder(rootCell.getRoot(), "//foo", "FooBinBinary").build();
    TargetNode<?, ?> fooBinBinaryNode =
        AppleBinaryBuilder.createBuilder(fooBinBinaryTarget)
            .setDeps(ImmutableSortedSet.of(fooLibTarget))
            .build();

    BuildTarget fooBinTarget = BuildTarget.builder(rootCell.getRoot(), "//foo", "FooBin").build();
    TargetNode<?, ?> fooBinNode =
        AppleBundleBuilder.createBuilder(fooBinTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(fooBinBinaryTarget)
            .setTests(ImmutableSortedSet.of(fooBinTestTarget))
            .build();

    BuildTarget bazLibTarget = BuildTarget.builder(rootCell.getRoot(), "//baz", "BazLib").build();
    TargetNode<?, ?> bazLibNode =
        AppleLibraryBuilder.createBuilder(bazLibTarget)
            .setDeps(ImmutableSortedSet.of(fooLibTarget))
            .setTests(ImmutableSortedSet.of(bazTestTarget))
            .build();

    TargetNode<?, ?> bazTestNode =
        AppleTestBuilder.createBuilder(bazTestTarget)
            .setDeps(ImmutableSortedSet.of(bazLibTarget))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .build();

    TargetNode<?, ?> fooTestNode =
        AppleTestBuilder.createBuilder(fooTestTarget)
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setDeps(ImmutableSortedSet.of(bazLibTarget))
            .build();

    TargetNode<?, ?> fooBinTestNode =
        AppleTestBuilder.createBuilder(fooBinTestTarget)
            .setDeps(ImmutableSortedSet.of(fooBinTarget))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .build();

    BuildTarget quxBinTarget = BuildTarget.builder(rootCell.getRoot(), "//qux", "QuxBin").build();
    TargetNode<?, ?> quxBinNode =
        AppleBinaryBuilder.createBuilder(quxBinTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .build();

    BuildTarget workspaceTarget =
        BuildTarget.builder(rootCell.getRoot(), "//foo", "workspace").build();
    workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(workspaceTarget)
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooBinTarget))
            .build();

    BuildTarget workspaceWithExtraSchemeTarget =
        BuildTarget.builder(rootCell.getRoot(), "//qux", "workspace").build();
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
            rootCell,
            targetGraph,
            workspaceWithExtraSchemeNode.getConstructorArg(),
            workspaceWithExtraSchemeNode.getBuildTarget(),
            ImmutableSet.of(
                ProjectGenerator.Option.INCLUDE_TESTS,
                ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            "BUCK",
            getBuildRuleResolverForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            halideBuckConfig,
            cxxBuckConfig,
            swiftBuckConfig);
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
        withNameAndBuildingFor("BazLib", equalTo(XCScheme.BuildActionEntry.BuildFor.TEST_ONLY)));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(4),
        withNameAndBuildingFor(
            "FooLibTest", equalTo(XCScheme.BuildActionEntry.BuildFor.TEST_ONLY)));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(5),
        withNameAndBuildingFor(
            "FooBinTest", equalTo(XCScheme.BuildActionEntry.BuildFor.TEST_ONLY)));
  }

  @Test
  public void targetsForWorkspaceWithExtraTargets() throws IOException, InterruptedException {
    BuildTarget fooLibTarget = BuildTarget.builder(rootCell.getRoot(), "//foo", "FooLib").build();
    TargetNode<AppleLibraryDescriptionArg, ?> fooLib =
        AppleLibraryBuilder.createBuilder(fooLibTarget).build();

    BuildTarget barLibTarget = BuildTarget.builder(rootCell.getRoot(), "//bar", "BarLib").build();
    TargetNode<AppleLibraryDescriptionArg, ?> barLib =
        AppleLibraryBuilder.createBuilder(barLibTarget).build();

    BuildTarget bazLibTarget = BuildTarget.builder(rootCell.getRoot(), "//baz", "BazLib").build();
    TargetNode<AppleLibraryDescriptionArg, ?> bazLib =
        AppleLibraryBuilder.createBuilder(bazLibTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg, ?> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTarget.builder(rootCell.getRoot(), "//foo", "workspace").build())
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooLibTarget))
            .setExtraTargets(ImmutableSortedSet.of(bazLibTarget))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(fooLib, barLib, bazLib, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ImmutableSet.of(
                ProjectGenerator.Option.INCLUDE_TESTS,
                ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
            false /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            "BUCK",
            getBuildRuleResolverForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            halideBuckConfig,
            cxxBuckConfig,
            swiftBuckConfig);
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
    BuildTarget fooLibTarget = BuildTarget.builder(rootCell.getRoot(), "//foo", "FooLib").build();
    TargetNode<AppleLibraryDescriptionArg, ?> fooLib =
        AppleLibraryBuilder.createBuilder(fooLibTarget).build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg, ?> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTarget.builder(rootCell.getRoot(), "//foo", "workspace").build())
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooLibTarget))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(fooLib, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ImmutableSet.of(
                ProjectGenerator.Option.INCLUDE_TESTS,
                ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
            true /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            "BUCK",
            getBuildRuleResolverForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            halideBuckConfig,
            cxxBuckConfig,
            swiftBuckConfig);
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
    BuildTarget fooLibTarget = BuildTarget.builder(rootCell.getRoot(), "//foo", "FooLib").build();
    TargetNode<AppleLibraryDescriptionArg, ?> fooLib =
        AppleLibraryBuilder.createBuilder(fooLibTarget).build();

    TargetNode<XcodeWorkspaceConfigDescriptionArg, ?> workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(
                BuildTarget.builder(rootCell.getRoot(), "//foo", "workspace").build())
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooLibTarget))
            .setExplicitRunnablePath(Optional.of("/some.app"))
            .setLaunchStyle(Optional.of(XCScheme.LaunchAction.LaunchStyle.WAIT))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(fooLib, workspaceNode);

    WorkspaceAndProjectGenerator generator =
        new WorkspaceAndProjectGenerator(
            rootCell,
            targetGraph,
            workspaceNode.getConstructorArg(),
            workspaceNode.getBuildTarget(),
            ImmutableSet.of(
                ProjectGenerator.Option.INCLUDE_TESTS,
                ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS),
            false /* combinedProject */,
            FocusedModuleTargetMatcher.noFocus(),
            true /* parallelizeBuild */,
            DEFAULT_PLATFORM,
            "BUCK",
            getBuildRuleResolverForNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            halideBuckConfig,
            cxxBuckConfig,
            swiftBuckConfig);
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(
        projectGenerators, MoreExecutors.newDirectExecutorService());

    XCScheme mainScheme = generator.getSchemeGenerators().get("workspace").getOutputScheme().get();
    XCScheme.LaunchAction launchAction = mainScheme.getLaunchAction().get();
    assertThat(launchAction.getRunnablePath().get(), Matchers.equalTo("/some.app"));
    assertThat(
        launchAction.getLaunchStyle(), Matchers.equalTo(XCScheme.LaunchAction.LaunchStyle.WAIT));
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

  private Function<TargetNode<?, ?>, BuildRuleResolver> getBuildRuleResolverForNodeFunction(
      final TargetGraph targetGraph) {
    return input ->
        ActionGraphCache.getFreshActionGraph(
                BuckEventBusFactory.newInstance(), targetGraph.getSubgraph(ImmutableSet.of(input)))
            .getResolver();
  }
}
