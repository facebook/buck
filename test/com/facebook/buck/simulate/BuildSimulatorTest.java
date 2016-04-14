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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BuildSimulatorTest {
  private static final BuildTarget ROOT_NODE =
      BuildTargetFactory.newInstance("//root/target/node:target");
  private static final int NUMBER_OF_THREADS = 42;
  private static final long DEFAULT_MILLIS = 21;
  private static final int WIDE_GRAPH_LEAF_NODES_COUNT = 100;
  private static final BuckEventBus eventBus = BuckEventBusFactory.newInstance();


  @Test
  public void testOneNodeActionGraph() throws IOException {
    SimulateTimes times = SimulateTimes.createEmpty(DEFAULT_MILLIS);
    TargetGraph oneNodeGraph = createOneNodeGraph();
    ActionGraphAndResolver result = Preconditions.checkNotNull(
        ActionGraphCache.getFreshActionGraph(eventBus, oneNodeGraph));
    BuildSimulator sim = newBuildSimulator(times, result);
    SimulateReport report = sim.simulateBuild(
        0,
        ImmutableList.of(ROOT_NODE));
    Assert.assertEquals(1, report.getRunReports().size());
    SingleRunReport runReport = report.getRunReports().get(0);
    Assert.assertEquals(1, runReport.getActionGraphNodesWithoutSimulateTime());
    Assert.assertEquals(times.getRuleFallbackTimeMillis(), runReport.getBuildDurationMillis());
  }

  @Test
  public void testMultipleTimeAggregates() throws IOException {
    SimulateTimes times = SimulateTimesTest.createDefaultTestInstance();
    TargetGraph oneNodeGraph = createOneNodeGraph();

    ActionGraphAndResolver result = Preconditions.checkNotNull(
        ActionGraphCache.getFreshActionGraph(eventBus, oneNodeGraph));
    BuildSimulator sim = newBuildSimulator(times, result);
    SimulateReport report = sim.simulateBuild(
        0,
        ImmutableList.of(ROOT_NODE));
    Assert.assertEquals(
        times.getTimeAggregates().size(),
        report.getRunReports().size());
  }

  private static BuildSimulator newBuildSimulator(
      SimulateTimes times,
      ActionGraphAndResolver result,
      int numberThreads) {
    BuckEventBus mockBus = EasyMock.createNiceMock(BuckEventBus.class);
    return new BuildSimulator(
        mockBus,
        times,
        result.getActionGraph(),
        result.getResolver(),
        numberThreads);
  }

  private static BuildSimulator newBuildSimulator(
      SimulateTimes times, ActionGraphAndResolver result) {
    return newBuildSimulator(times, result, NUMBER_OF_THREADS);
  }

  @Test
  public void testWideActionGraphSingledThreaded() throws IOException {
    testTargetGraphWith(
        createWideGraph(),
        1,
        DEFAULT_MILLIS * (1 + WIDE_GRAPH_LEAF_NODES_COUNT));
  }

  @Test
  public void testWideActionGraphSingle2Threads() throws IOException {
    testTargetGraphWith(
        createWideGraph(),
        2,
        DEFAULT_MILLIS + DEFAULT_MILLIS * WIDE_GRAPH_LEAF_NODES_COUNT / 2);
  }

  @Test
  public void testWideActionGraphSingle1000Threads() throws IOException {
    testTargetGraphWith(
        createWideGraph(),
        1000,
        DEFAULT_MILLIS * 2);
  }

  @Test
  public void testDiamondActionGraphSingledThreaded() throws IOException {
    testTargetGraphWith(
        createDiamondGraph(),
        1,
        DEFAULT_MILLIS * 4);
  }

  @Test
  public void testDiamondActionGraphSingle2Threads() throws IOException {
    testTargetGraphWith(
        createDiamondGraph(),
        2,
        DEFAULT_MILLIS * 3);
  }

  @Test
  public void testDiamondActionGraphSingle1000Threads() throws IOException {
    testTargetGraphWith(
        createDiamondGraph(),
        1000,
        DEFAULT_MILLIS * 3);
  }

  @Test
  public void testTriangularActionGraphSingledThreaded() throws IOException {
    testTargetGraphWith(
        createTriangularGraph(),
        1,
        DEFAULT_MILLIS * 3);
  }

  @Test
  public void testTriangularActionGraphSingle2Threads() throws IOException {
    testTargetGraphWith(
        createTriangularGraph(),
        2,
        DEFAULT_MILLIS * 2);
  }

  @Test
  public void testTriangularActionGraphSingle1000Threads() throws IOException {
    testTargetGraphWith(
        createTriangularGraph(),
        1000,
        DEFAULT_MILLIS * 2);
  }

  private void testTargetGraphWith(
      TargetGraph targetGraph,
      int numberThreads,
      long expectedDurationMillis) throws IOException {
    SimulateTimes times = SimulateTimes.createEmpty(DEFAULT_MILLIS);
    ActionGraphAndResolver result = Preconditions.checkNotNull(
        ActionGraphCache.getFreshActionGraph(eventBus, targetGraph));
    BuildSimulator sim = newBuildSimulator(times, result, numberThreads);
    SimulateReport report = sim.simulateBuild(
        0,
        ImmutableList.of(ROOT_NODE));
    Assert.assertEquals(1, report.getRunReports().size());
    SingleRunReport runReport = report.getRunReports().get(0);
    Assert.assertEquals(1, runReport.getBuildTargets().size());
    Assert.assertEquals(expectedDurationMillis, runReport.getBuildDurationMillis());
  }

  private static TargetGraph createWideGraph() {
    List<TargetNode<?>> nodes = new ArrayList<>();
    for (int i = 0; i < WIDE_GRAPH_LEAF_NODES_COUNT; ++i) {
      nodes.add(
          JavaLibraryBuilder.createBuilder(
              BuildTargetFactory.newInstance("//my/leaft_" + i + ":target"))
              .build());
    }
    JavaLibraryBuilder rootBuilder = JavaLibraryBuilder.createBuilder(ROOT_NODE);
    for (TargetNode<?> node : nodes) {
      rootBuilder.addDep(node.getBuildTarget());
    }
    nodes.add(rootBuilder.build());
    return TargetGraphFactory.newInstance(nodes);
  }

  private static TargetGraph createDiamondGraph() {
    TargetNode<?> leaf =
        JavaLibraryBuilder.createBuilder(
            BuildTargetFactory.newInstance("//test/rule/leaf:target"))
            .build();
    TargetNode<?> left =
        JavaLibraryBuilder.createBuilder(
            BuildTargetFactory.newInstance("//test/rule/left:target"))
            .addDep(leaf.getBuildTarget())
            .build();
    TargetNode<?> right =
        JavaLibraryBuilder.createBuilder(
            BuildTargetFactory.newInstance("//test/rule/right:target"))
            .addDep(leaf.getBuildTarget())
            .build();
    TargetNode<?> root = JavaLibraryBuilder.createBuilder(ROOT_NODE)
        .addDep(left.getBuildTarget())
        .addDep(right.getBuildTarget())
        .build();
    return TargetGraphFactory.newInstance(root, left, right, leaf);
  }

  private static TargetGraph createTriangularGraph() {
    TargetNode<?> left =
        JavaLibraryBuilder.createBuilder(
            BuildTargetFactory.newInstance("//test/rule/left_leaf:target"))
            .build();
    TargetNode<?> right =
        JavaLibraryBuilder.createBuilder(
            BuildTargetFactory.newInstance("//test/rule/right_leaf:target"))
            .build();
    TargetNode<?> root = JavaLibraryBuilder.createBuilder(ROOT_NODE)
        .addDep(left.getBuildTarget())
        .addDep(right.getBuildTarget())
        .build();
    return TargetGraphFactory.newInstance(root, left, right);
  }

  private static TargetGraph createOneNodeGraph() {
    TargetNode<?> targetNode = JavaLibraryBuilder.createBuilder(ROOT_NODE).build();
    return TargetGraphFactory.newInstance(targetNode);
  }

}
