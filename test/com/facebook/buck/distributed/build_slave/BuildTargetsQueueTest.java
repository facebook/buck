/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SingleThreadedBuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class BuildTargetsQueueTest {

  private final int MAX_UNITS_OF_WORK = 10;
  public static final String TRANSITIVE_DEP_RULE = "//:transitive_dep";
  public static final String HAS_RUNTIME_DEP_RULE = "//:runtime_dep";
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  public static final String TARGET_NAME = "//foo:one";
  public static final String LEAF_TARGET = TARGET_NAME + "_leaf";
  public static final String RIGHT_TARGET = TARGET_NAME + "_right";
  public static final String LEFT_TARGET = TARGET_NAME + "_left";
  public static final String CHAIN_TOP_TARGET = TARGET_NAME + "_chain_top";

  private static class FakeHasRuntimeDepsRule extends FakeBuildRule implements HasRuntimeDeps {
    private final ImmutableSortedSet<BuildRule> runtimeDeps;

    public FakeHasRuntimeDepsRule(
        BuildTarget target, ProjectFilesystem filesystem, BuildRule... runtimeDeps) {
      super(target, filesystem);
      this.runtimeDeps = ImmutableSortedSet.copyOf(runtimeDeps);
    }

    @Override
    public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
      return runtimeDeps.stream().map(BuildRule::getBuildTarget);
    }
  }

  @Test
  public void testEmptyQueue() {
    BuildTargetsQueue queue = BuildTargetsQueue.newEmptyQueue();
    List<WorkUnit> zeroDepTargets =
        queue.dequeueZeroDependencyNodes(ImmutableList.of(), MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepTargets.size());
  }

  private List<WorkUnit> dequeueNoFinishedTargets(BuildTargetsQueue queue) {
    return queue.dequeueZeroDependencyNodes(ImmutableList.of(), MAX_UNITS_OF_WORK);
  }

  @Test
  public void testResolverWithoutAnyTargets() {
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTargetsQueue queue = BuildTargetsQueue.newQueue(resolver, ImmutableList.of());
    List<WorkUnit> zeroDepTargets = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(0, zeroDepTargets.size());
  }

  @Test
  public void testResolverWithOnSingleTarget() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = createSimpleResolver();
    BuildTarget target = BuildTargetFactory.newInstance(TARGET_NAME);
    BuildTargetsQueue queue = BuildTargetsQueue.newQueue(resolver, ImmutableList.of(target));
    List<WorkUnit> zeroDepTargets = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepTargets.size());
    Assert.assertEquals(1, zeroDepTargets.get(0).getBuildTargets().size());
    Assert.assertEquals("//foo:one", zeroDepTargets.get(0).getBuildTargets().get(0));

    Assert.assertEquals(0, dequeueNoFinishedTargets(queue).size());
    Assert.assertEquals(
        0,
        queue
            .dequeueZeroDependencyNodes(
                ImmutableList.of(target.getFullyQualifiedName()), MAX_UNITS_OF_WORK)
            .size());
  }

  @Test
  public void testResolverWithTargetThatHasRuntimeDep()
      throws NoSuchBuildTargetException, InterruptedException {
    BuildRuleResolver resolver = createRuntimeDepsResolver();
    BuildTarget target = BuildTargetFactory.newInstance(HAS_RUNTIME_DEP_RULE);
    BuildTargetsQueue queue = BuildTargetsQueue.newQueue(resolver, ImmutableList.of(target));
    List<WorkUnit> zeroDepTargets = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepTargets.size());
    Assert.assertEquals(2, zeroDepTargets.get(0).getBuildTargets().size());

    // has_runtime_dep -> transitive_dep both form a chain, so should be returned as a work unit.
    Assert.assertEquals(TRANSITIVE_DEP_RULE, zeroDepTargets.get(0).getBuildTargets().get(0));
    Assert.assertEquals(HAS_RUNTIME_DEP_RULE, zeroDepTargets.get(0).getBuildTargets().get(1));

    List<WorkUnit> newZeroDepNodes =
        queue.dequeueZeroDependencyNodes(ImmutableList.of(TRANSITIVE_DEP_RULE), MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, newZeroDepNodes.size());

    List<WorkUnit> newZeroDepNodesTwo =
        queue.dequeueZeroDependencyNodes(ImmutableList.of(HAS_RUNTIME_DEP_RULE), MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, newZeroDepNodesTwo.size());
  }

  @Test
  public void testResolverWithDiamondDependencyTarget() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = createDiamondDependencyResolver();
    BuildTarget target = BuildTargetFactory.newInstance(TARGET_NAME);
    BuildTargetsQueue queue = BuildTargetsQueue.newQueue(resolver, ImmutableList.of(target));

    List<WorkUnit> zeroDepWorkUnits = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepWorkUnits.size());
    WorkUnit leafNodeWorkUnit = zeroDepWorkUnits.get(0);
    List<String> leafNodeTargets = leafNodeWorkUnit.getBuildTargets();
    Assert.assertEquals(1, leafNodeTargets.size());
    Assert.assertEquals(TARGET_NAME + "_leaf", leafNodeTargets.get(0));

    zeroDepWorkUnits = queue.dequeueZeroDependencyNodes(leafNodeTargets, MAX_UNITS_OF_WORK);
    Assert.assertEquals(2, zeroDepWorkUnits.size());

    WorkUnit leftNodeWorkUnit = new WorkUnit();
    leftNodeWorkUnit.setBuildTargets(ImmutableList.of(LEFT_TARGET));

    WorkUnit rightNodeWorkUnit = new WorkUnit();
    rightNodeWorkUnit.setBuildTargets(ImmutableList.of(RIGHT_TARGET));

    Assert.assertTrue(zeroDepWorkUnits.contains(leftNodeWorkUnit));
    Assert.assertTrue(zeroDepWorkUnits.contains(rightNodeWorkUnit));

    List<String> middleNodeTargets = ImmutableList.of(LEFT_TARGET, RIGHT_TARGET);

    zeroDepWorkUnits = queue.dequeueZeroDependencyNodes(middleNodeTargets, MAX_UNITS_OF_WORK);

    Assert.assertEquals(1, zeroDepWorkUnits.size());
    WorkUnit rootWorkUnit = zeroDepWorkUnits.get(0);
    Assert.assertEquals(1, rootWorkUnit.getBuildTargets().size());
    Assert.assertEquals(TARGET_NAME, rootWorkUnit.getBuildTargets().get(0));

    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(rootWorkUnit.getBuildTargets(), MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepWorkUnits.size());
  }

  @Test
  public void testDiamondDependencyResolverWithChainFromLeaf() throws NoSuchBuildTargetException {
    // Graph structure:
    //        / right \
    // root -          - chain top - chain bottom
    //        \ left  /

    BuildRuleResolver resolver = createDiamondDependencyResolverWithChainFromLeaf();
    BuildTarget target = BuildTargetFactory.newInstance(TARGET_NAME);
    BuildTargetsQueue queue = BuildTargetsQueue.newQueue(resolver, ImmutableList.of(target));

    List<WorkUnit> zeroDepWorkUnits = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepWorkUnits.size());
    WorkUnit chainWorkUnit = zeroDepWorkUnits.get(0);
    List<String> chainTargets = chainWorkUnit.getBuildTargets();
    Assert.assertEquals(2, chainTargets.size());
    Assert.assertEquals(LEAF_TARGET, chainTargets.get(0));
    Assert.assertEquals(CHAIN_TOP_TARGET, chainTargets.get(1));

    zeroDepWorkUnits = queue.dequeueZeroDependencyNodes(chainTargets, MAX_UNITS_OF_WORK);

    WorkUnit leftNodeWorkUnit = new WorkUnit();
    leftNodeWorkUnit.setBuildTargets(ImmutableList.of(LEFT_TARGET));

    WorkUnit rightNodeWorkUnit = new WorkUnit();
    rightNodeWorkUnit.setBuildTargets(ImmutableList.of(RIGHT_TARGET));

    Assert.assertTrue(zeroDepWorkUnits.contains(leftNodeWorkUnit));
    Assert.assertTrue(zeroDepWorkUnits.contains(rightNodeWorkUnit));

    // Signal the middle nodes separately. The root should only become available once both
    // left and right middle nodes have been signalled.
    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(ImmutableList.of(LEFT_TARGET), MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepWorkUnits.size());

    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(ImmutableList.of(RIGHT_TARGET), MAX_UNITS_OF_WORK);

    Assert.assertEquals(1, zeroDepWorkUnits.size());
    WorkUnit rootWorkUnit = zeroDepWorkUnits.get(0);
    Assert.assertEquals(1, rootWorkUnit.getBuildTargets().size());
    Assert.assertEquals(TARGET_NAME, rootWorkUnit.getBuildTargets().get(0));

    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(rootWorkUnit.getBuildTargets(), MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepWorkUnits.size());
  }

  private static BuildRuleResolver createSimpleResolver() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ImmutableSortedSet<BuildRule> buildRules =
        ImmutableSortedSet.of(
            JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance(TARGET_NAME))
                .build(resolver),
            JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//foo:two"))
                .build(resolver));
    buildRules.forEach(resolver::addToIndex);
    return resolver;
  }

  private BuildRuleResolver createRuntimeDepsResolver()
      throws NoSuchBuildTargetException, InterruptedException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    // Create a regular build rule
    BuildTarget buildTarget = BuildTargetFactory.newInstance(TRANSITIVE_DEP_RULE);
    BuildRuleParams ruleParams = TestBuildRuleParams.create();
    FakeBuildRule transitiveRuntimeDep = new FakeBuildRule(buildTarget, filesystem, ruleParams);
    resolver.addToIndex(transitiveRuntimeDep);

    // Create a build rule with runtime deps
    FakeBuildRule runtimeDepRule =
        new FakeHasRuntimeDepsRule(
            BuildTargetFactory.newInstance(HAS_RUNTIME_DEP_RULE), filesystem, transitiveRuntimeDep);
    resolver.addToIndex(runtimeDepRule);

    return resolver;
  }

  // Graph structure:
  //        / right \
  // root -          - leaf
  //        \ left  /
  public static BuildRuleResolver createDiamondDependencyResolver()
      throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    BuildTarget root = BuildTargetFactory.newInstance(TARGET_NAME);
    BuildTarget left = BuildTargetFactory.newInstance(LEFT_TARGET);
    BuildTarget right = BuildTargetFactory.newInstance(RIGHT_TARGET);
    BuildTarget leaf = BuildTargetFactory.newInstance(LEAF_TARGET);

    ImmutableSortedSet<BuildRule> buildRules =
        ImmutableSortedSet.of(
            JavaLibraryBuilder.createBuilder(leaf).build(resolver),
            JavaLibraryBuilder.createBuilder(left).addDep(leaf).build(resolver),
            JavaLibraryBuilder.createBuilder(right).addDep(leaf).build(resolver),
            JavaLibraryBuilder.createBuilder(root).addDep(left).addDep(right).build(resolver));
    buildRules.forEach(resolver::addToIndex);
    return resolver;
  }

  public static BuildTargetsQueue createDiamondDependencyQueue() throws NoSuchBuildTargetException {
    return BuildTargetsQueue.newQueue(
        createDiamondDependencyResolver(),
        ImmutableList.of(BuildTargetFactory.newInstance(TARGET_NAME)));
  }

  private static BuildRuleResolver createDiamondDependencyResolverWithChainFromLeaf()
      throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    BuildTarget root = BuildTargetFactory.newInstance(TARGET_NAME);
    BuildTarget left = BuildTargetFactory.newInstance(LEFT_TARGET);
    BuildTarget right = BuildTargetFactory.newInstance(RIGHT_TARGET);
    BuildTarget chainTop = BuildTargetFactory.newInstance(CHAIN_TOP_TARGET);
    BuildTarget leaf = BuildTargetFactory.newInstance(LEAF_TARGET);

    ImmutableSortedSet<BuildRule> buildRules =
        ImmutableSortedSet.of(
            JavaLibraryBuilder.createBuilder(leaf).build(resolver),
            JavaLibraryBuilder.createBuilder(chainTop).addDep(leaf).build(resolver),
            JavaLibraryBuilder.createBuilder(left).addDep(chainTop).build(resolver),
            JavaLibraryBuilder.createBuilder(right).addDep(chainTop).build(resolver),
            JavaLibraryBuilder.createBuilder(root).addDep(left).addDep(right).build(resolver));
    buildRules.forEach(resolver::addToIndex);
    return resolver;
  }
}
