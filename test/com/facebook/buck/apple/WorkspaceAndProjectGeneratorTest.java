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
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.xcode.XCScheme;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.timing.SettableFakeClock;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
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

import javax.annotation.Nullable;

public class WorkspaceAndProjectGeneratorTest {

  private ProjectFilesystem projectFilesystem;

  private TargetGraph targetGraph;
  private TargetNode<XcodeWorkspaceConfigDescription.Arg> workspaceNode;

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws IOException {
    projectFilesystem = new FakeProjectFilesystem(new SettableFakeClock(0, 0));

    // Add support files needed by project generation to fake filesystem.
    projectFilesystem.writeContentsToPath(
        "",
        Paths.get(ProjectGenerator.PATH_TO_ASSET_CATALOG_BUILD_PHASE_SCRIPT));
    projectFilesystem.writeContentsToPath(
        "",
        Paths.get(ProjectGenerator.PATH_TO_ASSET_CATALOG_COMPILER));

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

    BuildTarget bazTestTarget = BuildTarget.builder("//baz", "xctest").build();
    BuildTarget fooBinTestTarget = BuildTarget.builder("//foo", "bin-xctest").build();
    BuildTarget fooTestTarget = BuildTarget.builder("//foo", "lib-xctest").build();

    BuildTarget barLibTarget = BuildTarget.builder("//bar", "lib").build();
    TargetNode<?> barLibNode = AppleLibraryBuilder
        .createBuilder(barLibTarget)
        .setUseBuckHeaderMaps(Optional.of(Boolean.TRUE))
        .build();

    BuildTarget fooLibTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> fooLibNode = AppleLibraryBuilder
        .createBuilder(fooLibTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(barLibTarget)))
        .setTests(Optional.of(ImmutableSortedSet.of(fooTestTarget)))
        .setUseBuckHeaderMaps(Optional.of(Boolean.TRUE))
        .build();

    BuildTarget fooBinBinaryTarget = BuildTarget.builder("//foo", "binbinary").build();
    TargetNode<?> fooBinBinaryNode = AppleBinaryBuilder
        .createBuilder(fooBinBinaryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooLibTarget)))
        .setUseBuckHeaderMaps(Optional.of(Boolean.TRUE))
        .build();

    BuildTarget fooBinTarget = BuildTarget.builder("//foo", "bin").build();
    TargetNode<?> fooBinNode = AppleBundleBuilder
        .createBuilder(fooBinTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.APP))
        .setBinary(fooBinBinaryTarget)
        .setTests(Optional.of(ImmutableSortedSet.of(fooBinTestTarget)))
        .build();

    BuildTarget bazLibTarget = BuildTarget.builder("//baz", "lib").build();
    TargetNode<?> bazLibNode = AppleLibraryBuilder
        .createBuilder(bazLibTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooLibTarget)))
        .setTests(Optional.of(ImmutableSortedSet.of(bazTestTarget)))
        .setUseBuckHeaderMaps(Optional.of(Boolean.TRUE))
        .build();

    TargetNode<?> bazTestNode = AppleTestBuilder
        .createBuilder(bazTestTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(bazLibTarget)))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setUseBuckHeaderMaps(Optional.of(Boolean.TRUE))
        .build();

    TargetNode<?> fooTestNode = AppleTestBuilder
        .createBuilder(fooTestTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setDeps(Optional.of(ImmutableSortedSet.of(bazLibTarget)))
        .setUseBuckHeaderMaps(Optional.of(Boolean.TRUE))
        .build();

    TargetNode<?> fooBinTestNode = AppleTestBuilder
        .createBuilder(fooBinTestTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooBinTarget)))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setUseBuckHeaderMaps(Optional.of(Boolean.TRUE))
        .build();

    BuildTarget quxBinTarget = BuildTarget.builder("//qux", "bin").build();
    TargetNode<?> quxBinNode = AppleBinaryBuilder
        .createBuilder(quxBinTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(barLibTarget)))
        .setUseBuckHeaderMaps(Optional.of(Boolean.TRUE))
        .build();

    BuildTarget workspaceTarget = BuildTarget.builder("//foo", "workspace").build();
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

  @Test
  public void workspaceAndProjectsShouldDiscoverDependenciesAndTests() throws IOException {
    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspaceNode.getConstructorArg(),
        workspaceNode.getBuildTarget(),
        ImmutableSet.of(ProjectGenerator.Option.INCLUDE_TESTS),
        false /* combinedProject */,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph));
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
        ImmutableSet.of(ProjectGenerator.Option.INCLUDE_TESTS),
        true /* combinedProject */,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph));
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
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph));
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
    BuildTarget genruleTarget = BuildTarget.builder("//foo", "gen").build();
    TargetNode<GenruleDescription.Arg> genrule  = GenruleBuilder
        .newGenruleBuilder(genruleTarget)
        .setOut("source.m")
        .build();

    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<AppleNativeTargetDescriptionArg> library = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    SourceWithFlags.of(
                        new BuildTargetSourcePath(projectFilesystem, genruleTarget)))))
        .build();

    TargetNode<XcodeWorkspaceConfigDescription.Arg> workspaceNode = XcodeWorkspaceConfigBuilder
        .createBuilder(BuildTarget.builder("//foo", "workspace").build())
        .setSrcTarget(Optional.of(libraryTarget))
        .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(genrule, library);

    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspaceNode.getConstructorArg(),
        workspaceNode.getBuildTarget(),
        ImmutableSet.<ProjectGenerator.Option>of(),
        false /* combinedProject */,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph));
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    assertEquals(
        generator.getRequiredBuildTargets(),
        ImmutableSet.of(genruleTarget));
  }

  @Test
  public void requiredBuildTargetsForCombinedProject() throws IOException {
    BuildTarget genruleTarget = BuildTarget.builder("//foo", "gen").build();
    TargetNode<GenruleDescription.Arg> genrule  = GenruleBuilder
        .newGenruleBuilder(genruleTarget)
        .setOut("source.m")
        .build();

    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<AppleNativeTargetDescriptionArg> library = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    SourceWithFlags.of(
                        new BuildTargetSourcePath(projectFilesystem, genruleTarget)))))
        .build();

    TargetNode<XcodeWorkspaceConfigDescription.Arg> workspaceNode = XcodeWorkspaceConfigBuilder
        .createBuilder(BuildTarget.builder("//foo", "workspace").build())
        .setSrcTarget(Optional.of(libraryTarget))
        .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(genrule, library);

    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspaceNode.getConstructorArg(),
        workspaceNode.getBuildTarget(),
        ImmutableSet.<ProjectGenerator.Option>of(),
        true /* combinedProject */,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph));
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    assertEquals(
        generator.getRequiredBuildTargets(),
        ImmutableSet.of(genruleTarget));
  }

  @Test
  public void combinedTestBundle() throws IOException {
    TargetNode<AppleTestDescription.Arg> combinableTest1 = AppleTestBuilder
        .createBuilder(BuildTarget.builder("//foo", "combinableTest1").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setCanGroup(Optional.of(true))
        .build();
    TargetNode<AppleTestDescription.Arg> combinableTest2 = AppleTestBuilder
        .createBuilder(BuildTarget.builder("//bar", "combinableTest2").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setCanGroup(Optional.of(true))
        .build();
    TargetNode<AppleTestDescription.Arg> testMarkedUncombinable = AppleTestBuilder
        .createBuilder(BuildTarget.builder("//foo", "testMarkedUncombinable").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setCanGroup(Optional.of(false))
        .build();
    TargetNode<AppleTestDescription.Arg> anotherTest = AppleTestBuilder
        .createBuilder(BuildTarget.builder("//foo", "anotherTest").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.OCTEST))
        .setCanGroup(Optional.of(true))
        .build();
    TargetNode<AppleNativeTargetDescriptionArg> library = AppleLibraryBuilder
        .createBuilder(BuildTarget.builder("//foo", "lib").build())
        .setTests(
            Optional.of(
                ImmutableSortedSet.of(
                    combinableTest1.getBuildTarget(),
                    combinableTest2.getBuildTarget(),
                    testMarkedUncombinable.getBuildTarget(),
                    anotherTest.getBuildTarget())))
        .build();
    TargetNode<XcodeWorkspaceConfigDescription.Arg> workspace = XcodeWorkspaceConfigBuilder
        .createBuilder(BuildTarget.builder("//foo", "workspace").build())
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
        ImmutableSet.of(ProjectGenerator.Option.INCLUDE_TESTS),
        false,
        "BUCK",
        getOutputPathOfNodeFunction(targetGraph));
    generator.setGroupableTests(AppleBuildRules.filterGroupableTests(targetGraph.getNodes()));
    Map<Path, ProjectGenerator> projectGenerators = Maps.newHashMap();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    // Tests should become libraries
    PBXTarget combinableTestTarget1 = ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerators.get(Paths.get("foo")).getGeneratedProject(),
        "//foo:combinableTest1");
    assertEquals(
        "Test in the bundle should be built as a static library.",
        PBXTarget.ProductType.STATIC_LIBRARY,
        combinableTestTarget1.getProductType());

    PBXTarget combinableTestTarget2 = ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerators.get(Paths.get("bar")).getGeneratedProject(),
        "//bar:combinableTest2");
    assertEquals(
        "Other test in the bundle should be built as a static library.",
        PBXTarget.ProductType.STATIC_LIBRARY,
        combinableTestTarget2.getProductType());

    // Test not bundled with any others should retain behavior.
    PBXTarget notCombinedTest = ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerators.get(Paths.get("foo")).getGeneratedProject(),
        "//foo:anotherTest");
    assertEquals(
        "Test that is not combined with other tests should also generate a test bundle.",
        PBXTarget.ProductType.STATIC_LIBRARY,
        notCombinedTest.getProductType());

    // Test not bundled with any others should retain behavior.
    PBXTarget uncombinableTest = ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerators.get(Paths.get("foo")).getGeneratedProject(),
        "//foo:testMarkedUncombinable");
    assertEquals(
        "Test marked uncombinable should not be combined",
        PBXTarget.ProductType.UNIT_TEST,
        uncombinableTest.getProductType());

    // Combined test project should be generated with a combined test bundle.
    PBXTarget combinedTestBundle = ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        generator.getCombinedTestsProjectGenerator().get().getGeneratedProject(),
        "_BuckCombinedTest-xctest-0");
    assertEquals(
        "Combined test project target should be test bundle.",
        PBXTarget.ProductType.UNIT_TEST,
        combinedTestBundle.getProductType());

    // Scheme should contain generated test targets.
    XCScheme scheme = generator.getSchemeGenerator().get().getOutputScheme().get();
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
            new BuildTargetNodeToBuildRuleTransformer());
        TargetGraph subgraph = targetGraph.getSubgraph(
            ImmutableSet.of(
                input));
        ActionGraph actionGraph = Preconditions.checkNotNull(
            targetGraphToActionGraph.apply(subgraph));
        BuildRule rule = Preconditions.checkNotNull(
            actionGraph.findBuildRuleByTarget(input.getBuildTarget()));
        return rule.getPathToOutputFile();
      }
    };
  }

}
