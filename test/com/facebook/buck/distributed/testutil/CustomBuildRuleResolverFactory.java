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

package com.facebook.buck.distributed.testutil;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.SingleThreadedBuildRuleResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSortedSet;

public class CustomBuildRuleResolverFactory {

  public static final String ROOT_TARGET = "//foo:one";
  public static final String CACHABLE_C = ROOT_TARGET + "cacheable_c";
  public static final String CACHABLE_B = ROOT_TARGET + "cacheable_b";
  public static final String CACHABLE_A = ROOT_TARGET + "cacheable_a";
  public static final String UNCACHABLE_E = ROOT_TARGET + "uncacheable_e";
  public static final String UNCACHABLE_D = ROOT_TARGET + "uncacheable_d";
  public static final String UNCACHABLE_C = ROOT_TARGET + "uncacheable_c";
  public static final String UNCACHABLE_B = ROOT_TARGET + "uncacheable_b";
  public static final String UNCACHABLE_A = ROOT_TARGET + "uncacheable_a";
  public static final String CHAIN_TOP_TARGET = ROOT_TARGET + "_chain_top";
  public static final String LEFT_TARGET = ROOT_TARGET + "_left";
  public static final String RIGHT_TARGET = ROOT_TARGET + "_right";
  public static final String LEAF_TARGET = ROOT_TARGET + "_leaf";
  public static final String UNCACHABLE_ROOT = "//some:target";

  public static BuildRuleResolver createSimpleResolver() throws NoSuchBuildTargetException {
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

  // Graph structure:
  //        / right \
  // root -          - chain top - leaf
  //        \ left  /
  public static BuildRuleResolver createDiamondDependencyResolverWithChainFromLeaf()
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

  // Graph structure
  //  cacheable_a -> uncacheable_b
  public static BuildRuleResolver createBuildGraphWithUncachableLeaf() {
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule uncacheableB =
        newUncacheableRule(resolver, CustomBuildRuleResolverFactory.UNCACHABLE_B);
    newCacheableRule(resolver, CustomBuildRuleResolverFactory.CACHABLE_A, uncacheableB);

    return resolver;
  }

  // Graph structure
  //                 / uncacheable_a -> uncacheable b \
  // uncacheable_root                                  uc_d -> c_b -> uc_e -> c_c
  //                 \ uncacheable_c -> cacheable_a   /
  public static BuildRuleResolver createBuildGraphWithInterleavedUncacheables() {
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    // uncacheable_d -> cacheable_b -> uncacheable_e
    BuildRule cacheableC = newCacheableRule(resolver, CustomBuildRuleResolverFactory.CACHABLE_C);
    BuildRule uncacheableE =
        newUncacheableRule(resolver, CustomBuildRuleResolverFactory.UNCACHABLE_E, cacheableC);
    BuildRule cacheableB =
        newCacheableRule(resolver, CustomBuildRuleResolverFactory.CACHABLE_B, uncacheableE);
    BuildRule uncacheableD =
        newUncacheableRule(resolver, CustomBuildRuleResolverFactory.UNCACHABLE_D, cacheableB);

    // uncacheable_a -> uncacheable b \
    BuildRule uncacheableB =
        newUncacheableRule(resolver, CustomBuildRuleResolverFactory.UNCACHABLE_B, uncacheableD);
    BuildRule uncacheableA =
        newUncacheableRule(resolver, CustomBuildRuleResolverFactory.UNCACHABLE_A, uncacheableB);

    // uncacheable_c -> cacheable a /
    BuildRule cacheableA =
        newCacheableRule(resolver, CustomBuildRuleResolverFactory.CACHABLE_A, uncacheableD);
    BuildRule uncacheableC =
        newUncacheableRule(resolver, CustomBuildRuleResolverFactory.UNCACHABLE_C, cacheableA);

    // uncacheable_root
    newUncacheableRule(
        resolver, CustomBuildRuleResolverFactory.UNCACHABLE_ROOT, uncacheableA, uncacheableC);

    return resolver;
  }

  private static BuildRule newUncacheableRule(
      BuildRuleResolver resolver, String targetString, BuildRule... deps) {
    FakeUncacheableBuildRule uncachableRule =
        new FakeUncacheableBuildRule(
            BuildTargetFactory.newInstance(targetString), new FakeProjectFilesystem(), deps);
    resolver.addToIndex(uncachableRule);
    return uncachableRule;
  }

  private static BuildRule newCacheableRule(
      BuildRuleResolver resolver, String targetString, BuildRule... deps) {
    FakeBuildRule rule =
        new FakeBuildRule(
            BuildTargetFactory.newInstance(targetString), new FakeProjectFilesystem(), deps);
    resolver.addToIndex(rule);
    return rule;
  }

  private static class FakeUncacheableBuildRule extends FakeBuildRule {
    public FakeUncacheableBuildRule(
        BuildTarget target, ProjectFilesystem filesystem, BuildRule... deps) {
      super(target, filesystem, deps);
    }

    @Override
    public boolean isCacheable() {
      return false;
    }
  }
}
