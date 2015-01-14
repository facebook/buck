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

package com.facebook.buck.apple.xcode;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.AppleBinaryBuilder;
import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleBundleBuilder;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.apple.AppleNativeTargetDescriptionArg;
import com.facebook.buck.apple.AppleTestBuilder;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.XcodeProjectConfigBuilder;
import com.facebook.buck.apple.XcodeProjectConfigDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfigBuilder;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.timing.SettableFakeClock;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMultimap;
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
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

public class WorkspaceAndProjectGeneratorTest {

  private ProjectFilesystem projectFilesystem;

  private TargetGraph targetGraph;
  private TargetNode<XcodeWorkspaceConfigDescription.Arg> workspaceNode;
  private TargetNode<?> fooProjectNode;
  private TargetNode<?> barProjectNode;
  private TargetNode<?> bazProjectNode;
  private TargetNode<?> quxProjectNode;

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

    BuildTarget barLibTarget = BuildTarget.builder("//bar", "lib").build();
    TargetNode<?> barLibNode = AppleLibraryBuilder
        .createBuilder(barLibTarget)
        .build();

    BuildTarget fooLibTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> fooLibNode = AppleLibraryBuilder
        .createBuilder(fooLibTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(barLibTarget)))
        .build();

    BuildTarget fooBinBinaryTarget = BuildTarget.builder("//foo", "binbinary").build();
    TargetNode<?> fooBinBinaryNode = AppleBinaryBuilder
        .createBuilder(fooBinBinaryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooLibTarget)))
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
        .build();

    TargetNode<?> bazTestNode = AppleTestBuilder
        .createBuilder(bazTestTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(bazLibTarget)))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .build();

    BuildTarget fooTestTarget = BuildTarget.builder("//foo", "lib-xctest").build();
    TargetNode<?> fooTestNode = AppleTestBuilder
        .createBuilder(fooTestTarget)
        .setSourceUnderTest(Optional.of(ImmutableSortedSet.of(fooLibTarget)))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setDeps(Optional.of(ImmutableSortedSet.of(bazLibTarget)))
        .build();

    TargetNode<?> fooBinTestNode = AppleTestBuilder
        .createBuilder(fooBinTestTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooBinTarget)))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .build();

    BuildTarget quxBinTarget = BuildTarget.builder("//qux", "bin").build();
    TargetNode<?> quxBinNode = AppleBinaryBuilder
        .createBuilder(quxBinTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(barLibTarget)))
        .build();

    BuildTarget fooProjectTarget = BuildTarget.builder("//foo", "foo").build();
    fooProjectNode = XcodeProjectConfigBuilder
        .createBuilder(fooProjectTarget)
        .setProjectName("foo")
        .setRules(
            ImmutableSortedSet.of(
                fooLibTarget,
                fooBinBinaryTarget,
                fooBinTarget,
                fooBinTestTarget,
                fooTestTarget))
        .build();

    BuildTarget barProjectTarget = BuildTarget.builder("//bar", "bar").build();
    barProjectNode = XcodeProjectConfigBuilder
        .createBuilder(barProjectTarget)
        .setProjectName("bar")
        .setRules(ImmutableSortedSet.of(barLibTarget))
        .build();

    BuildTarget bazProjectTarget = BuildTarget.builder("//baz", "baz").build();
    bazProjectNode = XcodeProjectConfigBuilder
        .createBuilder(bazProjectTarget)
        .setProjectName("baz")
        .setRules(ImmutableSortedSet.of(bazLibTarget))
        .build();

    BuildTarget quxProjectTarget = BuildTarget.builder("//qux", "qux").build();
    quxProjectNode = XcodeProjectConfigBuilder
        .createBuilder(quxProjectTarget)
        .setProjectName("qux")
        .setRules(ImmutableSortedSet.of(quxBinTarget))
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
        fooProjectNode,
        barProjectNode,
        bazProjectNode,
        quxProjectNode,
        workspaceNode);
  }

  @Test
  public void workspaceAndProjectsShouldDiscoverDependenciesAndTests() throws IOException {
    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspaceNode,
        ImmutableSet.of(ProjectGenerator.Option.INCLUDE_TESTS),
        AppleBuildRules.getSourceTargetToTestNodesMap(targetGraph.getNodes()),
        false /* combinedProject */);
    Map<TargetNode<?>, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    ProjectGenerator fooProjectGenerator =
        projectGenerators.get(fooProjectNode);
    ProjectGenerator barProjectGenerator =
        projectGenerators.get(barProjectNode);
    ProjectGenerator bazProjectGenerator =
        projectGenerators.get(bazProjectNode);
    ProjectGenerator quxProjectGenerator =
        projectGenerators.get(quxProjectNode);

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
        workspaceNode,
        ImmutableSet.of(ProjectGenerator.Option.INCLUDE_TESTS),
        AppleBuildRules.getSourceTargetToTestNodesMap(targetGraph.getNodes()),
        true /* combinedProject */);
    Map<TargetNode<?>, ProjectGenerator> projectGenerators = new HashMap<>();
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
        workspaceNode,
        ImmutableSet.<ProjectGenerator.Option>of(),
        AppleBuildRules.getSourceTargetToTestNodesMap(targetGraph.getNodes()),
        false /* combinedProject */);
    Map<TargetNode<?>, ProjectGenerator> projectGenerators = new HashMap<>();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    ProjectGenerator fooProjectGenerator =
        projectGenerators.get(fooProjectNode);
    ProjectGenerator barProjectGenerator =
        projectGenerators.get(barProjectNode);
    ProjectGenerator bazProjectGenerator =
        projectGenerators.get(bazProjectNode);
    ProjectGenerator quxProjectGenerator =
        projectGenerators.get(quxProjectNode);

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
  public void combinedTestBundle() throws IOException {
    TargetNode<AppleNativeTargetDescriptionArg> library = AppleLibraryBuilder
        .createBuilder(BuildTarget.builder("//foo", "lib").build())
        .build();
    TargetNode<AppleTestDescription.Arg> combinableTest1 = AppleTestBuilder
        .createBuilder(BuildTarget.builder("//foo", "combinableTest1").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setSourceUnderTest(Optional.of(ImmutableSortedSet.of(library.getBuildTarget())))
        .setCanGroup(Optional.of(true))
        .build();
    TargetNode<AppleTestDescription.Arg> combinableTest2 = AppleTestBuilder
        .createBuilder(BuildTarget.builder("//bar", "combinableTest2").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setSourceUnderTest(Optional.of(ImmutableSortedSet.of(library.getBuildTarget())))
        .setCanGroup(Optional.of(true))
        .build();
    TargetNode<AppleTestDescription.Arg> testMarkedUncombinable = AppleTestBuilder
        .createBuilder(BuildTarget.builder("//foo", "testMarkedUncombinable").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setSourceUnderTest(Optional.of(ImmutableSortedSet.of(library.getBuildTarget())))
        .setCanGroup(Optional.of(false))
        .build();
    TargetNode<AppleTestDescription.Arg> anotherTest = AppleTestBuilder
        .createBuilder(BuildTarget.builder("//foo", "anotherTest").build())
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.OCTEST))
        .setSourceUnderTest(Optional.of(ImmutableSortedSet.of(library.getBuildTarget())))
        .setCanGroup(Optional.of(true))
        .build();
    TargetNode<XcodeProjectConfigDescription.Arg> fooProject = XcodeProjectConfigBuilder
        .createBuilder(BuildTarget.builder("//foo", "project").build())
        .setProjectName("foo")
        .setRules(
            ImmutableSortedSet.of(
                library.getBuildTarget(),
                combinableTest1.getBuildTarget(),
                testMarkedUncombinable.getBuildTarget(),
                anotherTest.getBuildTarget()))
        .build();
    TargetNode<XcodeProjectConfigDescription.Arg> barProject = XcodeProjectConfigBuilder
        .createBuilder(BuildTarget.builder("//bar", "project").build())
        .setProjectName("bar")
        .setRules(ImmutableSortedSet.of(combinableTest2.getBuildTarget()))
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
            fooProject,
            barProject,
            workspace);

    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspace,
        ImmutableSet.of(ProjectGenerator.Option.INCLUDE_TESTS),
        ImmutableMultimap.of(
            library.getBuildTarget(), combinableTest1,
            library.getBuildTarget(), combinableTest2,
            library.getBuildTarget(), testMarkedUncombinable,
            library.getBuildTarget(), anotherTest),
        false);
    generator.setGroupableTests(AppleBuildRules.filterGroupableTests(targetGraph.getNodes()));
    Map<TargetNode<?>, ProjectGenerator> projectGenerators = Maps.newHashMap();
    generator.generateWorkspaceAndDependentProjects(projectGenerators);

    // Tests should become libraries
    PBXTarget combinableTestTarget1 = ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerators.get(fooProject).getGeneratedProject(),
        "//foo:combinableTest1");
    assertEquals(
        "Test in the bundle should be built as a static library.",
        PBXTarget.ProductType.STATIC_LIBRARY,
        combinableTestTarget1.getProductType());

    PBXTarget combinableTestTarget2 = ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerators.get(barProject).getGeneratedProject(),
        "//bar:combinableTest2");
    assertEquals(
        "Other test in the bundle should be built as a static library.",
        PBXTarget.ProductType.STATIC_LIBRARY,
        combinableTestTarget2.getProductType());

    // Test not bundled with any others should retain behavior.
    PBXTarget notCombinedTest = ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerators.get(fooProject).getGeneratedProject(),
        "//foo:anotherTest");
    assertEquals(
        "Test that is not combined with other tests should also generate a test bundle.",
        PBXTarget.ProductType.STATIC_LIBRARY,
        notCombinedTest.getProductType());

    // Test not bundled with any others should retain behavior.
    PBXTarget uncombinableTest = ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        projectGenerators.get(fooProject).getGeneratedProject(),
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

  @Test
  public void targetWithoutProject() throws IOException {
    TargetNode<AppleNativeTargetDescriptionArg> fooLib = AppleLibraryBuilder
        .createBuilder(BuildTarget.builder("//foo", "lib").build())
        .build();
    TargetNode<AppleNativeTargetDescriptionArg> barLib = AppleLibraryBuilder
        .createBuilder(BuildTarget.builder("//bar", "lib").build())
        .setDeps(Optional.of(ImmutableSortedSet.of(fooLib.getBuildTarget())))
        .build();
    TargetNode<XcodeProjectConfigDescription.Arg> barProject = XcodeProjectConfigBuilder
        .createBuilder(BuildTarget.builder("//bar", "project").build())
        .setProjectName("bar")
        .setRules(ImmutableSortedSet.of(barLib.getBuildTarget()))
        .build();
    TargetNode<XcodeWorkspaceConfigDescription.Arg> workspace = XcodeWorkspaceConfigBuilder
        .createBuilder(BuildTarget.builder("//bar", "workspace").build())
        .setSrcTarget(Optional.of(barLib.getBuildTarget()))
        .setWorkspaceName(Optional.of("workspace"))
        .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        fooLib,
        barLib,
        barProject,
        workspace);

    WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
        projectFilesystem,
        targetGraph,
        workspace,
        ImmutableSet.<ProjectGenerator.Option>of(),
        ImmutableMultimap.<BuildTarget, TargetNode<AppleTestDescription.Arg>>of(),
        false);

    Map<TargetNode<?>, ProjectGenerator> projectGenerators = Maps.newHashMap();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "No xcode_project_config rule was found for the following targets: [//foo:lib]");
    generator.generateWorkspaceAndDependentProjects(projectGenerators);
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
}
