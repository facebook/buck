/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.apple.AppleBinaryBuilder;
import com.facebook.buck.apple.AppleBundleBuilder;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.apple.AppleTestBuilder;
import com.facebook.buck.apple.ProjectGenerator;
import com.facebook.buck.apple.ProjectGeneratorTestUtils;
import com.facebook.buck.apple.XcodeWorkspaceConfigBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class ProjectCommandXcodeTest {

  private TargetNode<?> barLibNode;
  private TargetNode<?> fooLibNode;
  private TargetNode<?> fooBinBinaryNode;
  private TargetNode<?> fooBinNode;
  private TargetNode<?> bazLibNode;
  private TargetNode<?> bazTestNode;
  private TargetNode<?> fooTestNode;
  private TargetNode<?> fooBinTestNode;
  private TargetNode<?> quxBinNode;
  private TargetNode<?> workspaceNode;
  private TargetNode<?> workspaceExtraTestNode;
  private TargetNode<?> smallWorkspaceNode;

  TargetGraph targetGraph;

  @Before
  public void buildGraph() {
    // Create the following dep tree:
    //
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
    // FooBin and BazLib use "tests" to specify their tests while FooLibTest uses source_under_test
    // to specify that it is a test of FooLib.

    BuildTarget bazTestTarget = BuildTargetFactory.newInstance("//baz:xctest");
    BuildTarget fooBinTestTarget = BuildTargetFactory.newInstance("//foo:bin-xctest");

    BuildTarget barLibTarget = BuildTargetFactory.newInstance("//bar:lib");
    barLibNode = AppleLibraryBuilder
        .createBuilder(barLibTarget)
        .build();

    BuildTarget bazLibTarget = BuildTargetFactory.newInstance("//baz:lib");
    bazLibNode = AppleLibraryBuilder
        .createBuilder(bazLibTarget)
        .setTests(Optional.of(ImmutableSortedSet.of(bazTestTarget)))
        .build();

    BuildTarget fooTestTarget = BuildTargetFactory.newInstance("//foo:lib-xctest");
    fooTestNode = AppleTestBuilder
        .createBuilder(fooTestTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setDeps(Optional.of(ImmutableSortedSet.of(bazLibTarget)))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .build();

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo:lib");
    fooLibNode = AppleLibraryBuilder
        .createBuilder(fooLibTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(barLibTarget)))
        .setTests(Optional.of(ImmutableSortedSet.of(fooTestTarget)))
        .build();

    BuildTarget fooBinBinaryTarget = BuildTargetFactory.newInstance("//foo:binbinary");
    fooBinBinaryNode = AppleBinaryBuilder
        .createBuilder(fooBinBinaryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooLibTarget)))
        .build();

    BuildTarget fooBinTarget = BuildTargetFactory.newInstance("//foo:bin");
    fooBinNode = AppleBundleBuilder
        .createBuilder(fooBinTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.APP))
        .setBinary(fooBinBinaryTarget)
        .setTests(Optional.of(ImmutableSortedSet.of(fooBinTestTarget)))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .build();

    bazTestNode = AppleTestBuilder
        .createBuilder(bazTestTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(bazLibTarget)))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .build();

    fooBinTestNode = AppleTestBuilder
        .createBuilder(fooBinTestTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooBinTarget)))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .build();

    BuildTarget quxBinTarget = BuildTargetFactory.newInstance("//qux:bin");
    quxBinNode = AppleBinaryBuilder
        .createBuilder(quxBinTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(barLibTarget)))
        .build();

    BuildTarget workspaceExtraTestTarget = BuildTargetFactory.newInstance("//foo:extra-xctest");
    workspaceExtraTestNode = AppleTestBuilder
        .createBuilder(workspaceExtraTestTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setInfoPlist(new FakeSourcePath("Info.plist"))
        .build();

    BuildTarget workspaceTarget = BuildTargetFactory.newInstance("//foo:workspace");
    workspaceNode = XcodeWorkspaceConfigBuilder
        .createBuilder(workspaceTarget)
        .setWorkspaceName(Optional.of("workspace"))
        .setSrcTarget(Optional.of(fooBinTarget))
        .setExtraTests(Optional.of(ImmutableSortedSet.of(workspaceExtraTestTarget)))
        .build();

    BuildTarget smallWorkspaceTarget = BuildTargetFactory.newInstance("//baz:small-workspace");
    smallWorkspaceNode = XcodeWorkspaceConfigBuilder
        .createBuilder(smallWorkspaceTarget)
        .setWorkspaceName(Optional.of("small-workspace"))
        .setSrcTarget(Optional.of(bazLibTarget))
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
        workspaceExtraTestNode,
        workspaceNode,
        smallWorkspaceNode);
  }



  @Test
  public void testCreateTargetGraphWithoutTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommand.Ide.XCODE,
        ImmutableSet.<BuildTarget>of(),
        /* withTests = */ false,
        /* withDependenciesTests = */ false);

    assertEquals(
        ImmutableSortedSet.<TargetNode<?>>of(
            workspaceNode,
            fooBinNode,
            fooBinBinaryNode,
            fooLibNode,
            barLibNode,
            smallWorkspaceNode,
            bazLibNode,
            workspaceExtraTestNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphWithTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommand.Ide.XCODE,
        ImmutableSet.<BuildTarget>of(),
        /* withTests = */ true,
        /* withDependenciesTests */ true);

    assertEquals(
        ImmutableSortedSet.<TargetNode<?>>of(
            workspaceNode,
            fooBinNode,
            fooBinBinaryNode,
            fooLibNode,
            fooBinTestNode,
            fooTestNode,
            barLibNode,
            smallWorkspaceNode,
            bazLibNode,
            bazTestNode,
            workspaceExtraTestNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSliceWithoutTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommand.Ide.XCODE,
        ImmutableSet.of(workspaceNode.getBuildTarget()),
        /* withTests = */ false,
        /* withDependenciesTests */ false);

    assertEquals(
        ImmutableSortedSet.<TargetNode<?>>of(
            workspaceNode,
            fooBinNode,
            fooBinBinaryNode,
            fooLibNode,
            barLibNode,
            workspaceExtraTestNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSliceWithTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommand.Ide.XCODE,
        ImmutableSet.of(workspaceNode.getBuildTarget()),
        /* withTests = */ true,
        /* withDependenciesTests */ true);

    assertEquals(
        ImmutableSortedSet.<TargetNode<?>>of(
            workspaceNode,
            fooBinNode,
            fooBinBinaryNode,
            fooLibNode,
            fooBinTestNode,
            fooTestNode,
            barLibNode,
            bazLibNode,
            workspaceExtraTestNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSmallSliceWithoutTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommand.Ide.XCODE,
        ImmutableSet.of(smallWorkspaceNode.getBuildTarget()),
        /* withTests = */ false,
        /* withDependenciesTests */ false);

    assertEquals(
        ImmutableSortedSet.of(
            smallWorkspaceNode,
            bazLibNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSmallSliceWithTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommand.Ide.XCODE,
        ImmutableSet.of(smallWorkspaceNode.getBuildTarget()),
        /* withTests = */ true,
        /* withDependenciesTests */ true);

    assertEquals(
        ImmutableSortedSet.of(
            smallWorkspaceNode,
            bazLibNode,
            bazTestNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testTargetWithTests() throws IOException, InterruptedException {
    Map<Path, ProjectGenerator> projectGenerators = generateProjectsForTests(
        ImmutableSet.of(fooBinNode.getBuildTarget()),
        /* withTests = */ true,
        /* withDependenciesTests */ true);

    ProjectGeneratorTestUtils.assertTargetExists(
        projectGenerators.get(Paths.get("foo")), "bin-xctest");
    ProjectGeneratorTestUtils.assertTargetExists(
        projectGenerators.get(Paths.get("foo")), "lib-xctest");
  }

  private Map<Path, ProjectGenerator> generateProjectsForTests(
      ImmutableSet<BuildTarget> passedInTargetsSet,
      boolean isWithTests,
      boolean isWithDependenciesTests)
      throws IOException, InterruptedException {
    return ProjectCommandTests.generateWorkspacesForTargets(
        targetGraph,
        passedInTargetsSet,
        isWithTests,
        isWithDependenciesTests,
        /* isReadonly = */ false,
        /* isBuildWithBuck = */ false,
        /* isCombinedProjects = */ false,
        /* isCombinesTestBundles = */ false);
  }

  @Test
  public void testTargetWithoutDependenciesTests() throws IOException, InterruptedException {
    Map<Path, ProjectGenerator> projectGenerators = generateProjectsForTests(
        ImmutableSet.of(fooBinNode.getBuildTarget()),
        /* withTests = */ true,
        /* withDependenciesTests */ false);

    ProjectGeneratorTestUtils.assertTargetExists(
        projectGenerators.get(Paths.get("foo")), "bin-xctest");
    ProjectGeneratorTestUtils.assertTargetDoesNotExists(
        projectGenerators.get(Paths.get("foo")), "lib-xctest");
  }

  @Test
  public void testTargetWithoutTests() throws IOException, InterruptedException {
    Map<Path, ProjectGenerator> projectGenerators = generateProjectsForTests(
        ImmutableSet.of(fooBinNode.getBuildTarget()),
        /* withTests = */ false,
        /* withDependenciesTests */ false);

    ProjectGeneratorTestUtils.assertTargetDoesNotExists(
        projectGenerators.get(Paths.get("foo")), "bin-xctest");
    ProjectGeneratorTestUtils.assertTargetDoesNotExists(
        projectGenerators.get(Paths.get("foo")), "lib-xctest");
  }

  @Test
  public void testWorkspaceWithoutDependenciesTests() throws IOException, InterruptedException {
    Map<Path, ProjectGenerator> projectGenerators = generateProjectsForTests(
        ImmutableSet.of(workspaceNode.getBuildTarget()),
        /* withTests = */ true,
        /* withDependenciesTests */ false);

    ProjectGeneratorTestUtils.assertTargetExists(
        projectGenerators.get(Paths.get("foo")), "bin-xctest");
    ProjectGeneratorTestUtils.assertTargetDoesNotExists(
        projectGenerators.get(Paths.get("foo")), "lib-xctest");
    ProjectGeneratorTestUtils.assertTargetExists(
        projectGenerators.get(Paths.get("foo")), "extra-xctest");
  }

  @Test
  public void testWorkspaceWithoutExtraTestsWithoutDependenciesTests()
      throws IOException, InterruptedException {
    Map<Path, ProjectGenerator> projectGenerators = generateProjectsForTests(
        ImmutableSet.of(smallWorkspaceNode.getBuildTarget()),
        /* withTests = */ true,
        /* withDependenciesTests */ false);

    ProjectGeneratorTestUtils.assertTargetExists(
        projectGenerators.get(Paths.get("baz")), "lib");
    ProjectGeneratorTestUtils.assertTargetExists(
        projectGenerators.get(Paths.get("baz")), "xctest");
  }
}
