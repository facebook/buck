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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.FakeTargetNodeBuilder.FakeDescription;
import com.facebook.buck.testutil.TargetGraphFactory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class IncrementalActionGraphGeneratorTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private IncrementalActionGraphGenerator generator;
  private TargetGraph targetGraph;
  private BuildRuleResolver ruleResolver;

  @Before
  public void setUp() {
    generator = new IncrementalActionGraphGenerator();
  }

  @Test
  public void cacheableRuleCached() {
    TargetNode<?, ?> node = createTargetNode("test1");
    setUpTargetGraphAndResolver(node);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    BuildRule buildRule = ruleResolver.requireRule(node.getBuildTarget());

    assertTrue(ruleResolver.getRuleOptional(node.getBuildTarget()).isPresent());

    BuildRuleResolver newRuleResolver = createBuildRuleResolver(targetGraph);
    generator.populateRuleResolverWithCachedRules(targetGraph, newRuleResolver);
    newRuleResolver.requireRule(node.getBuildTarget());

    assertTrue(newRuleResolver.getRuleOptional(node.getBuildTarget()).isPresent());
    assertSame(buildRule, newRuleResolver.getRuleOptional(node.getBuildTarget()).get());
  }

  @Test
  public void uncacheableRuleNotCached() {
    TargetNode<?, ?> node = createUncacheableTargetNode("test1");
    setUpTargetGraphAndResolver(node);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    BuildRule buildRule = ruleResolver.requireRule(node.getBuildTarget());

    assertTrue(ruleResolver.getRuleOptional(node.getBuildTarget()).isPresent());

    BuildRuleResolver newRuleResolver = createBuildRuleResolver(targetGraph);
    generator.populateRuleResolverWithCachedRules(targetGraph, newRuleResolver);
    newRuleResolver.requireRule(node.getBuildTarget());

    assertTrue(newRuleResolver.getRuleOptional(node.getBuildTarget()).isPresent());
    assertNotSame(buildRule, newRuleResolver.getRuleOptional(node.getBuildTarget()).get());
  }

  @Test
  public void cacheableRuleWithUncacheableChildNotCached() {
    TargetNode<?, ?> childNode = createUncacheableTargetNode("child");
    TargetNode<?, ?> parentNode = createTargetNode("parent", childNode);
    setUpTargetGraphAndResolver(parentNode, childNode);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    BuildRule childBuildRule = ruleResolver.requireRule(childNode.getBuildTarget());
    BuildRule parentBuildRule = ruleResolver.requireRule(parentNode.getBuildTarget());

    assertTrue(ruleResolver.getRuleOptional(childNode.getBuildTarget()).isPresent());
    assertTrue(ruleResolver.getRuleOptional(parentNode.getBuildTarget()).isPresent());

    BuildRuleResolver newRuleResolver = createBuildRuleResolver(targetGraph);
    generator.populateRuleResolverWithCachedRules(targetGraph, newRuleResolver);
    newRuleResolver.requireRule(childNode.getBuildTarget());
    newRuleResolver.requireRule(parentNode.getBuildTarget());

    assertTrue(newRuleResolver.getRuleOptional(childNode.getBuildTarget()).isPresent());
    assertNotSame(
        childBuildRule, newRuleResolver.getRuleOptional(childNode.getBuildTarget()).get());
    assertTrue(newRuleResolver.getRuleOptional(parentNode.getBuildTarget()).isPresent());
    assertNotSame(
        parentBuildRule, newRuleResolver.getRuleOptional(parentNode.getBuildTarget()).get());
  }

  @Test
  public void buildRuleForUnchangedTargetLoadedFromCache() {
    TargetNode<?, ?> originalNode = createTargetNode("test1");
    setUpTargetGraphAndResolver(originalNode);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    BuildRule originalBuildRule = ruleResolver.requireRule(originalNode.getBuildTarget());

    TargetNode<?, ?> newNode = createTargetNode("test1");
    assertEquals(originalNode, newNode);
    setUpTargetGraphAndResolver(newNode);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    BuildRule newBuildRule = ruleResolver.requireRule(newNode.getBuildTarget());

    assertSame(originalBuildRule, newBuildRule);
    assertTrue(ruleResolver.getRuleOptional(newNode.getBuildTarget()).isPresent());
    assertSame(originalBuildRule, ruleResolver.getRule(newNode.getBuildTarget()));
  }

  @Test
  public void buildRuleForChangedTargetNotLoadedFromCache() {
    TargetNode<?, ?> originalNode = createTargetNode("test1");
    setUpTargetGraphAndResolver(originalNode);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    BuildRule originalBuildRule = ruleResolver.requireRule(originalNode.getBuildTarget());

    TargetNode<?, ?> depNode = createTargetNode("test2");
    TargetNode<?, ?> newNode = createTargetNode("test1", depNode);
    setUpTargetGraphAndResolver(newNode, depNode);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    BuildRule newBuildRule = ruleResolver.requireRule(newNode.getBuildTarget());

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

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    ruleResolver.requireRule(originalChildNode.getBuildTarget());
    BuildRule originalParentBuildRule1 =
        ruleResolver.requireRule(originalParentNode1.getBuildTarget());
    BuildRule originalParentBuildRule2 =
        ruleResolver.requireRule(originalParentNode2.getBuildTarget());

    TargetNode<?, ?> newChildNode = createTargetNode("child", "new_label");
    TargetNode<?, ?> newParentNode1 = createTargetNode("parent1", newChildNode);
    TargetNode<?, ?> newParentNode2 = createTargetNode("parent2", newChildNode);
    setUpTargetGraphAndResolver(newParentNode1, newParentNode2, newChildNode);

    BuildRuleResolver newRuleResolver = createBuildRuleResolver(targetGraph);
    generator.populateRuleResolverWithCachedRules(targetGraph, newRuleResolver);
    newRuleResolver.requireRule(newChildNode.getBuildTarget());
    newRuleResolver.requireRule(newParentNode1.getBuildTarget());
    newRuleResolver.requireRule(newParentNode2.getBuildTarget());

    assertTrue(newRuleResolver.getRuleOptional(newParentNode1.getBuildTarget()).isPresent());
    assertNotSame(
        originalParentBuildRule1, newRuleResolver.getRule(newParentNode1.getBuildTarget()));
    assertTrue(newRuleResolver.getRuleOptional(newParentNode2.getBuildTarget()).isPresent());
    assertNotSame(
        originalParentBuildRule2, newRuleResolver.getRule(newParentNode2.getBuildTarget()));
  }

  @Test
  public void buildRuleSubtreeForCachedTargetAddedToResolver() {
    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:test");
    BuildTarget childTarget1 = parentTarget.withFlavors(InternalFlavor.of("flav1"));
    BuildTarget childTarget2 = parentTarget.withFlavors(InternalFlavor.of("flav2"));
    TargetNode<?, ?> node =
        FakeTargetNodeBuilder.newBuilder(
                new FakeDescription() {
                  @Override
                  public BuildRule createBuildRule(
                      BuildRuleCreationContext context,
                      BuildTarget buildTarget,
                      BuildRuleParams params,
                      FakeTargetNodeArg args) {
                    BuildRule child1 =
                        context
                            .getBuildRuleResolver()
                            .computeIfAbsent(childTarget1, target -> new FakeBuildRule(target));
                    BuildRule child2 =
                        context
                            .getBuildRuleResolver()
                            .computeIfAbsent(childTarget2, target -> new FakeBuildRule(target));
                    return new FakeBuildRule(parentTarget, child1, child2);
                  }
                },
                parentTarget)
            .build();
    setUpTargetGraphAndResolver(node);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    ruleResolver.requireRule(node.getBuildTarget());

    BuildRuleResolver newRuleResolver = createBuildRuleResolver(targetGraph);
    generator.populateRuleResolverWithCachedRules(targetGraph, newRuleResolver);
    newRuleResolver.requireRule(node.getBuildTarget());

    assertTrue(newRuleResolver.getRuleOptional(parentTarget).isPresent());
    assertTrue(newRuleResolver.getRuleOptional(childTarget1).isPresent());
    assertTrue(newRuleResolver.getRuleOptional(childTarget2).isPresent());
  }

  @Test
  public void changedCacheableNodeInvalidatesParentChain() {
    TargetNode<?, ?> originalChildNode1 = createTargetNode("child1");
    TargetNode<?, ?> originalChildNode2 = createTargetNode("child2");
    TargetNode<?, ?> originalParentNode =
        createTargetNode("parent", originalChildNode1, originalChildNode2);
    setUpTargetGraphAndResolver(originalParentNode, originalChildNode1, originalChildNode2);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    BuildRule originalChildRule1 = ruleResolver.requireRule(originalChildNode1.getBuildTarget());
    BuildRule originalChildRule2 = ruleResolver.requireRule(originalChildNode2.getBuildTarget());
    BuildRule originalParentRule = ruleResolver.requireRule(originalParentNode.getBuildTarget());

    TargetNode<?, ?> newChildNode1 = createTargetNode("child1", "new_label");
    TargetNode<?, ?> newChildNode2 = createTargetNode("child2");
    TargetNode<?, ?> newParentNode = createTargetNode("parent", newChildNode1, newChildNode2);
    setUpTargetGraphAndResolver(newParentNode, newChildNode1, newChildNode2);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    ruleResolver.requireRule(newChildNode1.getBuildTarget());
    ruleResolver.requireRule(newChildNode2.getBuildTarget());
    ruleResolver.requireRule(newParentNode.getBuildTarget());

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

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    BuildRule originalChildRule1 = ruleResolver.requireRule(originalChildNode1.getBuildTarget());
    BuildRule originalChildRule2 = ruleResolver.requireRule(originalChildNode2.getBuildTarget());
    BuildRule originalParentRule = ruleResolver.requireRule(originalParentNode.getBuildTarget());

    TargetNode<?, ?> newChildNode1 = createUncacheableTargetNode("child1");
    TargetNode<?, ?> newChildNode2 = createTargetNode("child2");
    TargetNode<?, ?> newParentNode = createTargetNode("parent", newChildNode1, newChildNode2);
    setUpTargetGraphAndResolver(newParentNode, newChildNode1, newChildNode2);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    ruleResolver.requireRule(newChildNode1.getBuildTarget());
    ruleResolver.requireRule(newChildNode2.getBuildTarget());
    ruleResolver.requireRule(newParentNode.getBuildTarget());

    assertTrue(ruleResolver.getRuleOptional(newParentNode.getBuildTarget()).isPresent());
    assertNotSame(originalParentRule, ruleResolver.getRule(newParentNode.getBuildTarget()));

    assertTrue(ruleResolver.getRuleOptional(newChildNode1.getBuildTarget()).isPresent());
    assertNotSame(originalChildRule1, ruleResolver.getRule(newChildNode1.getBuildTarget()));

    assertTrue(ruleResolver.getRuleOptional(newChildNode2.getBuildTarget()).isPresent());
    assertSame(originalChildRule2, ruleResolver.getRule(newChildNode2.getBuildTarget()));
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

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    ruleResolver.requireRule(declaredChildNode.getBuildTarget());
    ruleResolver.requireRule(extraChildNode.getBuildTarget());
    ruleResolver.requireRule(targetGraphOnlyChildNode.getBuildTarget());
    ruleResolver.requireRule(parentNode.getBuildTarget());

    setUpTargetGraphAndResolver(
        parentNode, declaredChildNode, extraChildNode, targetGraphOnlyChildNode);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    ruleResolver.requireRule(parentNode.getBuildTarget());

    assertTrue(ruleResolver.getRuleOptional(declaredChildNode.getBuildTarget()).isPresent());
    assertTrue(ruleResolver.getRuleOptional(extraChildNode.getBuildTarget()).isPresent());
    assertTrue(ruleResolver.getRuleOptional(targetGraphOnlyChildNode.getBuildTarget()).isPresent());
  }

  @Test
  public void cachedNodeUsesLastRuleResolverForRuntimeDeps() {
    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:test");
    BuildTarget childTarget = parentTarget.withFlavors(InternalFlavor.of("child"));
    TargetNode<?, ?> node =
        FakeTargetNodeBuilder.newBuilder(
                new FakeDescription() {
                  @Override
                  public BuildRule createBuildRule(
                      BuildRuleCreationContext context,
                      BuildTarget buildTarget,
                      BuildRuleParams params,
                      FakeTargetNodeArg args) {
                    BuildRule child =
                        ruleResolver.computeIfAbsent(
                            childTarget, target -> new FakeBuildRule(target));
                    FakeBuildRule parent = new FakeBuildRule(parentTarget);
                    parent.setRuntimeDeps(child);
                    return parent;
                  }
                },
                parentTarget)
            .build();
    setUpTargetGraphAndResolver(node);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    BuildRule originalParentBuildRule = ruleResolver.requireRule(node.getBuildTarget());
    BuildRule originalChildBuildRule = ruleResolver.getRule(childTarget);

    setUpTargetGraphAndResolver(node);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    ruleResolver.requireRule(node.getBuildTarget());

    assertTrue(ruleResolver.getRuleOptional(parentTarget).isPresent());
    assertSame(originalParentBuildRule, ruleResolver.getRule(parentTarget));

    assertTrue(ruleResolver.getRuleOptional(childTarget).isPresent());
    assertSame(originalChildBuildRule, ruleResolver.getRule(childTarget));
  }

  @Test
  public void ruleResolversUpdatedForCachedNodeSubtreeLoadedFromCache() {
    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:test");
    BuildTarget childTarget1 = parentTarget.withFlavors(InternalFlavor.of("flav1"));
    BuildTarget childTarget2 = parentTarget.withFlavors(InternalFlavor.of("flav2"));
    TargetNode<?, ?> node =
        FakeTargetNodeBuilder.newBuilder(
                new FakeDescription() {
                  @Override
                  public BuildRule createBuildRule(
                      BuildRuleCreationContext context,
                      BuildTarget buildTarget,
                      BuildRuleParams params,
                      FakeTargetNodeArg args) {
                    BuildRule child1 =
                        context
                            .getBuildRuleResolver()
                            .computeIfAbsent(childTarget1, target -> new FakeBuildRule(target));
                    BuildRule child2 =
                        context
                            .getBuildRuleResolver()
                            .computeIfAbsent(childTarget2, target -> new FakeBuildRule(target));
                    return new FakeBuildRule(parentTarget, child1, child2);
                  }
                },
                parentTarget)
            .build();
    setUpTargetGraphAndResolver(node);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    FakeBuildRule parentBuildRule = (FakeBuildRule) ruleResolver.requireRule(node.getBuildTarget());
    FakeBuildRule childBuildRule1 = (FakeBuildRule) ruleResolver.getRule(childTarget1);
    FakeBuildRule childBuildRule2 = (FakeBuildRule) ruleResolver.getRule(childTarget2);

    BuildRuleResolver newRuleResolver = createBuildRuleResolver(targetGraph);
    generator.populateRuleResolverWithCachedRules(targetGraph, newRuleResolver);
    newRuleResolver.requireRule(node.getBuildTarget());

    assertSame(newRuleResolver, parentBuildRule.getRuleResolver());
    assertSame(newRuleResolver, childBuildRule1.getRuleResolver());
    assertSame(newRuleResolver, childBuildRule2.getRuleResolver());
  }

  @Test
  public void ruleResolversUpdatedForCachedNodeSubtreeNotLoadedFromCache() {
    BuildTarget rootTarget1 = BuildTargetFactory.newInstance("//:test1");
    BuildTarget childTarget1 = rootTarget1.withFlavors(InternalFlavor.of("child1"));
    BuildTarget childTarget2 = rootTarget1.withFlavors(InternalFlavor.of("child2"));
    TargetNode<?, ?> node1 =
        FakeTargetNodeBuilder.newBuilder(
                new FakeDescription() {
                  @Override
                  public BuildRule createBuildRule(
                      BuildRuleCreationContext context,
                      BuildTarget buildTarget,
                      BuildRuleParams params,
                      FakeTargetNodeArg args) {
                    BuildRule child1 =
                        context
                            .getBuildRuleResolver()
                            .computeIfAbsent(childTarget1, target -> new FakeBuildRule(target));
                    BuildRule child2 =
                        context
                            .getBuildRuleResolver()
                            .computeIfAbsent(childTarget2, target -> new FakeBuildRule(target));
                    return new FakeBuildRule(rootTarget1, child1, child2);
                  }
                },
                rootTarget1)
            .build();
    BuildTarget rootTarget2 = BuildTargetFactory.newInstance("//:test2");
    TargetNode<?, ?> node2 = FakeTargetNodeBuilder.newBuilder(rootTarget2).build();
    setUpTargetGraphAndResolver(node1, node2);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    FakeBuildRule rootBuildRule1 = (FakeBuildRule) ruleResolver.requireRule(node1.getBuildTarget());
    FakeBuildRule childBuildRule1 = (FakeBuildRule) ruleResolver.getRule(childTarget1);
    FakeBuildRule childBuildRule2 = (FakeBuildRule) ruleResolver.getRule(childTarget2);
    ruleResolver.requireRule(node2.getBuildTarget());

    BuildRuleResolver newRuleResolver = createBuildRuleResolver(targetGraph);
    generator.populateRuleResolverWithCachedRules(targetGraph, newRuleResolver);
    newRuleResolver.requireRule(node2.getBuildTarget());

    assertSame(newRuleResolver, rootBuildRule1.getRuleResolver());
    assertSame(newRuleResolver, childBuildRule1.getRuleResolver());
    assertSame(newRuleResolver, childBuildRule2.getRuleResolver());
  }

  @Test
  public void lastRuleResolverInvalidatedAfterTargetGraphWalk() {
    expectedException.expect(IllegalStateException.class);

    TargetNode<?, ?> node = createTargetNode("node");
    setUpTargetGraphAndResolver(node);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    ruleResolver.requireRule(node.getBuildTarget());

    BuildRuleResolver oldRuleResolver = ruleResolver;
    setUpTargetGraphAndResolver(node);

    generator.populateRuleResolverWithCachedRules(targetGraph, ruleResolver);
    ruleResolver.requireRule(node.getBuildTarget());

    oldRuleResolver.getRuleOptional(node.getBuildTarget());
  }

  private FakeTargetNodeBuilder createTargetNodeBuilder(String name) {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//test:" + name);
    return FakeTargetNodeBuilder.newBuilder(new FakeDescription(), buildTarget);
  }

  private TargetNode<?, ?> createTargetNode(String name, TargetNode<?, ?>... deps) {
    return createTargetNode(name, null, deps);
  }

  private TargetNode<?, ?> createTargetNode(String name, String label, TargetNode<?, ?>... deps) {
    return createTargetNode(name, label, new FakeDescription(), deps);
  }

  private TargetNode<?, ?> createTargetNode(
      String name, String label, FakeDescription description, TargetNode<?, ?>... deps) {
    FakeTargetNodeBuilder targetNodeBuilder =
        FakeTargetNodeBuilder.newBuilder(
                description, BuildTargetFactory.newInstance("//test:" + name))
            .setProducesCacheableSubgraph(true);

    for (TargetNode<?, ?> dep : deps) {
      targetNodeBuilder.getArgForPopulating().addDeps(dep.getBuildTarget());
    }
    if (label != null) {
      targetNodeBuilder.getArgForPopulating().addLabels(label);
    }
    return targetNodeBuilder.build();
  }

  private TargetNode<?, ?> createUncacheableTargetNode(String target) {
    return FakeTargetNodeBuilder.newBuilder(BuildTargetFactory.newInstance("//test:" + target))
        .setProducesCacheableSubgraph(false)
        .build();
  }

  private void setUpTargetGraphAndResolver(TargetNode<?, ?>... nodes) {
    targetGraph = TargetGraphFactory.newInstance(nodes);
    ruleResolver = createBuildRuleResolver(targetGraph);
  }

  private BuildRuleResolver createBuildRuleResolver(TargetGraph targetGraph) {
    return new SingleThreadedBuildRuleResolver(
        targetGraph,
        new DefaultTargetNodeToBuildRuleTransformer(),
        new TestCellBuilder().build().getCellProvider());
  }
}
