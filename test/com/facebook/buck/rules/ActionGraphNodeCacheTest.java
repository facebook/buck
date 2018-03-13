/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeTargetNodeBuilder.FakeDescription;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableSortedSet;
import java.util.SortedSet;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ActionGraphNodeCacheTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private ActionGraphNodeCache cache;
  private TargetGraph targetGraph;
  private BuildRuleResolver ruleResolver;

  @Before
  public void setUp() {
    cache = new ActionGraphNodeCache(100);
  }

  @Test
  public void cacheableRuleCached() {
    TargetNode<?, ?> node = createTargetNode("test1");
    setUpTargetGraphAndResolver(node);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(node);
    cache.finishTargetGraphWalk();

    assertTrue(cache.containsKey(node));
    assertTrue(ruleResolver.getRuleOptional(node.getBuildTarget()).isPresent());
  }

  @Test
  public void uncacheableRuleNotCached() {
    TargetNode<?, ?> node = createUncacheableTargetNode("test1");
    setUpTargetGraphAndResolver(node);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(node);
    cache.finishTargetGraphWalk();

    assertFalse(cache.containsKey(node));
    assertTrue(ruleResolver.getRuleOptional(node.getBuildTarget()).isPresent());
  }

  @Test
  public void cacheableRuleWithUncacheableChildNotCached() {
    TargetNode<?, ?> childNode = createUncacheableTargetNode("child");
    TargetNode<?, ?> parentNode = createTargetNode("parent", childNode);
    setUpTargetGraphAndResolver(parentNode, childNode);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(childNode);
    cache.requireRule(parentNode);
    cache.finishTargetGraphWalk();

    assertFalse(cache.containsKey(childNode));
    assertTrue(ruleResolver.getRuleOptional(childNode.getBuildTarget()).isPresent());
    assertFalse(cache.containsKey(parentNode));
    assertTrue(ruleResolver.getRuleOptional(parentNode.getBuildTarget()).isPresent());
  }

  @Test
  public void cachedNodesLruEvicted() {
    cache = new ActionGraphNodeCache(2);

    TargetNode<?, ?> node1 = createTargetNode("test1");
    TargetNode<?, ?> node2 = createTargetNode("test2");
    TargetNode<?, ?> node3 = createTargetNode("test3");
    setUpTargetGraphAndResolver(node1, node2, node3);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(node1);
    cache.requireRule(node2);
    cache.requireRule(node3);
    cache.finishTargetGraphWalk();

    assertFalse(cache.containsKey(node1));
    assertTrue(cache.containsKey(node2));
    assertTrue(cache.containsKey(node3));
  }

  @Test
  public void buildRuleForUnchangedTargetLoadedFromCache() {
    TargetNode<?, ?> originalNode = createTargetNode("test1");
    setUpTargetGraphAndResolver(originalNode);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    BuildRule originalBuildRule = cache.requireRule(originalNode);
    cache.finishTargetGraphWalk();

    TargetNode<?, ?> newNode = createTargetNode("test1");
    assertEquals(originalNode, newNode);
    setUpTargetGraphAndResolver(newNode);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    BuildRule newBuildRule = cache.requireRule(newNode);
    cache.finishTargetGraphWalk();

    assertSame(originalBuildRule, newBuildRule);
    assertTrue(ruleResolver.getRuleOptional(newNode.getBuildTarget()).isPresent());
    assertSame(originalBuildRule, ruleResolver.getRule(newNode.getBuildTarget()));
  }

  @Test
  public void buildRuleForChangedTargetNotLoadedFromCache() {
    TargetNode<?, ?> originalNode = createTargetNode("test1");
    setUpTargetGraphAndResolver(originalNode);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    BuildRule originalBuildRule = cache.requireRule(originalNode);
    cache.finishTargetGraphWalk();

    TargetNode<?, ?> depNode = createTargetNode("test2");
    TargetNode<?, ?> newNode = createTargetNode("test1", depNode);
    setUpTargetGraphAndResolver(newNode, depNode);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    BuildRule newBuildRule = cache.requireRule(newNode);
    cache.finishTargetGraphWalk();

    assertNotSame(originalBuildRule, newBuildRule);
    assertTrue(ruleResolver.getRuleOptional(newNode.getBuildTarget()).isPresent());
    assertNotSame(originalBuildRule, ruleResolver.getRule(newNode.getBuildTarget()));
  }

  @Test
  public void allParentChainsForChangedTargetInvalidated() {
    TargetNode<?, ?> originalChildNode = createTargetNode("child");
    TargetNode<?, ?> originalParentNode1 = createTargetNode("parent1", originalChildNode);
    TargetNode<?, ?> originalParentNode2 = createTargetNode("parent2", originalChildNode);
    setUpTargetGraphAndResolver(originalParentNode1, originalParentNode2, originalChildNode);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(originalChildNode);
    BuildRule originalParentBuildRule1 = cache.requireRule(originalParentNode1);
    BuildRule originalParentBuildRule2 = cache.requireRule(originalParentNode2);
    cache.finishTargetGraphWalk();

    TargetNode<?, ?> newChildNode = createTargetNode("child", "new_label");
    TargetNode<?, ?> newParentNode1 = createTargetNode("parent1", newChildNode);
    TargetNode<?, ?> newParentNode2 = createTargetNode("parent2", newChildNode);
    setUpTargetGraphAndResolver(newParentNode1, newParentNode2, newChildNode);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(newChildNode);
    cache.requireRule(newParentNode1);
    cache.requireRule(newParentNode2);
    cache.finishTargetGraphWalk();

    assertTrue(ruleResolver.getRuleOptional(newParentNode1.getBuildTarget()).isPresent());
    assertNotSame(originalParentBuildRule1, ruleResolver.getRule(newParentNode1.getBuildTarget()));
    assertTrue(ruleResolver.getRuleOptional(newParentNode2.getBuildTarget()).isPresent());
    assertNotSame(originalParentBuildRule2, ruleResolver.getRule(newParentNode2.getBuildTarget()));
  }

  @Test
  public void buildRuleSubtreeForCachedTargetAddedToResolver() {
    FakeCacheableBuildRule buildRuleDep1 = new FakeCacheableBuildRule("test1#flav1");
    FakeCacheableBuildRule buildRuleDep2 = new FakeCacheableBuildRule("test1#flav2");
    FakeCacheableBuildRule buildRule =
        new FakeCacheableBuildRule("test1", buildRuleDep1, buildRuleDep2);
    TargetNode<?, ?> node = createTargetNode(buildRule);
    setUpTargetGraphAndResolver(node);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(node);
    cache.finishTargetGraphWalk();

    BuildRuleResolver newRuleResolver = createBuildRuleResolver(targetGraph);
    cache.prepareForTargetGraphWalk(targetGraph, newRuleResolver);
    cache.requireRule(node);
    cache.finishTargetGraphWalk();

    assertTrue(newRuleResolver.getRuleOptional(buildRule.getBuildTarget()).isPresent());
    assertTrue(newRuleResolver.getRuleOptional(buildRuleDep1.getBuildTarget()).isPresent());
    assertTrue(newRuleResolver.getRuleOptional(buildRuleDep2.getBuildTarget()).isPresent());
  }

  @Test
  public void changedCacheableNodeInvalidatesParentChain() {
    TargetNode<?, ?> originalChildNode1 = createTargetNode("child1");
    TargetNode<?, ?> originalChildNode2 = createTargetNode("child2");
    TargetNode<?, ?> originalParentNode =
        createTargetNode("parent", originalChildNode1, originalChildNode2);
    setUpTargetGraphAndResolver(originalParentNode, originalChildNode1, originalChildNode2);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    BuildRule originalChildRule1 = cache.requireRule(originalChildNode1);
    BuildRule originalChildRule2 = cache.requireRule(originalChildNode2);
    BuildRule originalParentRule = cache.requireRule(originalParentNode);
    cache.finishTargetGraphWalk();

    TargetNode<?, ?> newChildNode1 = createTargetNode("child1", "new_label");
    TargetNode<?, ?> newChildNode2 = createTargetNode("child2");
    TargetNode<?, ?> newParentNode = createTargetNode("parent", newChildNode1, newChildNode2);
    setUpTargetGraphAndResolver(newParentNode, newChildNode1, newChildNode2);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(newChildNode1);
    cache.requireRule(newChildNode2);
    cache.requireRule(newParentNode);
    cache.finishTargetGraphWalk();

    assertTrue(ruleResolver.getRuleOptional(newParentNode.getBuildTarget()).isPresent());
    assertNotSame(originalParentRule, ruleResolver.getRule(newParentNode.getBuildTarget()));

    assertTrue(ruleResolver.getRuleOptional(newChildNode1.getBuildTarget()).isPresent());
    assertNotSame(originalChildRule1, ruleResolver.getRule(newChildNode1.getBuildTarget()));

    assertTrue(ruleResolver.getRuleOptional(newChildNode2.getBuildTarget()).isPresent());
    assertSame(originalChildRule2, ruleResolver.getRule(newChildNode2.getBuildTarget()));
  }

  @Test
  public void uncacheableNodeInvalidatesParentChain() {
    TargetNode<?, ?> originalChildNode1 = createUncacheableTargetNode("child1");
    TargetNode<?, ?> originalChildNode2 = createTargetNode("child2");
    TargetNode<?, ?> originalParentNode =
        createTargetNode("parent", originalChildNode1, originalChildNode2);
    setUpTargetGraphAndResolver(originalParentNode, originalChildNode1, originalChildNode2);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    BuildRule originalChildRule1 = cache.requireRule(originalChildNode1);
    BuildRule originalChildRule2 = cache.requireRule(originalChildNode2);
    BuildRule originalParentRule = cache.requireRule(originalParentNode);
    cache.finishTargetGraphWalk();

    TargetNode<?, ?> newChildNode1 = createUncacheableTargetNode("child1");
    TargetNode<?, ?> newChildNode2 = createTargetNode("child2");
    TargetNode<?, ?> newParentNode = createTargetNode("parent", newChildNode1, newChildNode2);
    setUpTargetGraphAndResolver(newParentNode, newChildNode1, newChildNode2);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(newChildNode1);
    cache.requireRule(newChildNode2);
    cache.requireRule(newParentNode);
    cache.finishTargetGraphWalk();

    assertTrue(ruleResolver.getRuleOptional(newParentNode.getBuildTarget()).isPresent());
    assertNotSame(originalParentRule, ruleResolver.getRule(newParentNode.getBuildTarget()));

    assertTrue(ruleResolver.getRuleOptional(newChildNode1.getBuildTarget()).isPresent());
    assertNotSame(originalChildRule1, ruleResolver.getRule(newChildNode1.getBuildTarget()));

    assertTrue(ruleResolver.getRuleOptional(newChildNode2.getBuildTarget()).isPresent());
    assertSame(originalChildRule2, ruleResolver.getRule(newChildNode2.getBuildTarget()));
  }

  @Test
  public void cachedParentInvalidatedIfPreviouslyCachedChildPushedOutOfCache() {
    cache = new ActionGraphNodeCache(2);

    TargetNode<?, ?> childNode1 = createTargetNode("child1");
    TargetNode<?, ?> childNode2 = createTargetNode("child2");
    TargetNode<?, ?> parentNode = createTargetNode("parent", childNode1, childNode2);
    setUpTargetGraphAndResolver(parentNode, childNode1, childNode2);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(childNode1);
    cache.requireRule(childNode2);
    cache.requireRule(parentNode);
    cache.finishTargetGraphWalk();

    assertFalse(cache.containsKey(childNode1));
    assertTrue(cache.containsKey(childNode2));
    assertTrue(cache.containsKey(parentNode));

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.finishTargetGraphWalk();

    assertFalse(cache.containsKey(parentNode));
  }

  @Test
  public void allTargetGraphDepTypesAddedToIndexForCachedNode() {
    TargetNode<?, ?> declaredChildNode = createTargetNodeBuilder("declared").build();
    TargetNode<?, ?> extraChildNode = createTargetNodeBuilder("extra").build();
    TargetNode<?, ?> targetGraphOnlyChildNode =
        createTargetNodeBuilder("target_graph_only").build();
    TargetNode<?, ?> parentNode =
        createTargetNodeBuilder("parent")
            .setDeps(declaredChildNode)
            .setExtraDeps(extraChildNode)
            .setTargetGraphOnlyDeps(targetGraphOnlyChildNode)
            .build();
    setUpTargetGraphAndResolver(
        parentNode, declaredChildNode, extraChildNode, targetGraphOnlyChildNode);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(declaredChildNode);
    cache.requireRule(extraChildNode);
    cache.requireRule(targetGraphOnlyChildNode);
    cache.requireRule(parentNode);
    cache.finishTargetGraphWalk();

    assertTrue(cache.containsKey(parentNode));
    setUpTargetGraphAndResolver(
        parentNode, declaredChildNode, extraChildNode, targetGraphOnlyChildNode);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(parentNode);
    cache.finishTargetGraphWalk();

    assertTrue(ruleResolver.getRuleOptional(declaredChildNode.getBuildTarget()).isPresent());
    assertTrue(ruleResolver.getRuleOptional(extraChildNode.getBuildTarget()).isPresent());
    assertTrue(ruleResolver.getRuleOptional(targetGraphOnlyChildNode.getBuildTarget()).isPresent());
  }

  @Test
  public void cachedNodeUsesLastRuleResolverForRuntimeDeps() {
    FakeCacheableBuildRule childBuildRule = new FakeCacheableBuildRule("test#child");
    SortedSet<BuildTarget> runtimeDeps = ImmutableSortedSet.of(childBuildRule.getBuildTarget());
    FakeCacheableBuildRule parentBuildRule = new FakeCacheableBuildRule("test", runtimeDeps);
    TargetNode<?, ?> originalNode = createTargetNode(parentBuildRule);
    setUpTargetGraphAndResolver(originalNode);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    ruleResolver.addToIndex(childBuildRule);
    cache.requireRule(originalNode);
    cache.finishTargetGraphWalk();

    FakeCacheableBuildRule newParentBuildRule = new FakeCacheableBuildRule("test", runtimeDeps);
    TargetNode<?, ?> newNode = createTargetNode(newParentBuildRule);
    setUpTargetGraphAndResolver(newNode);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(newNode);
    cache.finishTargetGraphWalk();

    assertTrue(ruleResolver.getRuleOptional(newNode.getBuildTarget()).isPresent());
    assertSame(parentBuildRule, ruleResolver.getRule(originalNode.getBuildTarget()));

    assertTrue(ruleResolver.getRuleOptional(childBuildRule.getBuildTarget()).isPresent());
    assertSame(childBuildRule, ruleResolver.getRule(childBuildRule.getBuildTarget()));
  }

  @Test
  public void ruleResolversUpdatedForCachedNodeSubtreeLoadedFromCache() {
    FakeCacheableBuildRule buildRuleDep1 = new FakeCacheableBuildRule("test1#flav1");
    FakeCacheableBuildRule buildRuleDep2 = new FakeCacheableBuildRule("test1#flav2");
    FakeCacheableBuildRule buildRule =
        new FakeCacheableBuildRule("test1", buildRuleDep1, buildRuleDep2);
    TargetNode<?, ?> node = createTargetNode(buildRule);
    setUpTargetGraphAndResolver(node);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(node);
    cache.finishTargetGraphWalk();

    BuildRuleResolver newRuleResolver = createBuildRuleResolver(targetGraph);
    cache.prepareForTargetGraphWalk(targetGraph, newRuleResolver);
    cache.requireRule(node);
    cache.finishTargetGraphWalk();

    assertSame(newRuleResolver, buildRule.getRuleResolver());
    assertSame(newRuleResolver, buildRuleDep1.getRuleResolver());
    assertSame(newRuleResolver, buildRuleDep2.getRuleResolver());
  }

  @Test
  public void ruleResolversUpdatedForCachedNodeSubtreeNotLoadedFromCache() {
    FakeCacheableBuildRule buildRuleDep1 = new FakeCacheableBuildRule("test1#flav1");
    FakeCacheableBuildRule buildRuleDep2 = new FakeCacheableBuildRule("test1#flav2");
    FakeCacheableBuildRule buildRule =
        new FakeCacheableBuildRule("test1", buildRuleDep1, buildRuleDep2);
    TargetNode<?, ?> node1 = createTargetNode(buildRule);
    TargetNode<?, ?> node2 = createTargetNode("test2");
    setUpTargetGraphAndResolver(node1, node2);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(node1);
    cache.requireRule(node2);
    cache.finishTargetGraphWalk();

    BuildRuleResolver newRuleResolver = createBuildRuleResolver(targetGraph);
    cache.prepareForTargetGraphWalk(targetGraph, newRuleResolver);
    cache.requireRule(node2);
    cache.finishTargetGraphWalk();

    assertSame(newRuleResolver, buildRule.getRuleResolver());
    assertSame(newRuleResolver, buildRuleDep1.getRuleResolver());
    assertSame(newRuleResolver, buildRuleDep2.getRuleResolver());
  }

  @Test
  public void lastRuleResolverInvalidatedAfterTargetGraphWalk() {
    expectedException.expect(IllegalStateException.class);

    TargetNode<?, ?> node = createTargetNode("node");
    setUpTargetGraphAndResolver(node);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(node);
    cache.finishTargetGraphWalk();

    BuildRuleResolver oldRuleResolver = ruleResolver;
    setUpTargetGraphAndResolver(node);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(node);
    cache.finishTargetGraphWalk();

    oldRuleResolver.getRuleOptional(node.getBuildTarget());
  }

  @Test
  public void runtimeDepsForOldCachedNodeLoadedFromCache() {
    FakeCacheableBuildRule childBuildRule = new FakeCacheableBuildRule("test#child");
    SortedSet<BuildTarget> runtimeDeps = ImmutableSortedSet.of(childBuildRule.getBuildTarget());
    FakeCacheableBuildRule parentBuildRule = new FakeCacheableBuildRule("test", runtimeDeps);
    TargetNode<?, ?> node1 = createTargetNode(parentBuildRule);
    setUpTargetGraphAndResolver(node1);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(node1);
    ruleResolver.addToIndex(childBuildRule);
    cache.finishTargetGraphWalk();

    TargetNode<?, ?> node2 = createTargetNode("test2");
    setUpTargetGraphAndResolver(node2);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(node2);
    cache.finishTargetGraphWalk();

    setUpTargetGraphAndResolver(node1);
    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(node1);
    cache.finishTargetGraphWalk();

    assertTrue(ruleResolver.getRuleOptional(childBuildRule.getBuildTarget()).isPresent());
    assertSame(childBuildRule, ruleResolver.getRuleOptional(childBuildRule.getBuildTarget()).get());
  }

  private FakeTargetNodeBuilder createTargetNodeBuilder(String name) {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//test:" + name);
    return FakeTargetNodeBuilder.newBuilder(
        new FakeDescription() {
          @Override
          public BuildRule createBuildRule(
              BuildRuleCreationContext context,
              BuildTarget buildTarget,
              BuildRuleParams params,
              FakeTargetNodeArg args) {
            return new FakeCacheableBuildRule(buildTarget, context.getProjectFilesystem(), params);
          }
        },
        buildTarget);
  }

  private TargetNode<?, ?> createTargetNode(String name, TargetNode<?, ?>... deps) {
    return createTargetNode(name, null, deps);
  }

  private TargetNode<?, ?> createTargetNode(String name, String label, TargetNode<?, ?>... deps) {
    return createTargetNode(
        name,
        label,
        new FakeDescription() {
          @Override
          public BuildRule createBuildRule(
              BuildRuleCreationContext context,
              BuildTarget buildTarget,
              BuildRuleParams params,
              FakeTargetNodeArg args) {
            return new FakeCacheableBuildRule(buildTarget, context.getProjectFilesystem(), params);
          }
        },
        deps);
  }

  private TargetNode<?, ?> createTargetNode(
      String name, String label, FakeDescription description, TargetNode<?, ?>... deps) {
    FakeTargetNodeBuilder targetNodeBuilder =
        FakeTargetNodeBuilder.newBuilder(
            description, BuildTargetFactory.newInstance("//test:" + name));

    for (TargetNode<?, ?> dep : deps) {
      targetNodeBuilder.getArgForPopulating().addDeps(dep.getBuildTarget());
    }
    if (label != null) {
      targetNodeBuilder.getArgForPopulating().addLabels(label);
    }
    return targetNodeBuilder.build();
  }

  private TargetNode<?, ?> createTargetNode(BuildRule buildRule, TargetNode<?, ?>... deps) {
    FakeTargetNodeBuilder builder = FakeTargetNodeBuilder.newBuilder(buildRule);
    builder
        .getArgForPopulating()
        .setDeps(RichStream.from(deps).map(t -> t.getBuildTarget()).collect(Collectors.toList()));
    return builder.build();
  }

  private TargetNode<?, ?> createUncacheableTargetNode(String target) {
    return createTargetNode(
        target,
        null,
        new FakeDescription() {
          @Override
          public BuildRule createBuildRule(
              BuildRuleCreationContext context,
              BuildTarget buildTarget,
              BuildRuleParams params,
              FakeTargetNodeArg args) {
            BuildRule buildRule =
                new FakeBuildRule(buildTarget, context.getProjectFilesystem(), params);
            assertFalse(buildRule instanceof CacheableBuildRule);
            return buildRule;
          }
        });
  }

  private void setUpTargetGraphAndResolver(TargetNode<?, ?>... nodes) {
    targetGraph = TargetGraphFactory.newInstance(nodes);
    ruleResolver = createBuildRuleResolver(targetGraph);
  }

  private BuildRuleResolver createBuildRuleResolver(TargetGraph targetGraph) {
    return new SingleThreadedBuildRuleResolver(
        targetGraph,
        new DefaultTargetNodeToBuildRuleTransformer(),
        new TestCellBuilder().build().getCellProvider(),
        null);
  }
}
