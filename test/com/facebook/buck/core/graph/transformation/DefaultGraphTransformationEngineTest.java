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

import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareTask;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import com.google.common.util.concurrent.Futures;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.Timeout;

/** Test and demonstration of {@link DefaultGraphTransformationEngine} */
public class DefaultGraphTransformationEngineTest {

  @Rule public Timeout timeout = Timeout.seconds(10000);
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private MutableGraph<Long> graph;
  private TrackingCache cache;
  private DepsAwareExecutor executor;

  @Before
  public void setUp() {
    executor = DefaultDepsAwareExecutor.from(new ForkJoinPool(4));

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

  @After
  public void cleanUp() {
    executor.shutdownNow();
  }

  /**
   * Demonstration of usage of {@link GraphEngineCache} with stats tracking used to verify behaviour
   * of the {@link DefaultGraphTransformationEngine}.
   */
  private final class TrackingCache implements GraphEngineCache<Long, Long> {

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
    DefaultGraphTransformationEngine<Long, Long> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), executor);
    assertEquals((Long) 3L, engine.computeUnchecked(3L));

    assertComputationIndexBecomesEmpty(engine.impl.computationIndex);
  }

  @Test
  public void requestOnRootCorrectValue() {
    ChildrenAdder transformer = new ChildrenAdder(graph);
    DefaultGraphTransformationEngine<Long, Long> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), executor);
    assertEquals((Long) 19L, engine.computeUnchecked(1L));

    assertComputationIndexBecomesEmpty(engine.impl.computationIndex);
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  @Test
  public void requestOnRootCorrectValueWithCustomExecutor() {
    ChildrenAdder transformer = new ChildrenAdder(graph);
    DefaultGraphTransformationEngine<Long, Long> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), executor);
    assertEquals((Long) 19L, engine.computeUnchecked(1L));
    assertComputationIndexBecomesEmpty(engine.impl.computationIndex);

    executor.shutdownNow();

    DefaultGraphTransformationEngine<Long, Long> engine2 =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), executor);
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

    DefaultGraphTransformationEngine<Long, Long> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), cache, executor);
    Long result = engine.computeUnchecked(3L);

    assertEquals((Long) 3L, result);

    transformer =
        new ChildrenAdder(graph) {
          @Override
          public Long transform(Long node, TransformationEnvironment<Long, Long> env) {
            Assert.fail("Did not expect call as cache should be used");
            return super.transform(node, env);
          }
        };

    engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), cache, executor);
    Long newResult = engine.computeUnchecked(3L);

    assertEquals(result, newResult);

    // all Futures should be removed
    assertComputationIndexBecomesEmpty(engine.impl.computationIndex);
    assertEquals(1, cache.getSize());
    assertEquals(1, cache.hitStats.get(3L).intValue());
  }

  @Test
  public void canReusePartiallyCachedResult() {
    ChildrenAdder transformer = new ChildrenAdder(graph);
    DefaultGraphTransformationEngine<Long, Long> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), cache, executor);

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
          public Long transform(Long node, TransformationEnvironment<Long, Long> env) {
            if (node == 5L || node == 4L || node == 3L) {
              Assert.fail("Did not expect call as cache should be used");
            }
            return super.transform(node, env);
          }
        };
    engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), cache, executor);

    // reuse the cache
    assertEquals((Long) 19L, engine.computeUnchecked(1L));

    // all Futures should be removed
    assertComputationIndexBecomesEmpty(engine.impl.computationIndex);
    assertEquals(5, cache.getSize());
    assertEquals(0, cache.hitStats.get(1L).intValue());
    assertEquals(0, cache.hitStats.get(2L).intValue());
    assertEquals(1, cache.hitStats.get(5L).intValue());
    assertEquals(1, cache.hitStats.get(3L).intValue());
    assertEquals(1, cache.hitStats.get(4L).intValue());
  }

  @Test
  public void handlesTransformerThatThrowsInTransform()
      throws ExecutionException, InterruptedException {

    Exception exception = new Exception();
    expectedException.expectCause(Matchers.sameInstance(exception));

    GraphTransformer<Long, Long> transformer =
        new GraphTransformer<Long, Long>() {
          @Override
          public Long transform(Long aLong, TransformationEnvironment<Long, Long> env)
              throws Exception {
            throw exception;
          }

          @Override
          public Set<Long> discoverDeps(Long aLong) {
            return ImmutableSet.of();
          }
        };

    DefaultGraphTransformationEngine<Long, Long> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), cache, executor);

    engine.compute(1L).get();
  }

  @Test
  public void handlesTransformerThatThrowsInDiscoverDeps()
      throws ExecutionException, InterruptedException {

    Exception exception = new Exception();
    expectedException.expectCause(Matchers.sameInstance(exception));

    GraphTransformer<Long, Long> transformer =
        new GraphTransformer<Long, Long>() {
          @Override
          public Long transform(Long aLong, TransformationEnvironment<Long, Long> env) {
            return 1L;
          }

          @Override
          public Set<Long> discoverDeps(Long aLong) throws Exception {
            throw exception;
          }
        };

    DefaultGraphTransformationEngine<Long, Long> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), cache, executor);

    engine.compute(1L).get();
  }

  /**
   * Asserts that the computationIndex of the {@link GraphTransformationEngine} eventually becomes
   * empty.
   *
   * @param computationIndex the computationIndex of the engine
   */
  private static void assertComputationIndexBecomesEmpty(
      ConcurrentHashMap<Long, ? extends DepsAwareTask<?, ?>> computationIndex) {
    // wait for all tasks to complete in the computation.
    // we can have situation where the computation was completed by using the cache.
    for (DepsAwareTask<?, ?> task : computationIndex.values()) {
      Futures.getUnchecked(task.getResultFuture());
    }

    assertEquals(0, computationIndex.size());
  }
}
