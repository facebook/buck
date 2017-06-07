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

package com.facebook.buck.distributed;

import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Assert;
import org.junit.Test;

public class BuildTargetsQueueTest {
  public static final String TARGET_NAME = "//foo:one";

  @Test
  public void testEmptyQueue() {
    BuildTargetsQueue queue = BuildTargetsQueue.newEmptyQueue();
    ImmutableList<String> zeroDepTargets = queue.dequeueZeroDependencyNodes(ImmutableList.of());
    Assert.assertEquals(0, zeroDepTargets.size());
  }

  @Test
  public void testResolverWithoutAnyTargets() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTargetsQueue queue = BuildTargetsQueue.newQueue(resolver, ImmutableList.of());
    ImmutableList<String> zeroDepTargets = queue.dequeueZeroDependencyNodes(ImmutableList.of());
    Assert.assertEquals(0, zeroDepTargets.size());
  }

  @Test
  public void testResolverWithOnSingleTarget() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = createSimpleResolver();
    BuildTarget target = BuildTargetFactory.newInstance(TARGET_NAME);
    BuildTargetsQueue queue = BuildTargetsQueue.newQueue(resolver, ImmutableList.of(target));
    ImmutableList<String> zeroDepTargets = queue.dequeueZeroDependencyNodes(ImmutableList.of());
    Assert.assertEquals(1, zeroDepTargets.size());
    Assert.assertEquals("//foo:one", zeroDepTargets.get(0));

    Assert.assertEquals(0, queue.dequeueZeroDependencyNodes(ImmutableList.of()).size());
    Assert.assertEquals(
        0,
        queue.dequeueZeroDependencyNodes(ImmutableList.of(target.getFullyQualifiedName())).size());
  }

  @Test
  public void testResolverWithDiamondDependencyTarget() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = createDiamondDependencyResolver();
    BuildTarget target = BuildTargetFactory.newInstance(TARGET_NAME);
    BuildTargetsQueue queue = BuildTargetsQueue.newQueue(resolver, ImmutableList.of(target));

    ImmutableList<String> zeroDepTargets = queue.dequeueZeroDependencyNodes(ImmutableList.of());
    Assert.assertEquals(1, zeroDepTargets.size());
    Assert.assertEquals(TARGET_NAME + "_leaf", zeroDepTargets.get(0));

    zeroDepTargets = queue.dequeueZeroDependencyNodes(zeroDepTargets);
    Assert.assertEquals(2, zeroDepTargets.size());
    Assert.assertTrue(zeroDepTargets.contains(TARGET_NAME + "_right"));
    Assert.assertTrue(zeroDepTargets.contains(TARGET_NAME + "_left"));

    zeroDepTargets = queue.dequeueZeroDependencyNodes(zeroDepTargets);
    Assert.assertEquals(1, zeroDepTargets.size());
    Assert.assertEquals(TARGET_NAME, zeroDepTargets.get(0));

    Assert.assertEquals(0, queue.dequeueZeroDependencyNodes(zeroDepTargets).size());
  }

  private static BuildRuleResolver createSimpleResolver() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ImmutableSortedSet<BuildRule> buildRules =
        ImmutableSortedSet.of(
            JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance(TARGET_NAME))
                .build(resolver),
            JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//foo:two"))
                .build(resolver));
    resolver.addAllToIndex(buildRules);
    return resolver;
  }

  public static BuildTargetsQueue createDiamondDependencyQueue() throws NoSuchBuildTargetException {
    return BuildTargetsQueue.newQueue(
        createDiamondDependencyResolver(),
        ImmutableList.of(BuildTargetFactory.newInstance(TARGET_NAME)));
  }

  public static BuildRuleResolver createDiamondDependencyResolver()
      throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    BuildTarget root = BuildTargetFactory.newInstance(TARGET_NAME);
    BuildTarget left = BuildTargetFactory.newInstance(TARGET_NAME + "_left");
    BuildTarget right = BuildTargetFactory.newInstance(TARGET_NAME + "_right");
    BuildTarget leaf = BuildTargetFactory.newInstance(TARGET_NAME + "_leaf");

    ImmutableSortedSet<BuildRule> buildRules =
        ImmutableSortedSet.of(
            JavaLibraryBuilder.createBuilder(leaf).build(resolver),
            JavaLibraryBuilder.createBuilder(left).addDep(leaf).build(resolver),
            JavaLibraryBuilder.createBuilder(right).addDep(leaf).build(resolver),
            JavaLibraryBuilder.createBuilder(root).addDep(left).addDep(right).build(resolver));
    resolver.addAllToIndex(buildRules);
    return resolver;
  }
}
