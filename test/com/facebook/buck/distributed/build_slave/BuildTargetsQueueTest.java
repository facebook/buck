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

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.ArtifactInfo;
import com.facebook.buck.artifact_cache.DummyArtifactCache;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.ParallelRuleKeyCalculator;
import com.facebook.buck.rules.RuleDepsCache;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SingleThreadedBuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.rules.keys.FakeRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyConfiguration;
import com.facebook.buck.rules.keys.RuleKeyFactory;
import com.facebook.buck.testutil.DummyFileHashCache;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class BuildTargetsQueueTest {

  private final int MAX_UNITS_OF_WORK = 10;
  public static final String TRANSITIVE_DEP_RULE = "//:transitive_dep";
  public static final String HAS_RUNTIME_DEP_RULE = "//:runtime_dep";
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  public static final String ROOT_TARGET = "//foo:one";
  public static final String LEAF_TARGET = ROOT_TARGET + "_leaf";
  public static final String RIGHT_TARGET = ROOT_TARGET + "_right";
  public static final String LEFT_TARGET = ROOT_TARGET + "_left";
  public static final String CHAIN_TOP_TARGET = ROOT_TARGET + "_chain_top";
  public static final RuleKey CACHE_HIT_RULE_KEY = new RuleKey("cafebabe");
  public static final RuleKey CACHE_MISS_RULE_KEY = new RuleKey("deadbeef");

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

  private static BuildTargetsQueue createQueueWithoutRemoteCache(
      BuildRuleResolver resolver, Iterable<BuildTarget> topLevelTargets) {
    return new BuildTargetsQueueFactory(
            resolver,
            MoreExecutors.newDirectExecutorService(),
            false,
            new NoopArtifactCache(),
            new DefaultBuckEventBus(FakeClock.DO_NOT_CARE, new BuildId()),
            new DummyFileHashCache(),
            RuleKeyConfiguration.builder()
                .setCoreKey("dummy")
                .setSeed(0)
                .setBuildInputRuleKeyFileSizeLimit(100)
                .build(),
            Optional.empty())
        .newQueue(topLevelTargets);
  }

  private static BuildTargetsQueue createQueueWithRemoteCacheHits(
      BuildRuleResolver resolver,
      Iterable<BuildTarget> topLevelTargets,
      List<BuildTarget> cacheHitTargets) {

    ArtifactCache remoteCache = new DummyArtifactCache();
    remoteCache.store(
        ArtifactInfo.builder().setRuleKeys(ImmutableList.of(CACHE_HIT_RULE_KEY)).build(), null);

    RuleKeyFactory<RuleKey> ruleKeyFactory =
        new FakeRuleKeyFactory(
            Maps.toMap(
                MoreIterables.dedupKeepLast(resolver.getBuildRules())
                    .stream()
                    .map(rule -> rule.getBuildTarget())
                    .collect(ImmutableSet.toImmutableSet()),
                target ->
                    cacheHitTargets.contains(target) ? CACHE_HIT_RULE_KEY : CACHE_MISS_RULE_KEY));

    ParallelRuleKeyCalculator<RuleKey> ruleKeyCalculator =
        new ParallelRuleKeyCalculator<>(
            MoreExecutors.newDirectExecutorService(),
            ruleKeyFactory,
            new RuleDepsCache(resolver),
            (eventBus, rule) -> () -> {});

    return new BuildTargetsQueueFactory(
            resolver,
            MoreExecutors.newDirectExecutorService(),
            false,
            remoteCache,
            new DefaultBuckEventBus(FakeClock.DO_NOT_CARE, new BuildId()),
            new DummyFileHashCache(),
            RuleKeyConfiguration.builder()
                .setCoreKey("dummy")
                .setSeed(0)
                .setBuildInputRuleKeyFileSizeLimit(100)
                .build(),
            Optional.of(ruleKeyCalculator))
        .newQueue(topLevelTargets);
  }

  @Test
  public void testEmptyQueue() {
    BuildTargetsQueue queue = BuildTargetsQueueFactory.newEmptyQueue();
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
    BuildTargetsQueue queue = createQueueWithoutRemoteCache(resolver, ImmutableList.of());
    List<WorkUnit> zeroDepTargets = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(0, zeroDepTargets.size());
  }

  @Test
  public void testResolverWithOnSingleTarget() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = createSimpleResolver();
    BuildTarget target = BuildTargetFactory.newInstance(ROOT_TARGET);
    BuildTargetsQueue queue = createQueueWithoutRemoteCache(resolver, ImmutableList.of(target));
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
    BuildTargetsQueue queue = createQueueWithoutRemoteCache(resolver, ImmutableList.of(target));
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
    BuildTarget target = BuildTargetFactory.newInstance(ROOT_TARGET);
    BuildTargetsQueue queue = createQueueWithoutRemoteCache(resolver, ImmutableList.of(target));

    List<WorkUnit> zeroDepWorkUnits = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepWorkUnits.size());
    WorkUnit leafNodeWorkUnit = zeroDepWorkUnits.get(0);
    List<String> leafNodeTargets = leafNodeWorkUnit.getBuildTargets();
    Assert.assertEquals(1, leafNodeTargets.size());
    Assert.assertEquals(LEAF_TARGET, leafNodeTargets.get(0));

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
    Assert.assertEquals(ROOT_TARGET, rootWorkUnit.getBuildTargets().get(0));

    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(rootWorkUnit.getBuildTargets(), MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepWorkUnits.size());
  }

  @Test
  public void testDiamondDependencyResolverWithChainFromLeaf() throws NoSuchBuildTargetException {
    // Graph structure:
    //        / right \
    // root -          - chain top - leaf
    //        \ left  /

    BuildRuleResolver resolver = createDiamondDependencyResolverWithChainFromLeaf();
    BuildTarget target = BuildTargetFactory.newInstance(ROOT_TARGET);
    BuildTargetsQueue queue = createQueueWithoutRemoteCache(resolver, ImmutableList.of(target));

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
    Assert.assertEquals(ROOT_TARGET, rootWorkUnit.getBuildTargets().get(0));

    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(rootWorkUnit.getBuildTargets(), MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepWorkUnits.size());
  }

  @Test
  public void testDiamondDependencyGraphWithRemoteCacheHits() throws NoSuchBuildTargetException {
    // Graph structure:
    //               / right (hit) \
    // root (miss) -                 - chain top (miss) - chain bottom (hit)
    //              \ left (miss) /

    BuildRuleResolver resolver = createDiamondDependencyResolverWithChainFromLeaf();
    BuildTarget rootTarget = BuildTargetFactory.newInstance(ROOT_TARGET);
    BuildTarget rightTarget = BuildTargetFactory.newInstance(RIGHT_TARGET);
    BuildTarget leafTarget = BuildTargetFactory.newInstance(LEAF_TARGET);

    BuildTargetsQueue queue =
        createQueueWithRemoteCacheHits(
            resolver, ImmutableList.of(rootTarget), ImmutableList.of(rightTarget, leafTarget));

    List<WorkUnit> zeroDepWorkUnits = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepWorkUnits.size());
    WorkUnit workUnit = zeroDepWorkUnits.get(0);
    List<String> targets = workUnit.getBuildTargets();
    Assert.assertEquals(3, targets.size());
    Assert.assertEquals(CHAIN_TOP_TARGET, targets.get(0));
    Assert.assertEquals(LEFT_TARGET, targets.get(1));
    Assert.assertEquals(ROOT_TARGET, targets.get(2));

    zeroDepWorkUnits = queue.dequeueZeroDependencyNodes(targets, MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepWorkUnits.size());
  }

  @Test
  public void testGraphWithTopLevelCacheHit() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = createSimpleResolver();
    BuildTarget target = BuildTargetFactory.newInstance(ROOT_TARGET);
    BuildTargetsQueue queue =
        createQueueWithRemoteCacheHits(
            resolver, ImmutableList.of(target), ImmutableList.of(target));
    List<WorkUnit> zeroDepTargets = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(0, zeroDepTargets.size());
  }

  @Test
  public void testDeepBuildDoesNotCalculateRuleKeysOrUseRemoteCache()
      throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = createDiamondDependencyResolverWithChainFromLeaf();
    BuildTarget rootTarget = BuildTargetFactory.newInstance(ROOT_TARGET);

    ParallelRuleKeyCalculator<RuleKey> rkCalculator =
        EasyMock.createMock(ParallelRuleKeyCalculator.class);
    EasyMock.expect(rkCalculator.getAllKnownTargets()).andReturn(ImmutableSet.of()).anyTimes();
    ArtifactCache artifactCache = EasyMock.createMock(ArtifactCache.class);

    BuildTargetsQueueFactory factory =
        new BuildTargetsQueueFactory(
            resolver,
            MoreExecutors.newDirectExecutorService(),
            true,
            artifactCache,
            new DefaultBuckEventBus(FakeClock.DO_NOT_CARE, new BuildId()),
            new DummyFileHashCache(),
            RuleKeyConfiguration.builder()
                .setCoreKey("dummy")
                .setSeed(0)
                .setBuildInputRuleKeyFileSizeLimit(100)
                .build(),
            Optional.of(rkCalculator));

    EasyMock.replay(artifactCache);
    EasyMock.replay(rkCalculator);

    factory.newQueue(ImmutableList.of(rootTarget));

    EasyMock.verify(artifactCache);
    EasyMock.verify(rkCalculator);
  }

  private static BuildRuleResolver createSimpleResolver() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ImmutableSortedSet<BuildRule> buildRules =
        ImmutableSortedSet.of(
            JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance(ROOT_TARGET))
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

    BuildTarget root = BuildTargetFactory.newInstance(ROOT_TARGET);
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
    return createQueueWithoutRemoteCache(
        createDiamondDependencyResolver(),
        ImmutableList.of(BuildTargetFactory.newInstance(ROOT_TARGET)));
  }

  // Graph structure:
  //        / right \
  // root -          - chain top - leaf
  //        \ left  /
  private static BuildRuleResolver createDiamondDependencyResolverWithChainFromLeaf()
      throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    BuildTarget root = BuildTargetFactory.newInstance(ROOT_TARGET);
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

  public static BuildTargetsQueue createDiamondDependencyQueueWithChainFromLeaf()
      throws NoSuchBuildTargetException {
    return createQueueWithoutRemoteCache(
        createDiamondDependencyResolverWithChainFromLeaf(),
        ImmutableList.of(BuildTargetFactory.newInstance(ROOT_TARGET)));
  }
}
