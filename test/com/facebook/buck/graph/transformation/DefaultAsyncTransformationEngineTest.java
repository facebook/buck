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

package com.facebook.buck.graph.transformation;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableMap;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/** Test and demonstration of {@link DefaultAsyncTransformationEngine} */
public class DefaultAsyncTransformationEngineTest {

  private MutableGraph<Long> graph;
  private TrackingCache cache;

  @Before
  public void setUp() {
    graph = GraphBuilder.directed().build();

    /**
     * Make a graph
     *
     * <p>Edges directed down
     *
     * <pre>
     *            1
     *         /  |  \
     *        2  4 <- 5
     *       /
     *      3
     * </pre>
     */
    graph.addNode(1L);
    graph.addNode(2L);
    graph.addNode(3L);
    graph.addNode(4L);
    graph.addNode(5L);

    graph.putEdge(1L, 2L);
    graph.putEdge(1L, 4L);
    graph.putEdge(1L, 5L);
    graph.putEdge(5L, 4L);
    graph.putEdge(2L, 3L);

    cache = new TrackingCache();
  }

  /**
   * Demonstration of usage of {@link TransformationEngineCache} with stats tracking used to verify
   * behaviour of the {@link DefaultAsyncTransformationEngine}.
   */
  private final class TrackingCache implements TransformationEngineCache<Long, Long> {

    private final ConcurrentHashMap<Long, Long> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, LongAdder> hitStats = new ConcurrentHashMap<>();

    @Override
    public Optional<Long> get(Long k) {
      Optional<Long> result = Optional.ofNullable(cache.get(k));
      result.ifPresent(r -> hitStats.get(k).increment());
      return result;
    }

    @Override
    public void put(Long k, Long v) {
      cache.put(k, v);
      hitStats.put(k, new LongAdder());
    }

    public ImmutableMap<Long, LongAdder> getStats() {
      return ImmutableMap.copyOf(hitStats);
    }

    public int getSize() {
      return cache.size();
    }
  }

  @Test
  public void requestOnLeafResultsSameValue() {
    ChildrenAdder transformer = new ChildrenAdder(graph);
    DefaultAsyncTransformationEngine<Long, Long> engine =
        new DefaultAsyncTransformationEngine<>(transformer, graph.nodes().size());
    assertEquals((Long) 3L, engine.computeUnchecked(3L));

    assertEquals(0, engine.computationIndex.size());
  }

  @Test
  public void requestOnRootCorrectValue() {
    ChildrenAdder transformer = new ChildrenAdder(graph);
    DefaultAsyncTransformationEngine<Long, Long> engine =
        new DefaultAsyncTransformationEngine<>(transformer, graph.nodes().size());
    assertEquals((Long) 19L, engine.computeUnchecked(1L));

    assertEquals(0, engine.computationIndex.size());
  }

  @Test
  public void canReuseCachedResult() {
    ChildrenAdder transformer = new ChildrenAdder(graph);

    DefaultAsyncTransformationEngine<Long, Long> engine =
        new DefaultAsyncTransformationEngine<>(transformer, graph.nodes().size(), cache);
    Long result = engine.computeUnchecked(3L);

    assertEquals((Long) 3L, result);

    transformer =
        new ChildrenAdder(graph) {
          @Override
          public CompletionStage<Long> transform(
              Long node, TransformationEnvironment<Long, Long> env) {
            Assert.fail("Did not expect call as cache should be used");
            return super.transform(node, env);
          }
        };

    engine = new DefaultAsyncTransformationEngine<>(transformer, graph.nodes().size(), cache);
    Long newResult = engine.computeUnchecked(3L);

    assertEquals(result, newResult);

    // all Futures should be removed
    assertEquals(0, engine.computationIndex.size());
    assertEquals(1, cache.getSize());
    assertEquals(1, cache.hitStats.get((Long) 3L).intValue());
  }

  @Test
  public void canReusePartiallyCachedResult() {
    ChildrenAdder transformer = new ChildrenAdder(graph);
    DefaultAsyncTransformationEngine<Long, Long> engine =
        new DefaultAsyncTransformationEngine<>(transformer, graph.nodes().size(), cache);

    assertEquals((Long) 9L, engine.computeUnchecked(5L));
    assertEquals((Long) 3L, engine.computeUnchecked(3L));

    /**
     *
     *
     * <pre>
     *            1
     *         /  |  \
     *        2  4 <- 5
     *       /
     *      3
     * </pre>
     *
     * <p>So we now have 5, 4, 3 in the cache to be reused.
     */
    transformer =
        new ChildrenAdder(graph) {
          @Override
          public CompletionStage<Long> transform(
              Long node, TransformationEnvironment<Long, Long> env) {
            if (node == 5L || node == 4L || node == 3L) {
              Assert.fail("Did not expect call as cache should be used");
            }
            return super.transform(node, env);
          }
        };

    // reuse the cache
    assertEquals((Long) 19L, engine.computeUnchecked(1L));

    // all Futures should be removed
    assertEquals(0, engine.computationIndex.size());
    assertEquals(5, cache.getSize());
    assertEquals(0, cache.hitStats.get((Long) 1L).intValue());
    assertEquals(0, cache.hitStats.get((Long) 2L).intValue());
    assertEquals(1, cache.hitStats.get((Long) 5L).intValue());
    assertEquals(1, cache.hitStats.get((Long) 3L).intValue());
    assertEquals(1, cache.hitStats.get((Long) 4L).intValue());
  }
}
