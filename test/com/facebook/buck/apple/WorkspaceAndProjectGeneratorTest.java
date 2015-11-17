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

package com.facebook.buck.apple;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.apple.xcode.XCScheme;
import com.facebook.buck.apple.xcode.xcodeproj.PBXAggregateTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.facebook.buck.timing.SettableFakeClock;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.core.AllOf;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

public class WorkspaceAndProjectGeneratorTest {

  private static final FlavorDomain<CxxPlatform> PLATFORMS =
      new FlavorDomain<>("C/C++ platform", ImmutableMap.<Flavor, CxxPlatform>of());
  private static final CxxPlatform DEFAULT_PLATFORM = CxxPlatformUtils.DEFAULT_PLATFORM;
  private static final Path ROOT = Paths.get("/opt/src/buck");

  private ProjectFilesystem projectFilesystem;

  private TargetGraph targetGraph;
  private TargetNode<XcodeWorkspaceConfigDescription.Arg> workspaceNode;
  private TargetNode<XcodeWorkspaceConfigDescription.Arg> workspaceWithExtraSchemeNode;

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws IOException {
    projectFilesystem = new FakeProjectFilesystem(new SettableFakeClock(0, 0));

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
    // FooBin and BazLib use "tests" to specify their tests while FooLibTest uses source_under_test
    // to specify that it is a test of FooLib.
    //
    // Calling generate on FooBin should pull in everything except BazLibTest and QuxBin

    BuildTarget bazTestTarget = BuildTarget.builder(ROOT, "//baz", "xctest").build();
    BuildTarget fooBinTestTarget = BuildTarget.builder(ROOT, "//foo", "bin-xctest").build();
    BuildTarget fooTestTarget = BuildTarget.builder(ROOT, "//foo", "lib-xctest").build();

    BuildTarget barLibTarget = BuildTarget.builder(ROOT, "//bar", "lib").build();
    TargetNode<?> barLibNode = AppleLibraryBuilder.createBuilder(barLibTarget).build();

    BuildTarget fooLibTarget = BuildTarget.builder(ROOT, "//foo", "lib").build();
    TargetNode<?> fooLibNode = AppleLibraryBuilder
        .createBuilder(fooLibTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(barLibTarget)))
        .setTests(Optional.of(ImmutableSortedSet.of(fooTestTarget)))
        .build();

    BuildTarget fooBinBinaryTarget = BuildTarget.builder(ROOT, "//foo", "binbinary").build();
    TargetNode<?> fooBinBinaryNode = AppleBinaryBuilder
        .createBuilder(fooBinBinaryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooLibTarget)))
        .build();

    BuildTarget fooBinTarget = BuildTarget.builder(ROOT, "//foo", "bin").build();
    TargetNode<?> fooBinNode = AppleBundleBuilder
        .createBuilder(fooBinTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.APP))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setBinary(fooBinBinaryTarget)
        .setTests(Optional.of(ImmutableSortedSet.of(fooBinTestTarget)))
        .build();

    BuildTarget bazLibTarget = BuildTarget.builder(ROOT, "//baz", "lib").build();
    TargetNode<?> bazLibNode = AppleLibraryBuilder
        .createBuilder(bazLibTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooLibTarget)))
        .setTests(Optional.of(ImmutableSortedSet.of(bazTestTarget)))
        .build();

    TargetNode<?> bazTestNode = AppleTestBuilder
        .createBuilder(bazTestTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(bazLibTarget)))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .build();

    TargetNode<?> fooTestNode = AppleTestBuilder
        .createBuilder(fooTestTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setDeps(Optional.of(ImmutableSortedSet.of(bazLibTarget)))
        .build();

    TargetNode<?> fooBinTestNode = AppleTestBuilder
        .createBuilder(fooBinTestTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooBinTarget)))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .build();

    BuildTarget quxBinTarget = BuildTarget.builder(ROOT, "//qux", "bin").build();
    TargetNode<?> quxBinNode = AppleBinaryBuilder
        .createBuilder(quxBinTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(barLibTarget)))
        .build();

    BuildTarget workspaceTarget = BuildTarget.builder(ROOT, "//foo", "workspace").build();
    workspaceNode = XcodeWorkspaceConfigBuilder
        .createBuilder(workspaceTarget)
        .setWorkspaceName(Optional.of("workspace"))
        .setSrcTarget(Optional.of(fooBinTarget))
        .build();

    targetGraph = TargetGraphFactory.newInstance(
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
  public void workspaceAndProjectsShouldDiscoverDependenciesAndTests() throws IOException {
    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspaceNode.getConstructorArg(),
        workspaceNode.getBuildTarget(),
        ImmutableSet.of(ProjectGenerator.Option.INCLUDE_TESTS,
            ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS),
        false /* combinedProject */,
        false /* buildWithBuck */,
        ImmutableList.<String>of(),
        false /* parallelizeBuild */,
        false /* attemptToDetermineBestCxxPlatform */,
        new AlwaysFoundExecutableFinder(),
        ImmutableMap.<String, String>of(),
        PLATFORMS,
        DEFAULT_PLATFORM,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph),
        getFakeBuckEventBus());
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    ProjectGenerator fooProjectGenerator =
        projectGenerators.get(Paths.get("foo"));
    ProjectGenerator barProjectGenerator =
        projectGenerators.get(Paths.get("bar"));
    ProjectGenerator bazProjectGenerator =
        projectGenerators.get(Paths.get("baz"));
    ProjectGenerator quxProjectGenerator =
        projectGenerators.get(Paths.get("qux"));

    assertNull(
        "The Qux project should not be generated at all",
        quxProjectGenerator);

    assertNotNull(
        "The Foo project should have been generated",
        fooProjectGenerator);

    assertNotNull(
        "The Bar project should have been generated",
        barProjectGenerator);

    assertNotNull(
        "The Baz project should have been generated",
        bazProjectGenerator);

    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(),
        "//foo:bin");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(),
        "//foo:lib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(),
        "//foo:bin-xctest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(),
        "//foo:lib-xctest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        barProjectGenerator.getGeneratedProject(),
        "//bar:lib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        bazProjectGenerator.getGeneratedProject(),
        "//baz:lib");
  }

  @Test
  public void combinedProjectShouldDiscoverDependenciesAndTests() throws IOException {
    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspaceNode.getConstructorArg(),
        workspaceNode.getBuildTarget(),
        ImmutableSet.of(ProjectGenerator.Option.INCLUDE_TESTS,
            ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS),
        true /* combinedProject */,
        false /* buildWithBuck */,
        ImmutableList.<String>of(),
        false /* parallelizeBuild */,
        false /* attemptToDetermineBestCxxPlatform */,
        new AlwaysFoundExecutableFinder(),
        ImmutableMap.<String, String>of(),
        PLATFORMS,
        DEFAULT_PLATFORM,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph),
        getFakeBuckEventBus());
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    assertTrue(
        "Combined project generation should not populate the project generators map",
        projectGenerators.isEmpty());

    Optional<ProjectGenerator> projectGeneratorOptional = generator.getCombinedProjectGenerator();
    assertTrue(
        "Combined project generator should be present",
        projectGeneratorOptional.isPresent());
    ProjectGenerator projectGenerator = projectGeneratorOptional.get();

    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:bin");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:bin-xctest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib-xctest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//bar:lib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//baz:lib");
  }

  @Test
  public void workspaceAndProjectsWithoutTests() throws IOException {
    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspaceNode.getConstructorArg(),
        workspaceNode.getBuildTarget(),
        ImmutableSet.<ProjectGenerator.Option>of(),
        false /* combinedProject */,
        false /* buildWithBuck */,
        ImmutableList.<String>of(),
        false /* parallelizeBuild */,
        false /* attemptToDetermineBestCxxPlatform */,
        new AlwaysFoundExecutableFinder(),
        ImmutableMap.<String, String>of(),
        PLATFORMS,
        DEFAULT_PLATFORM,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph),
        getFakeBuckEventBus());
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    ProjectGenerator fooProjectGenerator =
        projectGenerators.get(Paths.get("foo"));
    ProjectGenerator barProjectGenerator =
        projectGenerators.get(Paths.get("bar"));
    ProjectGenerator bazProjectGenerator =
        projectGenerators.get(Paths.get("baz"));
    ProjectGenerator quxProjectGenerator =
        projectGenerators.get(Paths.get("qux"));

    assertNull(
        "The Qux project should not be generated at all",
        quxProjectGenerator);

    assertNull(
        "The Baz project should not be generated at all",
        bazProjectGenerator);

    assertNotNull(
        "The Foo project should have been generated",
        fooProjectGenerator);

    assertNotNull(
        "The Bar project should have been generated",
        barProjectGenerator);

    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(),
        "//foo:bin");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(),
        "//foo:lib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        barProjectGenerator.getGeneratedProject(),
        "//bar:lib");
  }

  @Test
  public void requiredBuildTargets() throws IOException {
    BuildTarget genruleTarget = BuildTarget.builder(ROOT, "//foo", "gen").build();
    TargetNode<GenruleDescription.Arg> genrule  = GenruleBuilder
        .newGenruleBuilder(genruleTarget)
        .setOut("source.m")
        .build();

    BuildTarget libraryTarget = BuildTarget.builder(ROOT, "//foo", "lib").build();
    TargetNode<AppleNativeTargetDescriptionArg> library = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setSrcs(
            Optional.of(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(
                        new BuildTargetSourcePath(genruleTarget)))))
        .build();

    TargetNode<XcodeWorkspaceConfigDescription.Arg> workspaceNode = XcodeWorkspaceConfigBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//foo", "workspace").build())
        .setSrcTarget(Optional.of(libraryTarget))
        .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(genrule, library, workspaceNode);

    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspaceNode.getConstructorArg(),
        workspaceNode.getBuildTarget(),
        ImmutableSet.<ProjectGenerator.Option>of(),
        false /* combinedProject */,
        false /* buildWithBuck */,
        ImmutableList.<String>of(),
        false /* parallelizeBuild */,
        false /* attemptToDetermineBestCxxPlatform */,
        new AlwaysFoundExecutableFinder(),
        ImmutableMap.<String, String>of(),
        PLATFORMS,
        DEFAULT_PLATFORM,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph),
        getFakeBuckEventBus());
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    assertEquals(
        generator.getRequiredBuildTargets(),
        ImmutableSet.of(genruleTarget));
  }

  @Test
  public void requiredBuildTargetsForCombinedProject() throws IOException {
    BuildTarget genruleTarget = BuildTarget.builder(ROOT, "//foo", "gen").build();
    TargetNode<GenruleDescription.Arg> genrule  = GenruleBuilder
        .newGenruleBuilder(genruleTarget)
        .setOut("source.m")
        .build();

    BuildTarget libraryTarget = BuildTarget.builder(ROOT, "//foo", "lib").build();
    TargetNode<AppleNativeTargetDescriptionArg> library = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setSrcs(
            Optional.of(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(
                        new BuildTargetSourcePath(genruleTarget)))))
        .build();

    TargetNode<XcodeWorkspaceConfigDescription.Arg> workspaceNode = XcodeWorkspaceConfigBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//foo", "workspace").build())
        .setSrcTarget(Optional.of(libraryTarget))
        .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(genrule, library, workspaceNode);

    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspaceNode.getConstructorArg(),
        workspaceNode.getBuildTarget(),
        ImmutableSet.<ProjectGenerator.Option>of(),
        true /* combinedProject */,
        false /* buildWithBuck */,
        ImmutableList.<String>of(),
        false /* parallelizeBuild */,
        false /* attemptToDetermineBestCxxPlatform */,
        new AlwaysFoundExecutableFinder(),
        ImmutableMap.<String, String>of(),
        PLATFORMS,
        DEFAULT_PLATFORM,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph),
        getFakeBuckEventBus());
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    assertEquals(
        generator.getRequiredBuildTargets(),
        ImmutableSet.of(genruleTarget));
  }

  @Test
  public void buildWithBuck() throws IOException {
    Optional<Path> buck = new ExecutableFinder().getOptionalExecutable(
        Paths.get("buck"),
        ImmutableMap.<String, String>of());
    assumeThat(buck.isPresent(), is(true));
    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspaceNode.getConstructorArg(),
        workspaceNode.getBuildTarget(),
        ImmutableSet.of(ProjectGenerator.Option.INCLUDE_TESTS,
            ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS),
        false /* combinedProject */,
        true /* buildWithBuck */,
        ImmutableList.<String>of(),
        false /* parallelizeBuild */,
        false /* attemptToDetermineBestCxxPlatform */,
        new AlwaysFoundExecutableFinder(),
        ImmutableMap.<String, String>of(),
        PLATFORMS,
        DEFAULT_PLATFORM,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph),
        getFakeBuckEventBus());
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    ProjectGenerator fooProjectGenerator = projectGenerators.get(Paths.get("foo"));
    assertThat(fooProjectGenerator, is(notNullValue()));

    PBXTarget buildWithBuckTarget = null;
    for (PBXTarget target : fooProjectGenerator.getGeneratedProject().getTargets()) {
      if (target.getProductName() != null && target.getProductName().endsWith("-Buck")) {
        buildWithBuckTarget = target;
        break;
      }
    }
    assertThat(buildWithBuckTarget, is(notNullValue()));
    assertThat(buildWithBuckTarget, is(instanceOf(PBXAggregateTarget.class)));

    String gid = buildWithBuckTarget.getGlobalID();

    Optional<XCScheme> scheme = Iterables
        .getOnlyElement(generator.getSchemeGenerators().values())
        .getOutputScheme();

    assertThat(scheme.isPresent(), is(true));

    XCScheme.BuildableReference buildWithBuckBuildableReference = null;
    for (XCScheme.BuildActionEntry buildActionEntry :
        scheme.get().getBuildAction().get().getBuildActionEntries()) {
      XCScheme.BuildableReference buildableReference = buildActionEntry.getBuildableReference();
      if (buildableReference.getBlueprintIdentifier().equals(gid)) {
        buildWithBuckBuildableReference = buildableReference;
      }
    }
    assertThat(buildWithBuckBuildableReference, is(notNullValue()));

    assertThat(buildWithBuckBuildableReference.getBuildableName(), equalTo("//foo:bin-Buck"));
  }

  @Test
  public void buildWithBuckWithCxxPlatformDetection() throws IOException {
    Optional<Path> buck = new ExecutableFinder().getOptionalExecutable(
        Paths.get("buck"),
        ImmutableMap.<String, String>of());
    assumeThat(buck.isPresent(), is(true));
    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspaceNode.getConstructorArg(),
        workspaceNode.getBuildTarget(),
        ImmutableSet.of(ProjectGenerator.Option.INCLUDE_TESTS,
            ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS),
        false /* combinedProject */,
        true /* buildWithBuck */,
        ImmutableList.<String>of(),
        false /* parallelizeBuild */,
        true /* attemptToDetermineBestCxxPlatform */,
        new AlwaysFoundExecutableFinder(),
        ImmutableMap.<String, String>of(),
        PLATFORMS,
        DEFAULT_PLATFORM,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph),
        getFakeBuckEventBus());
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    ProjectGenerator fooProjectGenerator = projectGenerators.get(Paths.get("foo"));
    assertThat(fooProjectGenerator, is(notNullValue()));

    PBXTarget buildWithBuckTarget = null;
    for (PBXTarget target : fooProjectGenerator.getGeneratedProject().getTargets()) {
      if (target.getProductName() != null && target.getProductName().endsWith("-Buck")) {
        buildWithBuckTarget = target;
        break;
      }
    }
    assertThat(buildWithBuckTarget, is(notNullValue()));
    assertThat(buildWithBuckTarget, is(instanceOf(PBXAggregateTarget.class)));

    // build with buck should contain script that would set config based on evironment variables
    PBXShellScriptBuildPhase phase = (PBXShellScriptBuildPhase) buildWithBuckTarget
        .getBuildPhases().get(0);
    String script = phase.getShellScript();
    assertThat(script.contains("--config cxx.default_platform=$PLATFORM_NAME-$arch"),
        Matchers.equalTo(true));
  }

  @Test
  public void combinedTestBundle() throws IOException {
    TargetNode<AppleTestDescription.Arg> combinableTest1 = AppleTestBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//foo", "combinableTest1").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setCanGroup(Optional.of(true))
        .build();
    TargetNode<AppleTestDescription.Arg> combinableTest2 = AppleTestBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//bar", "combinableTest2").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setCanGroup(Optional.of(true))
        .build();
    TargetNode<AppleTestDescription.Arg> testMarkedUncombinable = AppleTestBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//foo", "testMarkedUncombinable").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setCanGroup(Optional.of(false))
        .build();
    TargetNode<AppleTestDescription.Arg> anotherTest = AppleTestBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//foo", "anotherTest").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.OCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setCanGroup(Optional.of(true))
        .build();
    TargetNode<AppleNativeTargetDescriptionArg> library = AppleLibraryBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//foo", "lib").build())
        .setTests(
            Optional.of(
                ImmutableSortedSet.of(
                    combinableTest1.getBuildTarget(),
                    combinableTest2.getBuildTarget(),
                    testMarkedUncombinable.getBuildTarget(),
                    anotherTest.getBuildTarget())))
        .build();
    TargetNode<XcodeWorkspaceConfigDescription.Arg> workspace = XcodeWorkspaceConfigBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//foo", "workspace").build())
        .setSrcTarget(Optional.of(library.getBuildTarget()))
        .setWorkspaceName(Optional.of("workspace"))
        .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            library,
            combinableTest1,
            combinableTest2,
            testMarkedUncombinable,
            anotherTest,
            workspace);

    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspace.getConstructorArg(),
        workspaceNode.getBuildTarget(),
        ImmutableSet.of(ProjectGenerator.Option.INCLUDE_TESTS,
            ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS),
        false /* combinedProject */,
        false /* buildWithBuck */,
        ImmutableList.<String>of(),
        false /* parallelizeBuild */,
        false /* attemptToDetermineBestCxxPlatform */,
        new AlwaysFoundExecutableFinder(),
        ImmutableMap.<String, String>of(),
        PLATFORMS,
        DEFAULT_PLATFORM,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph),
        getFakeBuckEventBus());
    generator.setGroupableTests(AppleBuildRules.filterGroupableTests(targetGraph.getNodes()));
    Map<Path, ProjectGenerator> projectGenerators = Maps.newHashMap();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    // Tests should become libraries
    PBXTarget combinableTestTarget1 = ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerators.get(Paths.get("foo")).getGeneratedProject(),
        "//foo:combinableTest1");
    assertEquals(
        "Test in the bundle should be built as a static library.",
        ProductType.STATIC_LIBRARY,
        combinableTestTarget1.getProductType());

    PBXTarget combinableTestTarget2 = ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerators.get(Paths.get("bar")).getGeneratedProject(),
        "//bar:combinableTest2");
    assertEquals(
        "Other test in the bundle should be built as a static library.",
        ProductType.STATIC_LIBRARY,
        combinableTestTarget2.getProductType());

    // Test not bundled with any others should retain behavior.
    PBXTarget notCombinedTest = ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerators.get(Paths.get("foo")).getGeneratedProject(),
        "//foo:anotherTest");
    assertEquals(
        "Test that is not combined with other tests should also generate a test bundle.",
        ProductType.STATIC_LIBRARY,
        notCombinedTest.getProductType());

    // Test not bundled with any others should retain behavior.
    PBXTarget uncombinableTest = ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerators.get(Paths.get("foo")).getGeneratedProject(),
        "//foo:testMarkedUncombinable");
    assertEquals(
        "Test marked uncombinable should not be combined",
        ProductType.UNIT_TEST,
        uncombinableTest.getProductType());

    // Combined test project should be generated with a combined test bundle.
    PBXTarget combinedTestBundle = ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        generator.getCombinedTestsProjectGenerator().get().getGeneratedProject(),
        "_BuckCombinedTest-xctest-0");
    assertEquals(
        "Combined test project target should be test bundle.",
        ProductType.UNIT_TEST,
        combinedTestBundle.getProductType());

    // Main scheme should contain generated test targets.
    XCScheme scheme = generator.getSchemeGenerators().get("workspace").getOutputScheme().get();
    XCScheme.TestAction testAction = scheme.getTestAction().get();
    assertThat(
        "Combined test target should be a testable",
        testAction.getTestables(),
        hasItem(testableWithName("_BuckCombinedTest-xctest-0")));
    assertThat(
        "Uncombined but groupable test should be a testable",
        testAction.getTestables(),
        hasItem(testableWithName("_BuckCombinedTest-octest-1")));
    assertThat(
        "Bundled test library is not a testable",
        testAction.getTestables(),
        not(hasItem(testableWithName("combinableTest1"))));

    XCScheme.BuildAction buildAction = scheme.getBuildAction().get();
    assertThat(
        "Bundled test library should be built for tests",
        buildAction.getBuildActionEntries(),
        hasItem(
            withNameAndBuildingFor(
                "combinableTest1",
                equalTo(XCScheme.BuildActionEntry.BuildFor.TEST_ONLY))));
    assertThat(
        "Combined test library should be built for tests",
        buildAction.getBuildActionEntries(),
        hasItem(
            withNameAndBuildingFor(
                "_BuckCombinedTest-xctest-0",
                equalTo(XCScheme.BuildActionEntry.BuildFor.TEST_ONLY))));
  }

  @Test
  public void groupTests() {
    TargetNode<AppleTestDescription.Arg> combinableTest1 = AppleTestBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//foo", "test1").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setCanGroup(Optional.of(true))
        .build();
    TargetNode<AppleTestDescription.Arg> combinableTest2 = AppleTestBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//bar", "test2").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setCanGroup(Optional.of(true))
        .build();

    ImmutableMultimap.Builder<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>
        groupedTestsMapBuilder = ImmutableMultimap.builder();
    ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescription.Arg>>
        ungroupedTestsMapBuilder = ImmutableSetMultimap.builder();

    WorkspaceAndProjectGenerator.groupSchemeTests(
        ImmutableSet.of(
            combinableTest1,
            combinableTest2),
        ImmutableSetMultimap.of(
            "workspace", combinableTest1,
            "workspace", combinableTest2),
        groupedTestsMapBuilder,
        ungroupedTestsMapBuilder);

    ImmutableMultimap<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>
        groupedTestsMap = groupedTestsMapBuilder.build();

    assertThat(
        ungroupedTestsMapBuilder.build().entries(),
        empty());
    assertEquals(
        ImmutableSortedSet.of(
            combinableTest1,
            combinableTest2),
        ImmutableSortedSet.copyOf(
            groupedTestsMap.values()));
    ImmutableList<Map.Entry<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>>
        groupedTests = ImmutableList.copyOf(groupedTestsMap.entries());
    assertEquals(2, groupedTests.size());
    assertEquals(groupedTests.get(0).getKey(), groupedTests.get(1).getKey());
  }

  @Test
  public void doNotGroupTestsWithDifferentExtensions() {
    TargetNode<AppleTestDescription.Arg> combinableTest1 = AppleTestBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//foo", "test1").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setCanGroup(Optional.of(true))
        .build();
    TargetNode<AppleTestDescription.Arg> combinableTest2 = AppleTestBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//bar", "test2").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.OCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setCanGroup(Optional.of(true))
        .build();

    ImmutableMultimap.Builder<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>
        groupedTestsMapBuilder = ImmutableMultimap.builder();
    ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescription.Arg>>
        ungroupedTestsMapBuilder = ImmutableSetMultimap.builder();

    WorkspaceAndProjectGenerator.groupSchemeTests(
        ImmutableSet.of(
            combinableTest1,
            combinableTest2),
        ImmutableSetMultimap.of(
            "workspace", combinableTest1,
            "workspace", combinableTest2),
        groupedTestsMapBuilder,
        ungroupedTestsMapBuilder);

    ImmutableMultimap<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>
        groupedTestsMap = groupedTestsMapBuilder.build();

    assertThat(
        ungroupedTestsMapBuilder.build().entries(),
        empty());
    assertEquals(
        ImmutableSortedSet.of(
            combinableTest1,
            combinableTest2),
        ImmutableSortedSet.copyOf(
            groupedTestsMap.values()));
    ImmutableList<Map.Entry<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>>
        groupedTests = ImmutableList.copyOf(groupedTestsMap.entries());
    assertEquals(2, groupedTests.size());
    assertNotEquals(groupedTests.get(0).getKey(), groupedTests.get(1).getKey());
  }

  @Test
  public void doNotGroupTestsWithDifferentConfigs() {
    ImmutableSortedMap<String, ImmutableMap<String, String>> configs = ImmutableSortedMap.of(
        "Debug",
        ImmutableMap.of("KEY", "VALUE"));

    TargetNode<AppleTestDescription.Arg> combinableTest1 = AppleTestBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//foo", "test1").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setCanGroup(Optional.of(true))
        .build();
    TargetNode<AppleTestDescription.Arg> combinableTest2 = AppleTestBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//bar", "test2").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setConfigs(Optional.of(configs))
        .setCanGroup(Optional.of(true))
        .build();

    ImmutableMultimap.Builder<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>
        groupedTestsMapBuilder = ImmutableMultimap.builder();
    ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescription.Arg>>
        ungroupedTestsMapBuilder = ImmutableSetMultimap.builder();

    WorkspaceAndProjectGenerator.groupSchemeTests(
        ImmutableSet.of(
            combinableTest1,
            combinableTest2),
        ImmutableSetMultimap.of(
            "workspace", combinableTest1,
            "workspace", combinableTest2),
        groupedTestsMapBuilder,
        ungroupedTestsMapBuilder);

    ImmutableMultimap<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>
        groupedTestsMap = groupedTestsMapBuilder.build();

    assertThat(
        ungroupedTestsMapBuilder.build().entries(),
        empty());
    assertEquals(
        ImmutableSortedSet.of(
            combinableTest1,
            combinableTest2),
        ImmutableSortedSet.copyOf(
            groupedTestsMap.values()));
    ImmutableList<Map.Entry<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>>
        groupedTests = ImmutableList.copyOf(groupedTestsMap.entries());
    assertEquals(2, groupedTests.size());
    assertNotEquals(groupedTests.get(0).getKey(), groupedTests.get(1).getKey());
  }

  @Test
  public void doNotGroupTestsWithDifferentLinkerFlags() {
    TargetNode<AppleTestDescription.Arg> combinableTest1 = AppleTestBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//foo", "test1").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setCanGroup(Optional.of(true))
        .build();
    TargetNode<AppleTestDescription.Arg> combinableTest2 = AppleTestBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//bar", "test2").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setLinkerFlags(Optional.of(ImmutableList.of("-flag")))
        .setExportedLinkerFlags(Optional.of(ImmutableList.of("-exported-flag")))
        .setCanGroup(Optional.of(true))
        .build();


    ImmutableMultimap.Builder<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>
        groupedTestsMapBuilder = ImmutableMultimap.builder();
    ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescription.Arg>>
        ungroupedTestsMapBuilder = ImmutableSetMultimap.builder();

    WorkspaceAndProjectGenerator.groupSchemeTests(
        ImmutableSet.of(
            combinableTest1,
            combinableTest2),
        ImmutableSetMultimap.of(
            "workspace", combinableTest1,
            "workspace", combinableTest2),
        groupedTestsMapBuilder,
        ungroupedTestsMapBuilder);

    ImmutableMultimap<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>
        groupedTestsMap = groupedTestsMapBuilder.build();

    assertThat(
        ungroupedTestsMapBuilder.build().entries(),
        empty());
    assertEquals(
        ImmutableSortedSet.of(
            combinableTest1,
            combinableTest2),
        ImmutableSortedSet.copyOf(
            groupedTestsMap.values()));
    ImmutableList<Map.Entry<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>>
        groupedTests = ImmutableList.copyOf(groupedTestsMap.entries());
    assertEquals(2, groupedTests.size());
    assertNotEquals(groupedTests.get(0).getKey(), groupedTests.get(1).getKey());
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
    // FooBin and BazLib use "tests" to specify their tests while FooLibTest uses source_under_test
    // to specify that it is a test of FooLib.
    //
    // Calling generate on FooBin should pull in everything except BazLibTest and QuxBin

    BuildTarget bazTestTarget = BuildTarget.builder(ROOT, "//baz", "BazTest").build();
    BuildTarget fooBinTestTarget = BuildTarget.builder(ROOT, "//foo", "FooBinTest").build();
    BuildTarget fooTestTarget = BuildTarget.builder(ROOT, "//foo", "FooLibTest").build();

    BuildTarget barLibTarget = BuildTarget.builder(ROOT, "//bar", "BarLib").build();
    TargetNode<?> barLibNode = AppleLibraryBuilder.createBuilder(barLibTarget).build();

    BuildTarget fooLibTarget = BuildTarget.builder(ROOT, "//foo", "FooLib").build();
    TargetNode<?> fooLibNode = AppleLibraryBuilder
        .createBuilder(fooLibTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(barLibTarget)))
        .setTests(Optional.of(ImmutableSortedSet.of(fooTestTarget)))
        .build();

    BuildTarget fooBinBinaryTarget = BuildTarget.builder(ROOT, "//foo", "FooBinBinary").build();
    TargetNode<?> fooBinBinaryNode = AppleBinaryBuilder
        .createBuilder(fooBinBinaryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooLibTarget)))
        .build();

    BuildTarget fooBinTarget = BuildTarget.builder(ROOT, "//foo", "FooBin").build();
    TargetNode<?> fooBinNode = AppleBundleBuilder
        .createBuilder(fooBinTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.APP))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setBinary(fooBinBinaryTarget)
        .setTests(Optional.of(ImmutableSortedSet.of(fooBinTestTarget)))
        .build();

    BuildTarget bazLibTarget = BuildTarget.builder(ROOT, "//baz", "BazLib").build();
    TargetNode<?> bazLibNode = AppleLibraryBuilder
        .createBuilder(bazLibTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooLibTarget)))
        .setTests(Optional.of(ImmutableSortedSet.of(bazTestTarget)))
        .build();

    TargetNode<?> bazTestNode = AppleTestBuilder
        .createBuilder(bazTestTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(bazLibTarget)))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .build();

    TargetNode<?> fooTestNode = AppleTestBuilder
        .createBuilder(fooTestTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .setDeps(Optional.of(ImmutableSortedSet.of(bazLibTarget)))
        .build();

    TargetNode<?> fooBinTestNode = AppleTestBuilder
        .createBuilder(fooBinTestTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooBinTarget)))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .build();

    BuildTarget quxBinTarget = BuildTarget.builder(ROOT, "//qux", "QuxBin").build();
    TargetNode<?> quxBinNode = AppleBinaryBuilder
        .createBuilder(quxBinTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(barLibTarget)))
        .build();

    BuildTarget workspaceTarget = BuildTarget.builder(ROOT, "//foo", "workspace").build();
    workspaceNode = XcodeWorkspaceConfigBuilder
        .createBuilder(workspaceTarget)
        .setWorkspaceName(Optional.of("workspace"))
        .setSrcTarget(Optional.of(fooBinTarget))
        .build();

    BuildTarget workspaceWithExtraSchemeTarget =
        BuildTarget.builder(ROOT, "//qux", "workspace").build();
    workspaceWithExtraSchemeNode = XcodeWorkspaceConfigBuilder
        .createBuilder(workspaceWithExtraSchemeTarget)
        .setWorkspaceName(Optional.of("workspace"))
        .setSrcTarget(Optional.of(quxBinTarget))
        .setExtraSchemes(Optional.of(ImmutableSortedMap.of("FooScheme", workspaceTarget)))
        .build();

    targetGraph = TargetGraphFactory.newInstance(
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
  public void targetsForWorkspaceWithExtraSchemes() throws IOException {
    setUpWorkspaceWithSchemeAndProjects();

    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspaceWithExtraSchemeNode.getConstructorArg(),
        workspaceWithExtraSchemeNode.getBuildTarget(),
        ImmutableSet.of(ProjectGenerator.Option.INCLUDE_TESTS,
            ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS),
        false /* combinedProject */,
        false /* buildWithBuck */,
        ImmutableList.<String>of(),
        false /* parallelizeBuild */,
        false /* attemptToDetermineBestCxxPlatform */,
        new AlwaysFoundExecutableFinder(),
        ImmutableMap.<String, String>of(),
        PLATFORMS,
        DEFAULT_PLATFORM,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph),
        getFakeBuckEventBus());
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    ProjectGenerator fooProjectGenerator =
        projectGenerators.get(Paths.get("foo"));
    ProjectGenerator barProjectGenerator =
        projectGenerators.get(Paths.get("bar"));
    ProjectGenerator bazProjectGenerator =
        projectGenerators.get(Paths.get("baz"));
    ProjectGenerator quxProjectGenerator =
        projectGenerators.get(Paths.get("qux"));

    assertNotNull(
        "The Qux project should have been generated",
        quxProjectGenerator);

    assertNotNull(
        "The Foo project should have been generated",
        fooProjectGenerator);

    assertNotNull(
        "The Bar project should have been generated",
        barProjectGenerator);

    assertNotNull(
        "The Baz project should have been generated",
        bazProjectGenerator);

    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(),
        "//foo:FooBin");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(),
        "//foo:FooLib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(),
        "//foo:FooBinTest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(),
        "//foo:FooLibTest");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        barProjectGenerator.getGeneratedProject(),
        "//bar:BarLib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        bazProjectGenerator.getGeneratedProject(),
        "//baz:BazLib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        quxProjectGenerator.getGeneratedProject(),
        "//qux:QuxBin");

    XCScheme mainScheme = generator.getSchemeGenerators().get("workspace").getOutputScheme().get();
    XCScheme.BuildAction mainSchemeBuildAction = mainScheme.getBuildAction().get();
    // I wish we could use Hamcrest contains() here, but we hit
    // https://code.google.com/p/hamcrest/issues/detail?id=190 if we do that.
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries(),
        hasSize(2));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(0),
        withNameAndBuildingFor(
            "BarLib",
            equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(1),
        withNameAndBuildingFor(
            "QuxBin",
            equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));

    XCScheme fooScheme = generator.getSchemeGenerators().get("FooScheme").getOutputScheme().get();
    XCScheme.BuildAction fooSchemeBuildAction = fooScheme.getBuildAction().get();
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries(),
        hasSize(6));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(0),
        withNameAndBuildingFor(
            "BarLib",
            equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(1),
        withNameAndBuildingFor(
            "FooLib",
            equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(2),
        withNameAndBuildingFor(
            "FooBin",
            equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(3),
        withNameAndBuildingFor(
            "BazLib",
            equalTo(XCScheme.BuildActionEntry.BuildFor.TEST_ONLY)));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(4),
        withNameAndBuildingFor(
            "FooLibTest",
            equalTo(XCScheme.BuildActionEntry.BuildFor.TEST_ONLY)));
    assertThat(
        fooSchemeBuildAction.getBuildActionEntries().get(5),
        withNameAndBuildingFor(
            "FooBinTest",
            equalTo(XCScheme.BuildActionEntry.BuildFor.TEST_ONLY)));
  }

  @Test
  public void targetsForWorkspaceWithExtraTargets() throws IOException {
    BuildTarget fooLibTarget = BuildTarget.builder(ROOT, "//foo", "FooLib").build();
    TargetNode<AppleNativeTargetDescriptionArg> fooLib = AppleLibraryBuilder
        .createBuilder(fooLibTarget)
        .build();

    BuildTarget barLibTarget = BuildTarget.builder(ROOT, "//bar", "BarLib").build();
    TargetNode<AppleNativeTargetDescriptionArg> barLib = AppleLibraryBuilder
        .createBuilder(barLibTarget)
        .build();

    BuildTarget bazLibTarget = BuildTarget.builder(ROOT, "//baz", "BazLib").build();
    TargetNode<AppleNativeTargetDescriptionArg> bazLib = AppleLibraryBuilder
        .createBuilder(bazLibTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(barLibTarget)))
        .build();

    TargetNode<XcodeWorkspaceConfigDescription.Arg> workspaceNode = XcodeWorkspaceConfigBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//foo", "workspace").build())
        .setWorkspaceName(Optional.of("workspace"))
        .setSrcTarget(Optional.of(fooLibTarget))
        .setExtraTargets(Optional.of(ImmutableSortedSet.of(bazLibTarget)))
        .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        fooLib, barLib, bazLib, workspaceNode);

    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspaceNode.getConstructorArg(),
        workspaceNode.getBuildTarget(),
        ImmutableSet.of(ProjectGenerator.Option.INCLUDE_TESTS,
            ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS),
        false /* combinedProject */,
        false /* buildWithBuck */,
        ImmutableList.<String>of(),
        false /* parallelizeBuild */,
        false /* attemptToDetermineBestCxxPlatform */,
        new AlwaysFoundExecutableFinder(),
        ImmutableMap.<String, String>of(),
        PLATFORMS,
        DEFAULT_PLATFORM,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph),
        getFakeBuckEventBus());
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    ProjectGenerator fooProjectGenerator =
        projectGenerators.get(Paths.get("foo"));
    ProjectGenerator barProjectGenerator =
        projectGenerators.get(Paths.get("bar"));
    ProjectGenerator bazProjectGenerator =
        projectGenerators.get(Paths.get("baz"));

    assertNotNull(
        "The Foo project should have been generated",
        fooProjectGenerator);

    assertNotNull(
        "The Bar project should have been generated",
        barProjectGenerator);

    assertNotNull(
        "The Baz project should have been generated",
        bazProjectGenerator);

    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        fooProjectGenerator.getGeneratedProject(),
        "//foo:FooLib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        barProjectGenerator.getGeneratedProject(),
        "//bar:BarLib");
    ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        bazProjectGenerator.getGeneratedProject(),
        "//baz:BazLib");

    XCScheme mainScheme = generator.getSchemeGenerators().get("workspace").getOutputScheme().get();
    XCScheme.BuildAction mainSchemeBuildAction = mainScheme.getBuildAction().get();
    // I wish we could use Hamcrest contains() here, but we hit
    // https://code.google.com/p/hamcrest/issues/detail?id=190 if we do that.
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries(),
        hasSize(3));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(0),
        withNameAndBuildingFor(
            "FooLib",
            equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(1),
        withNameAndBuildingFor(
            "BarLib",
            equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(2),
        withNameAndBuildingFor(
            "BazLib",
            equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
  }

  @Test
  public void enablingParallelizeBuild() throws IOException {
    BuildTarget fooLibTarget = BuildTarget.builder(ROOT, "//foo", "FooLib").build();
    TargetNode<AppleNativeTargetDescriptionArg> fooLib = AppleLibraryBuilder
        .createBuilder(fooLibTarget)
        .build();

    TargetNode<XcodeWorkspaceConfigDescription.Arg> workspaceNode = XcodeWorkspaceConfigBuilder
        .createBuilder(BuildTarget.builder(ROOT, "//foo", "workspace").build())
        .setWorkspaceName(Optional.of("workspace"))
        .setSrcTarget(Optional.of(fooLibTarget))
        .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(fooLib, workspaceNode);

    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspaceNode.getConstructorArg(),
        workspaceNode.getBuildTarget(),
        ImmutableSet.of(ProjectGenerator.Option.INCLUDE_TESTS,
            ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS),
        false /* combinedProject */,
        false /* buildWithBuck */,
        ImmutableList.<String>of(),
        true /* parallelizeBuild */,
        false /* attemptToDetermineBestCxxPlatform */,
        new AlwaysFoundExecutableFinder(),
        ImmutableMap.<String, String>of(),
        PLATFORMS,
        DEFAULT_PLATFORM,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph),
        getFakeBuckEventBus());
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    XCScheme mainScheme = generator.getSchemeGenerators().get("workspace").getOutputScheme().get();
    XCScheme.BuildAction mainSchemeBuildAction = mainScheme.getBuildAction().get();
    // I wish we could use Hamcrest contains() here, but we hit
    // https://code.google.com/p/hamcrest/issues/detail?id=190 if we do that.
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries(),
        hasSize(1));
    assertThat(
        mainSchemeBuildAction.getBuildActionEntries().get(0),
        withNameAndBuildingFor(
            "FooLib",
            equalTo(XCScheme.BuildActionEntry.BuildFor.DEFAULT)));
    assertThat(
        mainSchemeBuildAction.getParallelizeBuild(),
        is(true));
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

  private Matcher<XCScheme.TestableReference> testableWithName(String name) {
    return new FeatureMatcher<XCScheme.TestableReference, String>(
        equalTo(name), "TestableReference named", "name") {
      @Override
      protected String featureValueOf(XCScheme.TestableReference testableReference) {
        return testableReference.getBuildableReference().blueprintName;
      }
    };
  }

  private Matcher<XCScheme.BuildActionEntry> withNameAndBuildingFor(
      String name,
      Matcher<? super EnumSet<XCScheme.BuildActionEntry.BuildFor>> buildFor) {
    return AllOf.allOf(
        buildActionEntryWithName(name),
        new FeatureMatcher<
            XCScheme.BuildActionEntry,
            EnumSet<XCScheme.BuildActionEntry.BuildFor>>(buildFor, "Building for", "BuildFor") {
          @Override
          protected EnumSet<XCScheme.BuildActionEntry.BuildFor> featureValueOf(
              XCScheme.BuildActionEntry entry) {
            return entry.getBuildFor();
          }
        });
  }

  private Function<TargetNode<?>, Path> getOutputPathOfNodeFunction(final TargetGraph targetGraph) {
    return new Function<TargetNode<?>, Path>() {
      @Nullable
      @Override
      public Path apply(TargetNode<?> input) {
        TargetGraphToActionGraph targetGraphToActionGraph = new TargetGraphToActionGraph(
            BuckEventBusFactory.newInstance(),
            new BuildTargetNodeToBuildRuleTransformer(),
            new NullFileHashCache());
        TargetGraph subgraph = targetGraph.getSubgraph(
            ImmutableSet.of(
                input));
        ActionGraph actionGraph =
            Preconditions.checkNotNull(targetGraphToActionGraph.apply(subgraph)).getFirst();
        BuildRule rule = Preconditions.checkNotNull(
            actionGraph.findBuildRuleByTarget(input.getBuildTarget()));
        return rule.getPathToOutput();
      }
    };
  }

}
