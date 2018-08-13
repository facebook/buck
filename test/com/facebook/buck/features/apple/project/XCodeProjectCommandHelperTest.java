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

package com.facebook.buck.features.apple.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleBinaryBuilder;
import com.facebook.buck.apple.AppleBundleBuilder;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.apple.AppleTestBuilder;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetGraphAndTargets;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class XCodeProjectCommandHelperTest {

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

  private TargetGraph targetGraph;

  @Before
  public void buildGraph() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);

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
    // FooBin and BazLib and FooLibTest use "tests" to specify their tests.

    BuildTarget bazTestTarget = BuildTargetFactory.newInstance("//baz:xctest");
    BuildTarget fooBinTestTarget = BuildTargetFactory.newInstance("//foo:bin-xctest");

    BuildTarget barLibTarget = BuildTargetFactory.newInstance("//bar:lib");
    barLibNode = AppleLibraryBuilder.createBuilder(barLibTarget).build();

    BuildTarget bazLibTarget = BuildTargetFactory.newInstance("//baz:lib");
    bazLibNode =
        AppleLibraryBuilder.createBuilder(bazLibTarget)
            .setTests(ImmutableSortedSet.of(bazTestTarget))
            .build();

    BuildTarget fooTestTarget = BuildTargetFactory.newInstance("//foo:lib-xctest");
    fooTestNode =
        AppleTestBuilder.createBuilder(fooTestTarget)
            .setDeps(ImmutableSortedSet.of(bazLibTarget))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo:lib");
    fooLibNode =
        AppleLibraryBuilder.createBuilder(fooLibTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .setTests(ImmutableSortedSet.of(fooTestTarget))
            .build();

    BuildTarget fooBinBinaryTarget = BuildTargetFactory.newInstance("//foo:binbinary");
    fooBinBinaryNode =
        AppleBinaryBuilder.createBuilder(fooBinBinaryTarget)
            .setDeps(ImmutableSortedSet.of(fooLibTarget))
            .build();

    BuildTarget fooBinTarget = BuildTargetFactory.newInstance("//foo:bin");
    fooBinNode =
        AppleBundleBuilder.createBuilder(fooBinTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setBinary(fooBinBinaryTarget)
            .setTests(ImmutableSortedSet.of(fooBinTestTarget))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    bazTestNode =
        AppleTestBuilder.createBuilder(bazTestTarget)
            .setDeps(ImmutableSortedSet.of(bazLibTarget))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    fooBinTestNode =
        AppleTestBuilder.createBuilder(fooBinTestTarget)
            .setDeps(ImmutableSortedSet.of(fooBinTarget))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    BuildTarget quxBinTarget = BuildTargetFactory.newInstance("//qux:bin");
    quxBinNode =
        AppleBinaryBuilder.createBuilder(quxBinTarget)
            .setDeps(ImmutableSortedSet.of(barLibTarget))
            .build();

    BuildTarget workspaceExtraTestTarget = BuildTargetFactory.newInstance("//foo:extra-xctest");
    workspaceExtraTestNode =
        AppleTestBuilder.createBuilder(workspaceExtraTestTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    BuildTarget workspaceTarget = BuildTargetFactory.newInstance("//foo:workspace");
    workspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(workspaceTarget)
            .setWorkspaceName(Optional.of("workspace"))
            .setSrcTarget(Optional.of(fooBinTarget))
            .setExtraTests(ImmutableSortedSet.of(workspaceExtraTestTarget))
            .build();

    BuildTarget smallWorkspaceTarget = BuildTargetFactory.newInstance("//baz:small-workspace");
    smallWorkspaceNode =
        XcodeWorkspaceConfigBuilder.createBuilder(smallWorkspaceTarget)
            .setWorkspaceName(Optional.of("small-workspace"))
            .setSrcTarget(Optional.of(bazLibTarget))
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
            workspaceExtraTestNode,
            workspaceNode,
            smallWorkspaceNode);
  }

  @Test
  public void testCreateTargetGraphWithoutTests() {
    TargetGraphAndTargets targetGraphAndTargets =
        createTargetGraph(
            targetGraph,
            ImmutableSet.of(),
            /* withTests = */ false,
            /* withDependenciesTests = */ false);

    assertEquals(
        ImmutableSortedSet.of(
            workspaceNode,
            fooBinNode,
            fooBinBinaryNode,
            fooLibNode,
            barLibNode,
            smallWorkspaceNode,
            bazLibNode,
            workspaceExtraTestNode),
        ImmutableSortedSet.copyOf(targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphWithTests() {
    TargetGraphAndTargets targetGraphAndTargets =
        createTargetGraph(
            targetGraph,
            ImmutableSet.of(),
            /* withTests = */ true,
            /* withDependenciesTests */ true);

    assertEquals(
        ImmutableSortedSet.of(
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
        ImmutableSortedSet.copyOf(targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSliceWithoutTests() {
    TargetGraphAndTargets targetGraphAndTargets =
        createTargetGraph(
            targetGraph,
            ImmutableSet.of(workspaceNode.getBuildTarget()),
            /* withTests = */ false,
            /* withDependenciesTests */ false);

    assertEquals(
        ImmutableSortedSet.of(
            workspaceNode,
            fooBinNode,
            fooBinBinaryNode,
            fooLibNode,
            barLibNode,
            workspaceExtraTestNode),
        ImmutableSortedSet.copyOf(targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSliceWithTests() {
    TargetGraphAndTargets targetGraphAndTargets =
        createTargetGraph(
            targetGraph,
            ImmutableSet.of(workspaceNode.getBuildTarget()),
            /* withTests = */ true,
            /* withDependenciesTests */ true);

    assertEquals(
        ImmutableSortedSet.of(
            workspaceNode,
            fooBinNode,
            fooBinBinaryNode,
            fooLibNode,
            fooBinTestNode,
            fooTestNode,
            barLibNode,
            bazLibNode,
            workspaceExtraTestNode),
        ImmutableSortedSet.copyOf(targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSmallSliceWithoutTests() {
    TargetGraphAndTargets targetGraphAndTargets =
        createTargetGraph(
            targetGraph,
            ImmutableSet.of(smallWorkspaceNode.getBuildTarget()),
            /* withTests = */ false,
            /* withDependenciesTests */ false);

    assertEquals(
        ImmutableSortedSet.of(smallWorkspaceNode, bazLibNode),
        ImmutableSortedSet.copyOf(targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSmallSliceWithTests() {
    TargetGraphAndTargets targetGraphAndTargets =
        createTargetGraph(
            targetGraph,
            ImmutableSet.of(smallWorkspaceNode.getBuildTarget()),
            /* withTests = */ true,
            /* withDependenciesTests */ true);

    assertEquals(
        ImmutableSortedSet.of(smallWorkspaceNode, bazLibNode, bazTestNode),
        ImmutableSortedSet.copyOf(targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testTargetWithTests() throws IOException, InterruptedException {
    Map<Path, ProjectGenerator> projectGenerators =
        generateProjectsForTests(
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
    return generateWorkspacesForTargets(
        targetGraph, passedInTargetsSet, isWithTests, isWithDependenciesTests);
  }

  @Test
  public void testTargetWithoutDependenciesTests() throws IOException, InterruptedException {
    Map<Path, ProjectGenerator> projectGenerators =
        generateProjectsForTests(
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
    Map<Path, ProjectGenerator> projectGenerators =
        generateProjectsForTests(
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
    Map<Path, ProjectGenerator> projectGenerators =
        generateProjectsForTests(
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
    Map<Path, ProjectGenerator> projectGenerators =
        generateProjectsForTests(
            ImmutableSet.of(smallWorkspaceNode.getBuildTarget()),
            /* withTests = */ true,
            /* withDependenciesTests */ false);

    ProjectGeneratorTestUtils.assertTargetExists(projectGenerators.get(Paths.get("baz")), "lib");
    ProjectGeneratorTestUtils.assertTargetExists(projectGenerators.get(Paths.get("baz")), "xctest");
  }

  private static TargetGraphAndTargets createTargetGraph(
      TargetGraph projectGraph,
      ImmutableSet<BuildTarget> passedInTargetsSet,
      boolean withTests,
      boolean withDependenciesTests) {
    ImmutableSet<BuildTarget> graphRoots;
    if (!passedInTargetsSet.isEmpty()) {
      graphRoots = passedInTargetsSet;
    } else {
      graphRoots =
          XCodeProjectCommandHelper.getRootsFromPredicate(
              projectGraph,
              node -> node.getDescription() instanceof XcodeWorkspaceConfigDescription);
    }

    ImmutableSet<BuildTarget> graphRootsOrSourceTargets =
        XCodeProjectCommandHelper.replaceWorkspacesWithSourceTargetsIfPossible(
            graphRoots, projectGraph);

    ImmutableSet<BuildTarget> explicitTests;
    if (withTests) {
      explicitTests =
          XCodeProjectCommandHelper.getExplicitTestTargets(
              graphRootsOrSourceTargets,
              projectGraph,
              withDependenciesTests,
              FocusedModuleTargetMatcher.noFocus());
    } else {
      explicitTests = ImmutableSet.of();
    }

    return TargetGraphAndTargets.create(graphRoots, projectGraph, withTests, explicitTests);
  }

  private static Map<Path, ProjectGenerator> generateWorkspacesForTargets(
      TargetGraph targetGraph,
      ImmutableSet<BuildTarget> passedInTargetsSet,
      boolean isWithTests,
      boolean isWithDependenciesTests)
      throws IOException, InterruptedException {
    TargetGraphAndTargets targetGraphAndTargets =
        createTargetGraph(targetGraph, passedInTargetsSet, isWithTests, isWithDependenciesTests);

    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    Cell cell =
        new TestCellBuilder()
            .setFilesystem(new FakeProjectFilesystem(SettableFakeClock.DO_NOT_CARE))
            .build();
    XCodeProjectCommandHelper.generateWorkspacesForTargets(
        BuckEventBusForTests.newInstance(),
        BuckPluginManagerFactory.createPluginManager(),
        cell,
        FakeBuckConfig.builder().build(),
        TestRuleKeyConfigurationFactory.create(),
        MoreExecutors.newDirectExecutorService(),
        targetGraphAndTargets,
        passedInTargetsSet,
        ProjectGeneratorOptions.builder()
            .setShouldGenerateReadOnlyFiles(false)
            .setShouldIncludeTests(isWithTests)
            .setShouldIncludeDependenciesTests(isWithDependenciesTests)
            .setShouldUseHeaderMaps(true)
            .setShouldMergeHeaderMaps(false)
            .setShouldForceLoadLinkWholeLibraries(false)
            .setShouldGenerateHeaderSymlinkTreesOnly(false)
            .setShouldGenerateMissingUmbrellaHeader(false)
            .setShouldUseShortNamesForTargets(true)
            .setShouldCreateDirectoryStructure(false)
            .build(),
        ImmutableSet.of(),
        FocusedModuleTargetMatcher.noFocus(),
        projectGenerators,
        false,
        new NullPathOutputPresenter());
    return projectGenerators;
  }
}
