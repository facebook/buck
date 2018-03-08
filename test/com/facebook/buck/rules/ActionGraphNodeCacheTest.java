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
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableSortedSet;
import java.util.SortedSet;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

public class ActionGraphNodeCacheTest {
  private ActionGraphNodeCache cache;
  private TargetGraph targetGraph;
  private BuildRuleResolver ruleResolver;

  @Before
  public void setUp() {
    cache = new ActionGraphNodeCache(100);
  }

  @Test
  public void ruleMarkedCacheableCached() {
    TargetNode<?, ?> node = createTargetNode("test1");
    setUpTargetGraphAndResolver(node);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(node);
    cache.finishTargetGraphWalk();

    assertTrue(cache.containsKey(node));
    assertTrue(ruleResolver.getRuleOptional(node.getBuildTarget()).isPresent());
  }

  @Test
  public void ruleNotMarkedCacheableNotCached() {
    TargetNode<?, ?> node = createUncacheableTargetNode("test1");
    setUpTargetGraphAndResolver(node);

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(node);
    cache.finishTargetGraphWalk();

    assertFalse(cache.containsKey(node));
    assertTrue(ruleResolver.getRuleOptional(node.getBuildTarget()).isPresent());
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
    BuildRuleResolver originalRuleResolver = ruleResolver;

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(originalChildNode1);
    cache.requireRule(originalChildNode2);
    cache.requireRule(originalParentNode);
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
    assertNotSame(
        originalRuleResolver.getRule(originalParentNode.getBuildTarget()),
        ruleResolver.getRule(newParentNode.getBuildTarget()));

    assertTrue(ruleResolver.getRuleOptional(newChildNode1.getBuildTarget()).isPresent());
    assertNotSame(
        originalRuleResolver.getRule(originalChildNode1.getBuildTarget()),
        ruleResolver.getRule(newChildNode1.getBuildTarget()));

    assertTrue(ruleResolver.getRuleOptional(newChildNode2.getBuildTarget()).isPresent());
    assertSame(
        originalRuleResolver.getRule(originalChildNode2.getBuildTarget()),
        ruleResolver.getRule(newChildNode2.getBuildTarget()));
  }

  @Test
  public void uncacheableNodeInvalidatesParentChain() {
    TargetNode<?, ?> originalChildNode1 = createUncacheableTargetNode("child1");
    TargetNode<?, ?> originalChildNode2 = createTargetNode("child2");
    TargetNode<?, ?> originalParentNode =
        createTargetNode("parent", originalChildNode1, originalChildNode2);
    setUpTargetGraphAndResolver(originalParentNode, originalChildNode1, originalChildNode2);
    BuildRuleResolver originalRuleResolver = ruleResolver;

    cache.prepareForTargetGraphWalk(targetGraph, ruleResolver);
    cache.requireRule(originalChildNode1);
    cache.requireRule(originalChildNode2);
    cache.requireRule(originalParentNode);
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
    assertNotSame(
        originalRuleResolver.getRule(originalParentNode.getBuildTarget()),
        ruleResolver.getRule(newParentNode.getBuildTarget()));

    assertTrue(ruleResolver.getRuleOptional(newChildNode1.getBuildTarget()).isPresent());
    assertNotSame(
        originalRuleResolver.getRule(originalChildNode1.getBuildTarget()),
        ruleResolver.getRule(newChildNode1.getBuildTarget()));

    assertTrue(ruleResolver.getRuleOptional(newChildNode2.getBuildTarget()).isPresent());
    assertSame(
        originalRuleResolver.getRule(originalChildNode2.getBuildTarget()),
        ruleResolver.getRule(newChildNode2.getBuildTarget()));
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
            description, BuildTargetFactory.newInstance("//foo:" + name));

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
        new ToolchainProviderBuilder().build(),
        null);
  }
}
