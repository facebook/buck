/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.versions;

import static org.junit.Assert.assertThat;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.timing.FakeClock;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.ForkJoinPool;
import org.hamcrest.Matchers;
import org.junit.Test;

public class VersionedTargetGraphCacheTest {

  private static final BuckEventBus BUS = new DefaultBuckEventBus(new FakeClock(0), new BuildId());
  private static final ForkJoinPool POOL = new ForkJoinPool(1);

  @Test
  public void testEmpty() throws Exception {
    VersionedTargetGraphCache cache = new VersionedTargetGraphCache();
    TargetGraphAndBuildTargets graph = createSimpleGraph();
    VersionedTargetGraphCacheResult result =
        cache.getVersionedTargetGraph(
            BUS, new DefaultTypeCoercerFactory(), graph, ImmutableMap.of(), POOL);
    assertEmpty(result);
  }

  @Test
  public void testHit() throws Exception {
    VersionedTargetGraphCache cache = new VersionedTargetGraphCache();
    TargetGraphAndBuildTargets graph = createSimpleGraph();
    VersionedTargetGraphCacheResult firstResult =
        cache.getVersionedTargetGraph(
            BUS, new DefaultTypeCoercerFactory(), graph, ImmutableMap.of(), POOL);
    assertEmpty(firstResult);
    VersionedTargetGraphCacheResult secondResult =
        cache.getVersionedTargetGraph(
            BUS, new DefaultTypeCoercerFactory(), graph, ImmutableMap.of(), POOL);
    assertHit(secondResult, firstResult.getTargetGraphAndBuildTargets());
  }

  @Test
  public void testPoolChangeCausesHit() throws Exception {
    VersionedTargetGraphCache cache = new VersionedTargetGraphCache();
    TargetGraphAndBuildTargets graph = createSimpleGraph();
    VersionedTargetGraphCacheResult firstResult =
        cache.getVersionedTargetGraph(
            BUS, new DefaultTypeCoercerFactory(), graph, ImmutableMap.of(), POOL);
    assertEmpty(firstResult);
    VersionedTargetGraphCacheResult secondResult =
        cache.getVersionedTargetGraph(
            BUS, new DefaultTypeCoercerFactory(), graph, ImmutableMap.of(), new ForkJoinPool(2));
    assertHit(secondResult, firstResult.getTargetGraphAndBuildTargets());
  }

  @Test
  public void testGraphChangeCausesMiss() throws Exception {
    VersionedTargetGraphCache cache = new VersionedTargetGraphCache();
    TargetGraphAndBuildTargets firstGraph = createSimpleGraph();
    VersionedTargetGraphCacheResult firstResult =
        cache.getVersionedTargetGraph(
            BUS, new DefaultTypeCoercerFactory(), firstGraph, ImmutableMap.of(), POOL);
    assertEmpty(firstResult);
    TargetGraphAndBuildTargets secondGraph = createSimpleGraph();
    VersionedTargetGraphCacheResult secondResult =
        cache.getVersionedTargetGraph(
            BUS, new DefaultTypeCoercerFactory(), secondGraph, ImmutableMap.of(), POOL);
    assertMismatch(secondResult, firstResult.getTargetGraphAndBuildTargets());
  }

  @Test
  public void testVersionUniverseChangeCausesMiss() throws Exception {
    VersionedTargetGraphCache cache = new VersionedTargetGraphCache();
    TargetGraphAndBuildTargets graph = createSimpleGraph();
    ImmutableMap<String, VersionUniverse> firstVersionUniverses = ImmutableMap.of();
    VersionedTargetGraphCacheResult firstResult =
        cache.getVersionedTargetGraph(
            BUS, new DefaultTypeCoercerFactory(), graph, firstVersionUniverses, POOL);
    assertEmpty(firstResult);
    ImmutableMap<String, VersionUniverse> secondVersionUniverses =
        ImmutableMap.of("foo", VersionUniverse.of(ImmutableMap.of()));
    VersionedTargetGraphCacheResult secondResult =
        cache.getVersionedTargetGraph(
            BUS, new DefaultTypeCoercerFactory(), graph, secondVersionUniverses, POOL);
    assertMismatch(secondResult, firstResult.getTargetGraphAndBuildTargets());
  }

  private TargetGraphAndBuildTargets createSimpleGraph() {
    TargetNode<?, ?> root = new VersionRootBuilder("//:root").build();
    TargetGraph graph = TargetGraphFactory.newInstance(root);
    return TargetGraphAndBuildTargets.of(graph, ImmutableSet.of(root.getBuildTarget()));
  }

  private void assertHit(
      VersionedTargetGraphCacheResult result, TargetGraphAndBuildTargets previousGraph) {
    assertThat(result.getType(), Matchers.is(VersionedTargetGraphCache.ResultType.HIT));
    assertThat(result.getTargetGraphAndBuildTargets(), Matchers.is(previousGraph));
  }

  private void assertEmpty(VersionedTargetGraphCacheResult result) {
    assertThat(result.getType(), Matchers.is(VersionedTargetGraphCache.ResultType.EMPTY));
  }

  private void assertMismatch(
      VersionedTargetGraphCacheResult result, TargetGraphAndBuildTargets previousGraph) {
    assertThat(result.getType(), Matchers.is(VersionedTargetGraphCache.ResultType.MISMATCH));
    assertThat(result.getTargetGraphAndBuildTargets(), Matchers.not(Matchers.is(previousGraph)));
  }
}
