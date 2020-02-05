/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.versions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.features.python.PythonTestBuilder;
import com.facebook.buck.features.python.PythonTestDescriptionArg;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.shell.ExportFileDescriptionArg;
import com.facebook.buck.testutil.CloseableResource;
import com.facebook.buck.util.cache.CacheStats;
import com.facebook.buck.util.cache.InstrumentingCacheStatsTracker;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class VersionedTargetGraphCacheTest {

  private UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory;

  private Version version1 = Version.of("v1");
  private Version version2 = Version.of("v2");
  private BuildTarget versionedAlias = BuildTargetFactory.newInstance("//:alias");

  @Rule
  public CloseableResource<DepsAwareExecutor<? super ComputeResult, ?>> depsAwareExecutor =
      CloseableResource.of(() -> DefaultDepsAwareExecutor.of(4));

  @Before
  public void setUp() {
    unconfiguredBuildTargetFactory = new ParsingUnconfiguredBuildTargetViewFactory();
  }

  @Test
  public void testEmpty() throws Exception {
    InstrumentedVersionedTargetGraphCache cache =
        new InstrumentedVersionedTargetGraphCache(
            new VersionedTargetGraphCache(), new InstrumentingCacheStatsTracker());
    TargetGraphCreationResult graph = createSimpleGraph("foo");
    VersionedTargetGraphCache.VersionedTargetGraphCacheResult result =
        cache.getVersionedTargetGraph(
            depsAwareExecutor.get(),
            new DefaultTypeCoercerFactory(),
            unconfiguredBuildTargetFactory,
            graph,
            ImmutableMap.of(),
            new TestCellBuilder().build());
    assertEmpty(result);
    CacheStats stats = cache.getCacheStats();
    assertEquals(Optional.of(0L), stats.getHitCount());
    assertEquals(Optional.of(1L), stats.getMissCount());
    // verify that the timings are set
    assertTimingsNotEmpty(stats);
  }

  @Test
  public void testHit() throws Exception {
    InstrumentedVersionedTargetGraphCache cache =
        new InstrumentedVersionedTargetGraphCache(
            new VersionedTargetGraphCache(), new InstrumentingCacheStatsTracker());
    TargetGraphCreationResult graph = createSimpleGraph("foo");
    VersionedTargetGraphCache.VersionedTargetGraphCacheResult firstResult =
        cache.getVersionedTargetGraph(
            depsAwareExecutor.get(),
            new DefaultTypeCoercerFactory(),
            unconfiguredBuildTargetFactory,
            graph,
            ImmutableMap.of(),
            new TestCellBuilder().build());
    assertEmpty(firstResult);
    VersionedTargetGraphCache.VersionedTargetGraphCacheResult secondResult =
        cache.getVersionedTargetGraph(
            depsAwareExecutor.get(),
            new DefaultTypeCoercerFactory(),
            unconfiguredBuildTargetFactory,
            graph,
            ImmutableMap.of(),
            new TestCellBuilder().build());
    assertHit(secondResult, firstResult.getTargetGraphCreationResult());
    CacheStats stats = cache.getCacheStats();
    assertEquals(Optional.of(1L), stats.getHitCount());
    assertEquals(Optional.of(1L), stats.getMissCount());
    // verify that the timings are set
    assertTimingsNotEmpty(stats);
  }

  @Test
  public void testPoolChangeCausesHit() throws Exception {
    InstrumentedVersionedTargetGraphCache cache =
        new InstrumentedVersionedTargetGraphCache(
            new VersionedTargetGraphCache(), new InstrumentingCacheStatsTracker());
    TargetGraphCreationResult graph = createSimpleGraph("foo");
    VersionedTargetGraphCache.VersionedTargetGraphCacheResult firstResult =
        cache.getVersionedTargetGraph(
            depsAwareExecutor.get(),
            new DefaultTypeCoercerFactory(),
            unconfiguredBuildTargetFactory,
            graph,
            ImmutableMap.of(),
            new TestCellBuilder().build());
    assertEmpty(firstResult);
    VersionedTargetGraphCache.VersionedTargetGraphCacheResult secondResult =
        cache.getVersionedTargetGraph(
            depsAwareExecutor.get(),
            new DefaultTypeCoercerFactory(),
            unconfiguredBuildTargetFactory,
            graph,
            ImmutableMap.of(),
            new TestCellBuilder().build());
    assertHit(secondResult, firstResult.getTargetGraphCreationResult());
    CacheStats stats = cache.getCacheStats();
    assertEquals(Optional.of(1L), stats.getHitCount());
    assertEquals(Optional.of(1L), stats.getMissCount());
    assertTimingsNotEmpty(stats);
  }

  @Test
  public void testGraphChangeCausesMiss() throws Exception {
    InstrumentedVersionedTargetGraphCache cache =
        new InstrumentedVersionedTargetGraphCache(
            new VersionedTargetGraphCache(), new InstrumentingCacheStatsTracker());
    TargetGraphCreationResult firstGraph = createSimpleGraph("foo");
    VersionedTargetGraphCache.VersionedTargetGraphCacheResult firstResult =
        cache.getVersionedTargetGraph(
            depsAwareExecutor.get(),
            new DefaultTypeCoercerFactory(),
            unconfiguredBuildTargetFactory,
            firstGraph,
            ImmutableMap.of(),
            new TestCellBuilder().build());
    assertEmpty(firstResult);
    TargetGraphCreationResult secondGraph = createSimpleGraph("bar");
    VersionedTargetGraphCache.VersionedTargetGraphCacheResult secondResult =
        cache.getVersionedTargetGraph(
            depsAwareExecutor.get(),
            new DefaultTypeCoercerFactory(),
            unconfiguredBuildTargetFactory,
            secondGraph,
            ImmutableMap.of(),
            new TestCellBuilder().build());
    assertMismatch(secondResult, firstResult.getTargetGraphCreationResult());
    CacheStats stats = cache.getCacheStats();
    assertEquals(Optional.of(0L), stats.getHitCount());
    assertEquals(Optional.of(1L), stats.getMissCount());
    assertEquals(Optional.of(1L), stats.getMissMatchCount());
    // verify that the timings are set
    assertTimingsNotEmpty(stats);
  }

  @Test
  public void testVersionUniverseChangeCausesMiss() throws Exception {
    InstrumentedVersionedTargetGraphCache cache =
        new InstrumentedVersionedTargetGraphCache(
            new VersionedTargetGraphCache(), new InstrumentingCacheStatsTracker());
    TargetGraphCreationResult graph = createSimpleGraph("foo");
    ImmutableMap<String, VersionUniverse> firstVersionUniverses = ImmutableMap.of();
    VersionedTargetGraphCache.VersionedTargetGraphCacheResult firstResult =
        cache.getVersionedTargetGraph(
            depsAwareExecutor.get(),
            new DefaultTypeCoercerFactory(),
            unconfiguredBuildTargetFactory,
            graph,
            firstVersionUniverses,
            new TestCellBuilder().build());
    assertEmpty(firstResult);
    ImmutableMap<String, VersionUniverse> secondVersionUniverses =
        ImmutableMap.of("foo", VersionUniverse.of(ImmutableMap.of(versionedAlias, version2)));
    VersionedTargetGraphCache.VersionedTargetGraphCacheResult secondResult =
        cache.getVersionedTargetGraph(
            depsAwareExecutor.get(),
            new DefaultTypeCoercerFactory(),
            unconfiguredBuildTargetFactory,
            graph,
            secondVersionUniverses,
            new TestCellBuilder().build());
    assertMismatch(secondResult, firstResult.getTargetGraphCreationResult());
    CacheStats stats = cache.getCacheStats();
    assertEquals(Optional.of(0L), stats.getHitCount());
    assertEquals(Optional.of(1L), stats.getMissCount());
    assertEquals(Optional.of(1L), stats.getMissMatchCount());
    // verify that the timings are set
    assertTimingsNotEmpty(stats);
  }

  @Test
  public void testDifferentInstrumentedCacheDoesNotInterfere() throws Exception {
    VersionedTargetGraphCache baseCache = new VersionedTargetGraphCache();
    InstrumentedVersionedTargetGraphCache cache1 =
        new InstrumentedVersionedTargetGraphCache(baseCache, new InstrumentingCacheStatsTracker());
    TargetGraphCreationResult graph = createSimpleGraph("foo");
    VersionedTargetGraphCache.VersionedTargetGraphCacheResult firstResult =
        cache1.getVersionedTargetGraph(
            depsAwareExecutor.get(),
            new DefaultTypeCoercerFactory(),
            unconfiguredBuildTargetFactory,
            graph,
            ImmutableMap.of(),
            new TestCellBuilder().build());

    CacheStats stats = cache1.getCacheStats();
    assertEquals(Optional.of(0L), stats.getHitCount());
    assertEquals(Optional.of(1L), stats.getMissCount());
    // verify that the timings are set
    assertTimingsNotEmpty(stats);

    InstrumentedVersionedTargetGraphCache cache2 =
        new InstrumentedVersionedTargetGraphCache(baseCache, new InstrumentingCacheStatsTracker());
    VersionedTargetGraphCache.VersionedTargetGraphCacheResult secondResult =
        cache2.getVersionedTargetGraph(
            depsAwareExecutor.get(),
            new DefaultTypeCoercerFactory(),
            unconfiguredBuildTargetFactory,
            graph,
            ImmutableMap.of(),
            new TestCellBuilder().build());
    assertHit(secondResult, firstResult.getTargetGraphCreationResult());
    stats = cache2.getCacheStats();
    assertEquals(Optional.of(1L), stats.getHitCount());
    assertEquals(Optional.of(0L), stats.getMissCount());
    // verify that the timings are set
    assertTimingsNotEmpty(stats);
  }

  private TargetGraphCreationResult createSimpleGraph(String basePath) {
    TargetNode<?> root = new VersionRootBuilder(String.format("//%s:root", basePath)).build();
    TargetNode<ExportFileDescriptionArg> v1 =
        new ExportFileBuilder(BuildTargetFactory.newInstance(String.format("//%s:v1", basePath)))
            .build();
    TargetNode<ExportFileDescriptionArg> v2 =
        new ExportFileBuilder(BuildTargetFactory.newInstance(String.format("//%s:v2", basePath)))
            .build();
    TargetNode<VersionedAliasDescriptionArg> alias =
        new VersionedAliasBuilder(versionedAlias)
            .setVersions(
                ImmutableMap.of(
                    version1, v1.getBuildTarget(),
                    version2, v2.getBuildTarget()))
            .build();
    TargetNode<PythonTestDescriptionArg> pythonTest =
        PythonTestBuilder.create(
                BuildTargetFactory.newInstance(String.format("//%s:test", basePath)))
            .setDeps(ImmutableSortedSet.of(alias.getBuildTarget()))
            .build();
    TargetGraph graph = TargetGraphFactory.newInstance(root, pythonTest, alias, v1, v2);
    return TargetGraphCreationResult.of(
        graph,
        ImmutableSet.of(
            root.getBuildTarget(),
            pythonTest.getBuildTarget(),
            v1.getBuildTarget(),
            v2.getBuildTarget()));
  }

  private void assertHit(
      VersionedTargetGraphCache.VersionedTargetGraphCacheResult result,
      TargetGraphCreationResult previousGraph) {
    assertThat(result.getType(), Matchers.is(VersionedTargetGraphCache.ResultType.HIT));
    assertThat(result.getTargetGraphCreationResult(), Matchers.is(previousGraph));
  }

  private void assertEmpty(VersionedTargetGraphCache.VersionedTargetGraphCacheResult result) {
    assertThat(result.getType(), Matchers.is(VersionedTargetGraphCache.ResultType.EMPTY));
  }

  private void assertMismatch(
      VersionedTargetGraphCache.VersionedTargetGraphCacheResult result,
      TargetGraphCreationResult previousGraph) {
    assertThat(result.getType(), Matchers.is(VersionedTargetGraphCache.ResultType.MISMATCH));
    assertThat(result.getTargetGraphCreationResult(), Matchers.not(Matchers.is(previousGraph)));
  }

  private void assertTimingsNotEmpty(CacheStats stats) {
    // verify that the timings are set
    assertTrue(stats.getTotalLoadTime().isPresent());
    assertTrue(stats.getRetrievalTime().isPresent());
    assertTrue(stats.getTotalMissTime().isPresent());
  }
}
