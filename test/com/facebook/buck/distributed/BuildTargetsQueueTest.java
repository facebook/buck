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
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class BuildTargetsQueueTest {
  public static final String TRANSITIVE_DEP_RULE = "//:transitive_dep";
  public static final String HAS_RUNTIME_DEP_RULE = "//:runtime_dep";
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  public static final String TARGET_NAME = "//foo:one";

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
    ImmutableList<String> zeroDepTargets = queue.dequeueZeroDependencyNodes(ImmutableList.of());
    Assert.assertEquals(0, zeroDepTargets.size());
  }

  @Test
  public void testResolverWithoutAnyTargets() {
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
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
  public void testResolverWithTargetThatHasRuntimeDep()
      throws NoSuchBuildTargetException, InterruptedException {
    BuildRuleResolver resolver = createRuntimeDepsResolver();
    BuildTarget target = BuildTargetFactory.newInstance(HAS_RUNTIME_DEP_RULE);
    BuildTargetsQueue queue = BuildTargetsQueue.newQueue(resolver, ImmutableList.of(target));
    ImmutableList<String> zeroDepTargets = queue.dequeueZeroDependencyNodes(ImmutableList.of());
    Assert.assertEquals(1, zeroDepTargets.size());
    Assert.assertEquals(TRANSITIVE_DEP_RULE, zeroDepTargets.get(0));

    ImmutableList<String> newZeroDepNodes =
        queue.dequeueZeroDependencyNodes(ImmutableList.of(TRANSITIVE_DEP_RULE));
    Assert.assertEquals(1, newZeroDepNodes.size());
    Assert.assertEquals(HAS_RUNTIME_DEP_RULE, newZeroDepNodes.get(0));

    Assert.assertEquals(
        0, queue.dequeueZeroDependencyNodes(ImmutableList.of(HAS_RUNTIME_DEP_RULE)).size());
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

  public static BuildTargetsQueue createDiamondDependencyQueue() throws NoSuchBuildTargetException {
    return BuildTargetsQueue.newQueue(
        createDiamondDependencyResolver(),
        ImmutableList.of(BuildTargetFactory.newInstance(TARGET_NAME)));
  }

  public static BuildRuleResolver createDiamondDependencyResolver()
      throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

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
    buildRules.forEach(resolver::addToIndex);
    return resolver;
  }
}
