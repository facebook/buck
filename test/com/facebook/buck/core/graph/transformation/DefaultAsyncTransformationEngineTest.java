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

package com.facebook.buck.core.graph.transformation;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.graph.transformation.TransformationEnvironment.AsyncSink;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import com.google.common.util.concurrent.Futures;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/** Test and demonstration of {@link DefaultAsyncTransformationEngine} */
public class DefaultAsyncTransformationEngineTest {

  @Rule public Timeout timeout = Timeout.seconds(10);

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
        new DefaultAsyncTransformationEngine<>(
            transformer, graph.nodes().size(), ForkJoinPool.commonPool());
    assertEquals((Long) 3L, engine.computeUnchecked(3L));

    assertComputationIndexBecomesEmpty(engine.computationIndex);
  }

  @Test
  public void requestOnRootCorrectValue() {
    ChildrenAdder transformer = new ChildrenAdder(graph);
    DefaultAsyncTransformationEngine<Long, Long> engine =
        new DefaultAsyncTransformationEngine<>(
            transformer, graph.nodes().size(), ForkJoinPool.commonPool());
    assertEquals((Long) 19L, engine.computeUnchecked(1L));

    assertComputationIndexBecomesEmpty(engine.computationIndex);
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  @Test
  public void requestOnRootCorrectValueWithCustomExecutor() {
    ChildrenAdder transformer = new ChildrenAdder(graph);
    ExecutorService executor = Executors.newFixedThreadPool(3);
    DefaultAsyncTransformationEngine<Long, Long> engine =
        new DefaultAsyncTransformationEngine<>(transformer, graph.nodes().size(), executor);
    assertEquals((Long) 19L, engine.computeUnchecked(1L));
    assertComputationIndexBecomesEmpty(engine.computationIndex);

    executor.shutdown();

    DefaultAsyncTransformationEngine<Long, Long> engine2 =
        new DefaultAsyncTransformationEngine<>(transformer, graph.nodes().size(), executor);
    try {
      engine2.computeUnchecked(1L);
      Assert.fail(
          "Did not expect DefaultAsyncTransformationEngine to compute with an executor that has been shut down");
    } catch (RejectedExecutionException e) {
      // this is expected because the custom executor has been shut down
    }
  }

  @Test
  public void canReuseCachedResult() {
    ChildrenAdder transformer = new ChildrenAdder(graph);

    DefaultAsyncTransformationEngine<Long, Long> engine =
        new DefaultAsyncTransformationEngine<>(
            transformer, graph.nodes().size(), cache, ForkJoinPool.commonPool());
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

    engine =
        new DefaultAsyncTransformationEngine<>(
            transformer, graph.nodes().size(), cache, ForkJoinPool.commonPool());
    Long newResult = engine.computeUnchecked(3L);

    assertEquals(result, newResult);

    // all Futures should be removed
    assertComputationIndexBecomesEmpty(engine.computationIndex);
    assertEquals(1, cache.getSize());
    assertEquals(1, cache.hitStats.get(3L).intValue());
  }

  @Test
  public void canReusePartiallyCachedResult() {
    ChildrenAdder transformer = new ChildrenAdder(graph);
    DefaultAsyncTransformationEngine<Long, Long> engine =
        new DefaultAsyncTransformationEngine<>(
            transformer, graph.nodes().size(), cache, ForkJoinPool.commonPool());

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
    engine =
        new DefaultAsyncTransformationEngine<>(
            transformer, graph.nodes().size(), cache, ForkJoinPool.commonPool());

    // reuse the cache
    assertEquals((Long) 19L, engine.computeUnchecked(1L));

    // all Futures should be removed
    assertComputationIndexBecomesEmpty(engine.computationIndex);
    assertEquals(5, cache.getSize());
    assertEquals(0, cache.hitStats.get(1L).intValue());
    assertEquals(0, cache.hitStats.get(2L).intValue());
    assertEquals(1, cache.hitStats.get(5L).intValue());
    assertEquals(1, cache.hitStats.get(3L).intValue());
    assertEquals(1, cache.hitStats.get(4L).intValue());
  }

  @Test
  public void environmentCanEvaluateAllAndCollect() {
    final int maxValue = 20;
    AsyncTransformer<Integer, ConcurrentHashMap<Integer, Set<Integer>>> transformer =
        (integer, env) -> {
          if (integer < maxValue) {
            return env.evaluateAllAndCollectAsync(
                IntStream.rangeClosed(integer + 1, Math.min(integer + integer + 1, maxValue))
                    .boxed()
                    .collect(Collectors.toList()),
                new AsyncSink<Integer, ConcurrentHashMap<Integer, Set<Integer>>>() {
                  private final ConcurrentHashMap<Integer, Set<Integer>> collect =
                      new ConcurrentHashMap<>();

                  @Override
                  public void sink(Integer key, ConcurrentHashMap<Integer, Set<Integer>> result) {
                    Set<Integer> set =
                        result.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
                    set.add(key);
                    collect.put(key, set);
                    collect.putAll(result);
                  }

                  @Override
                  public ConcurrentHashMap<Integer, Set<Integer>> collect() {
                    return collect;
                  }
                });
          }
          return CompletableFuture.completedFuture(
              new ConcurrentHashMap<>(ImmutableMap.of(maxValue, ImmutableSet.of(maxValue))));
        };
    DefaultAsyncTransformationEngine<Integer, ConcurrentHashMap<Integer, Set<Integer>>> engine =
        new DefaultAsyncTransformationEngine<>(transformer, maxValue);

    Map<Integer, Set<Integer>> result = engine.computeUnchecked(0);
    assertEquals(
        IntStream.range(1, maxValue + 1).boxed().collect(Collectors.toSet()), result.keySet());
    for (Entry<Integer, Set<Integer>> entry : result.entrySet()) {
      assertEquals(
          IntStream.range(entry.getKey(), maxValue + 1).boxed().collect(Collectors.toSet()),
          entry.getValue());
    }
  }

  /**
   * Asserts that the computationIndex of the {@link AsyncTransformationEngine} eventually becomes
   * empty.
   *
   * @param computationIndex the computationIndex of the engine
   */
  private static void assertComputationIndexBecomesEmpty(
      ConcurrentHashMap<Long, CompletableFuture<Long>> computationIndex) {
    // wait for all futures to complete in the computation.
    // we can have situation where the computation was completed by using the cache.
    Futures.getUnchecked(
        CompletableFuture.allOf(computationIndex.values().toArray(new CompletableFuture[] {})));

    assertEquals(0, computationIndex.size());
  }
}
