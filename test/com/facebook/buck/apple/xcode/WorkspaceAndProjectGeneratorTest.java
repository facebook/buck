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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.AppleBinaryBuilder;
import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleBundleBuilder;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleTestBuilder;
import com.facebook.buck.apple.XcodeProjectConfigBuilder;
import com.facebook.buck.apple.XcodeWorkspaceConfigBuilder;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.timing.SettableFakeClock;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class WorkspaceAndProjectGeneratorTest {

  private SettableFakeClock clock;
  private ProjectFilesystem projectFilesystem;
  private FakeProjectFilesystem fakeProjectFilesystem;
  private ExecutionContext executionContext;
  private BuildRuleResolver buildRuleResolver;
  private SourcePathResolver sourcePathResolver;

  private TargetGraph targetGraph;
  private TargetNode<XcodeWorkspaceConfigDescription.Arg> workspaceNode;
  private TargetNode<?> fooProjectNode;
  private TargetNode<?> barProjectNode;
  private TargetNode<?> bazProjectNode;
  private TargetNode<?> quxProjectNode;

  @Before
  public void setUp() throws IOException {
    clock = new SettableFakeClock(0, 0);
    fakeProjectFilesystem = new FakeProjectFilesystem(clock);
    projectFilesystem = fakeProjectFilesystem;
    executionContext = TestExecutionContext.newInstance();
    buildRuleResolver = new BuildRuleResolver();
    sourcePathResolver = new SourcePathResolver(buildRuleResolver);

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
    // Calling generate on FooBin should pull in everything except BazLibTest and QuxBin

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
        .build();

    BuildTarget bazLibTarget = BuildTarget.builder("//baz", "lib").build();
    TargetNode<?> bazLibNode = AppleLibraryBuilder
        .createBuilder(bazLibTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooLibTarget)))
        .build();

    BuildTarget bazTestLibTarget = BuildTarget
        .builder("//baz", "testlib")
        .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
        .build();
    TargetNode<?> bazTestLibNode = AppleLibraryBuilder
        .createBuilder(bazTestLibTarget)
        .build();

    BuildTarget bazTestBundleTarget = BuildTarget.builder("//baz", "xctest").build();
    TargetNode<?> bazTestBundleNode = AppleBundleBuilder
        .createBuilder(bazTestBundleTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setBinary(bazTestLibTarget)
        .build();

    BuildTarget bazTestTarget = BuildTarget.builder("//baz", "test").build();
    TargetNode<?> bazTestNode = AppleTestBuilder
        .createBuilder(bazTestTarget)
        .setTestBundle(bazTestBundleTarget)
        .setSourceUnderTest(Optional.of(ImmutableSortedSet.of(bazLibTarget)))
        .build();

    BuildTarget fooTestLibTarget = BuildTarget
        .builder("//foo", "lib-testlib")
        .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
        .build();
    TargetNode<?> fooTestLibNode = AppleLibraryBuilder
        .createBuilder(fooTestLibTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(bazLibTarget)))
        .build();

    BuildTarget fooTestBundleTarget = BuildTarget.builder("//foo", "lib-xctest").build();
    TargetNode<?> fooTestBundleNode = AppleBundleBuilder
        .createBuilder(fooTestBundleTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setBinary(fooTestLibTarget)
        .build();

    BuildTarget fooTestTarget = BuildTarget.builder("//foo", "lib-test").build();
    TargetNode<?> fooTestNode = AppleTestBuilder
        .createBuilder(fooTestTarget)
        .setTestBundle(fooTestBundleTarget)
        .setSourceUnderTest(Optional.of(ImmutableSortedSet.of(fooLibTarget)))
        .build();

    BuildTarget fooBinTestLibTarget = BuildTarget
        .builder("//foo", "bin-testlib")
        .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
        .build();
    TargetNode<?> fooBinTestLibNode = AppleLibraryBuilder
        .createBuilder(fooBinTestLibTarget)
        .build();

    BuildTarget fooBinTestBundleTarget = BuildTarget.builder("//foo", "bin-xctest").build();
    TargetNode<?> fooBinTestBundleNode = AppleBundleBuilder
        .createBuilder(fooBinTestBundleTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setBinary(fooBinTestLibTarget)
        .build();

    BuildTarget fooBinTestTarget = BuildTarget.builder("//foo", "bin-test").build();
    TargetNode<?> fooBinTestNode = AppleTestBuilder
        .createBuilder(fooBinTestTarget)
        .setTestBundle(fooBinTestBundleTarget)
        .setSourceUnderTest(Optional.of(ImmutableSortedSet.of(fooBinTarget)))
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
                fooTestLibTarget,
                fooTestBundleTarget,
                fooBinTestLibTarget,
                fooBinTestBundleTarget))
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
        .setRules(
            ImmutableSortedSet.of(
                bazLibTarget,
                bazTestLibTarget,
                bazTestBundleTarget))
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
        bazTestLibNode,
        bazTestBundleNode,
        bazTestNode,
        fooTestLibNode,
        fooTestBundleNode,
        fooTestNode,
        fooBinTestLibNode,
        fooBinTestBundleNode,
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
        executionContext,
        buildRuleResolver,
        sourcePathResolver,
        workspaceNode,
        ImmutableSet.<ProjectGenerator.Option>of(),
        AppleBuildRules.getSourceTargetToTestNodesMap(targetGraph.getNodes()),
        false);
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
        executionContext,
        buildRuleResolver,
        sourcePathResolver,
        workspaceNode,
        ImmutableSet.<ProjectGenerator.Option>of(),
        AppleBuildRules.getSourceTargetToTestNodesMap(targetGraph.getNodes()),
        true);
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

}
