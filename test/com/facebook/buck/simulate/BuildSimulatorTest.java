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

package com.facebook.buck.simulate;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

public class BuildSimulatorTest {

  private static final String ROOT_NODE = "//root/target/node:target";
  private static final int NUMBER_OF_THREADS = 42;
  private static final long DEFAULT_MILLIS = 21;
  private static final int WIDE_GRAPH_LEAF_NODES_COUNT = 100;

  @Test
  public void testOneNodeActionGraph() throws IOException {
    SimulateTimes times = SimulateTimes.createEmpty(DEFAULT_MILLIS);
    BuildSimulator sim = new BuildSimulator(
        times,
        createOneNodeGraph(),
        NUMBER_OF_THREADS);
    SimulateReport report = sim.simulateBuild(
        System.currentTimeMillis(),
        ImmutableList.of(BuildTargetFactory.newInstance((ROOT_NODE))));
    Assert.assertEquals(1, report.getBuildTargets().size());
    Assert.assertEquals(1, report.getActionGraphNodesWithoutSimulateTime());
    Assert.assertEquals(times.getRuleFallbackTimeMillis(), report.getBuildDurationMillis());
  }

  @Test
  public void testWideActionGraphSingledThreaded() throws IOException {
    testActionGraphWith(
        createWideGraph(),
        1,
        DEFAULT_MILLIS * (1 + WIDE_GRAPH_LEAF_NODES_COUNT));
  }

  @Test
  public void testWideActionGraphSingle2Threads() throws IOException {
    testActionGraphWith(
        createWideGraph(),
        2,
        DEFAULT_MILLIS + DEFAULT_MILLIS * WIDE_GRAPH_LEAF_NODES_COUNT / 2);
  }

  @Test
  public void testWideActionGraphSingle1000Threads() throws IOException {
    testActionGraphWith(
        createWideGraph(),
        1000,
        DEFAULT_MILLIS * 2);
  }

  @Test
  public void testDiamondActionGraphSingledThreaded() throws IOException {
    testActionGraphWith(
        createDiamondGraph(),
        1,
        DEFAULT_MILLIS * 4);
  }

  @Test
  public void testDiamondActionGraphSingle2Threads() throws IOException {
    testActionGraphWith(
        createDiamondGraph(),
        2,
        DEFAULT_MILLIS * 3);
  }

  @Test
  public void testDiamondActionGraphSingle1000Threads() throws IOException {
    testActionGraphWith(
        createDiamondGraph(),
        1000,
        DEFAULT_MILLIS * 3);
  }

  @Test
  public void testTriangularActionGraphSingledThreaded() throws IOException {
    testActionGraphWith(
        createTriangularGraph(),
        1,
        DEFAULT_MILLIS * 3);
  }

  @Test
  public void testTriangularActionGraphSingle2Threads() throws IOException {
    testActionGraphWith(
        createTriangularGraph(),
        2,
        DEFAULT_MILLIS * 2);
  }

  @Test
  public void testTriangularActionGraphSingle1000Threads() throws IOException {
    testActionGraphWith(
        createTriangularGraph(),
        1000,
        DEFAULT_MILLIS * 2);
  }

  private void testActionGraphWith(
      ActionGraph actionGraph,
      int numberThreads,
      long expectedDurationMillis) throws IOException {
    SimulateTimes times = SimulateTimes.createEmpty(DEFAULT_MILLIS);
    BuildSimulator sim = new BuildSimulator(
        times,
        actionGraph,
        numberThreads);
    SimulateReport report = sim.simulateBuild(
        System.currentTimeMillis(),
        ImmutableList.of(BuildTargetFactory.newInstance(ROOT_NODE)));
    Assert.assertEquals(1, report.getBuildTargets().size());
    Assert.assertEquals(expectedDurationMillis, report.getBuildDurationMillis());
  }

  private static ActionGraph createWideGraph() {
    List<BuildRule> nodes = Lists.newArrayList();
    for (int i = 0; i < WIDE_GRAPH_LEAF_NODES_COUNT; ++i) {
      TestBuildRule leaf = TestBuildRule.fromName("//my/leaft_" + i + ":target");
      nodes.add(leaf);
    }
    TestBuildRule root = TestBuildRule.fromName(
        ROOT_NODE,
        nodes.toArray(new TestBuildRule[nodes.size()]));
    nodes.add(root);
    return new ActionGraph(nodes);
  }

  private static ActionGraph createDiamondGraph() {
    TestBuildRule leaf = TestBuildRule.fromName("//test/rule/leaf:target");
    TestBuildRule left = TestBuildRule.fromName("//test/rule/left:target", leaf);
    TestBuildRule right = TestBuildRule.fromName("//test/rule/right:target", leaf);
    TestBuildRule root = TestBuildRule.fromName(ROOT_NODE, left, right);
    return new ActionGraph(ImmutableList.<BuildRule>of(root, left, right, leaf));
  }

  private static ActionGraph createTriangularGraph() {
    TestBuildRule left = TestBuildRule.fromName("//test/rule/left_leaf:target");
    TestBuildRule right = TestBuildRule.fromName("//test/rule/right_leaf:target");
    TestBuildRule root = TestBuildRule.fromName(ROOT_NODE, left, right);
    return new ActionGraph(ImmutableList.<BuildRule>of(root, left, right));
  }

  private static ActionGraph createOneNodeGraph() {
    TestBuildRule rule = TestBuildRule.fromName(ROOT_NODE);
    return new ActionGraph(ImmutableList.<BuildRule>of(rule));
  }

  private static class TestBuildRule extends NoopBuildRule {
    private static final ProjectFilesystem FILE_SYSTEM = new FakeProjectFilesystem();
    private static final SourcePathResolver RESOLVER =
        new SourcePathResolver(new BuildRuleResolver());

    public static TestBuildRule fromName(String name, TestBuildRule... deps) {
      return new TestBuildRule(
              new FakeBuildRuleParamsBuilder(name)
                  .setProjectFilesystem(FILE_SYSTEM)
                  .setDeclaredDeps(ImmutableSortedSet.<BuildRule>copyOf(deps))
                  .build());
    }

    private TestBuildRule(BuildRuleParams params) {
      super(params, RESOLVER);
      }

    @Override
    public BuildableProperties getProperties() {
      throw new IllegalStateException("Not implemented");
    }
  }

}
