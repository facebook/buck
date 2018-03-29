/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNoException;

import com.facebook.buck.jvm.java.JavaBinary;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class BuildRuleResolverTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @FunctionalInterface
  private interface BuildRuleResolverFactory {
    BuildRuleResolver create(TargetGraph graph, TargetNodeToBuildRuleTransformer transformer);

    default BuildRuleResolver create(TargetGraph graph) {
      return create(graph, new DefaultTargetNodeToBuildRuleTransformer());
    }
  }

  private static ForkJoinPool pool = new ForkJoinPool(4);

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return Arrays.asList(
        new Object[][] {
          {
            SingleThreadedBuildRuleResolver.class,
            (BuildRuleResolverFactory)
                (graph, transformer) ->
                    new SingleThreadedBuildRuleResolver(
                        graph, transformer, new TestCellBuilder().build().getCellProvider()),
            MoreExecutors.newDirectExecutorService(),
          },
          {
            MultiThreadedBuildRuleResolver.class,
            (BuildRuleResolverFactory)
                (graph, transformer) ->
                    new MultiThreadedBuildRuleResolver(
                        pool, graph, transformer, new TestCellBuilder().build().getCellProvider()),
            pool,
          },
        });
  }

  @Parameterized.Parameter(0)
  public Class<? extends BuildRuleResolver> classUnderTest;

  @Parameterized.Parameter(1)
  public BuildRuleResolverFactory buildRuleResolverFactory;

  @Parameterized.Parameter(2)
  public ExecutorService executorService;

  @AfterClass
  public static void afterClass() {
    pool.shutdownNow();
  }

  @Test
  public void testBuildAndAddToIndexRejectsDuplicateBuildTarget() {
    BuildRuleResolver buildRuleResolver = buildRuleResolverFactory.create(TargetGraph.EMPTY);

    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    buildRuleResolver.addToIndex(new FakeBuildRule(target));

    // A BuildRuleResolver should allow only one entry for a BuildTarget.
    try {
      buildRuleResolver.addToIndex(new FakeBuildRule(target));
      fail("Should throw IllegalStateException.");
    } catch (IllegalStateException e) {
      assertEquals(
          "A build rule for this target has already been created: " + target, e.getMessage());
    }
  }

  @Test
  public void testRequireNonExistingBuildRule() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    TargetNode<?, ?> library = JavaLibraryBuilder.createBuilder(target).build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library);
    BuildRuleResolver resolver = buildRuleResolverFactory.create(targetGraph);

    BuildRule rule = resolver.requireRule(target);
    assertThat(rule, is(notNullValue()));
    assertThat(rule.getBuildTarget(), is(equalTo(target)));
  }

  @Test
  public void testRequireExistingBuildRule() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    JavaLibraryBuilder builder = JavaLibraryBuilder.createBuilder(target);
    TargetNode<?, ?> library = builder.build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library);
    BuildRuleResolver resolver = buildRuleResolverFactory.create(targetGraph);
    BuildRule existing = resolver.requireRule(target);

    assertThat(resolver.getRuleOptional(target).isPresent(), is(true));

    BuildRule rule = resolver.requireRule(target);
    assertThat(rule, is(notNullValue()));
    assertThat(rule.getBuildTarget(), is(equalTo(target)));
    assertThat(rule, is(equalTo(existing)));
  }

  @Test
  public void getRuleWithTypeMissingRule() {
    BuildRuleResolver resolver = buildRuleResolverFactory.create(TargetGraph.EMPTY);
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(Matchers.containsString("could not be resolved"));
    resolver.getRuleWithType(BuildTargetFactory.newInstance("//:non-existent"), BuildRule.class);
  }

  @Test
  public void getRuleWithTypeWrongType() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    JavaLibraryBuilder builder = JavaLibraryBuilder.createBuilder(target);
    TargetNode<?, ?> library = builder.build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library);
    BuildRuleResolver resolver = buildRuleResolverFactory.create(targetGraph);
    resolver.requireRule(target);
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(Matchers.containsString("not of expected type"));
    resolver.getRuleWithType(BuildTargetFactory.newInstance("//foo:bar"), JavaBinary.class);
  }

  @Test
  public void computeIfAbsentComputesOnlyIfAbsent() throws Exception {
    BuildRuleResolver resolver = buildRuleResolverFactory.create(TargetGraph.EMPTY);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    AtomicInteger supplierInvoked = new AtomicInteger(0);
    BuildRule buildRule =
        new NoopBuildRuleWithDeclaredAndExtraDeps(
            target, new FakeProjectFilesystem(), TestBuildRuleParams.create());
    BuildRule returnedBuildRule =
        executorService
            .submit(
                () ->
                    resolver.computeIfAbsent(
                        target,
                        passedTarget -> {
                          assertEquals(passedTarget, target);
                          supplierInvoked.incrementAndGet();
                          return buildRule;
                        }))
            .get();
    assertEquals("supplier was called once", supplierInvoked.get(), 1);
    assertSame("returned the same build rule that was generated", returnedBuildRule, buildRule);
    assertSame("the rule can be retrieved again", resolver.getRule(target), buildRule);
    returnedBuildRule =
        executorService
            .submit(
                () ->
                    resolver.computeIfAbsent(
                        target,
                        passedTarget -> {
                          assertEquals(passedTarget, target);
                          supplierInvoked.incrementAndGet();
                          return buildRule;
                        }))
            .get();
    assertEquals("supplier is not called again", supplierInvoked.get(), 1);
    assertSame("recorded rule is still returned", returnedBuildRule, buildRule);
  }

  @Test
  public void accessingSameTargetWithinSameThreadActsAsIfItDoesNotExist() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    TargetNode<?, ?> library = JavaLibraryBuilder.createBuilder(target).build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library);
    BuildRuleResolver resolver =
        buildRuleResolverFactory.create(
            targetGraph,
            new TargetNodeToBuildRuleTransformer() {
              @Override
              public <T, U extends Description<T>> BuildRule transform(
                  CellProvider cellProvider,
                  TargetGraph targetGraph,
                  BuildRuleResolver ruleResolver,
                  TargetNode<T, U> targetNode) {
                Assert.assertFalse(ruleResolver.getRuleOptional(target).isPresent());
                return ruleResolver.computeIfAbsent(target, FakeBuildRule::new);
              }
            });
    resolver.requireRule(target);
  }

  @Test
  public void accessingTargetBeingBuildInDifferentThreadsWaitsForItsCompletion() throws Exception {
    Assume.assumeTrue(classUnderTest == MultiThreadedBuildRuleResolver.class);

    BuildTarget target1 = BuildTargetFactory.newInstance("//foo:bar1");
    TargetNode<?, ?> library1 = JavaLibraryBuilder.createBuilder(target1).build();

    BuildTarget target2 = BuildTargetFactory.newInstance("//foo:bar2");
    TargetNode<?, ?> library2 = JavaLibraryBuilder.createBuilder(target2).build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library1, library2);

    CountDownLatch jobsStarted = new CountDownLatch(2);
    CountDownLatch target1Finish = new CountDownLatch(1);

    ConcurrentHashMap<BuildTarget, Boolean> transformCalls = new ConcurrentHashMap<>();

    BuildRuleResolver resolver =
        buildRuleResolverFactory.create(
            targetGraph,
            new TargetNodeToBuildRuleTransformer() {
              @Override
              public <T, U extends Description<T>> BuildRule transform(
                  CellProvider cellProvider,
                  TargetGraph targetGraph,
                  BuildRuleResolver ruleResolver,
                  TargetNode<T, U> targetNode) {
                Boolean existing = transformCalls.put(targetNode.getBuildTarget(), true);
                assertEquals("Should only be called once for each build target", null, existing);
                try {
                  if (targetNode.getBuildTarget().equals(target1)) {
                    jobsStarted.countDown();
                    target1Finish.await();
                  } else if (targetNode.getBuildTarget().equals(target2)) {
                    jobsStarted.countDown();
                    // There's a race condition here where target1 is allowed to proceed before we
                    // start waiting. This will result in a false-negative if this test would fail.
                    ruleResolver.requireRule(target1);
                  } else {
                    throw new AssertionError("only 2 targets should be specified in this test.");
                  }
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
                return new FakeBuildRule(targetNode.getBuildTarget());
              }
            });

    Future<BuildRule> first = executorService.submit(() -> resolver.requireRule(target1));
    Future<BuildRule> second = executorService.submit(() -> resolver.requireRule(target2));
    jobsStarted.await(); // wait for both jobs to start.
    Thread.sleep(10); // Insert a small delay to reduce the chances of race condition.
    target1Finish.countDown();
    first.get();
    second.get();

    assertEquals("transform() should be called exactly twice", 2, transformCalls.size());
  }

  @Test(timeout = 5000)
  public void deadLockOnDependencyTest() throws ExecutionException, InterruptedException {
    Assume.assumeTrue(classUnderTest == MultiThreadedBuildRuleResolver.class);

    /**
     * create a graph of the following
     *
     * <pre>foo:bar0 foo:bar1   foo:bar2   foo:bar3
     *          \        \         /         /
     *                    foo:bar4
     * </pre>
     *
     * <p>such that when the ThreadPool has all 4 threads executing bar0,...bar3, we block waiting
     * completion of bar4, but have no additional threads for bar4
     *
     * <p>proper behaviour is to use one of the threads blocked on bar0,...bar3 to execute bar4.
     */
    BuildTarget target4 = BuildTargetFactory.newInstance("//foo:bar4");
    TargetNode<?, ?> library4 = JavaLibraryBuilder.createBuilder(target4).build();

    BuildTarget target3 = BuildTargetFactory.newInstance("//foo:bar3");
    TargetNode<?, ?> library3 =
        JavaLibraryBuilder.createBuilder(target3).addExportedDep(target4).build();

    BuildTarget target2 = BuildTargetFactory.newInstance("//foo:bar2");
    TargetNode<?, ?> library2 =
        JavaLibraryBuilder.createBuilder(target2).addExportedDep(target4).build();

    BuildTarget target1 = BuildTargetFactory.newInstance("//foo:bar1");
    TargetNode<?, ?> library1 =
        JavaLibraryBuilder.createBuilder(target1).addExportedDep(target4).build();

    BuildTarget target0 = BuildTargetFactory.newInstance("//foo:bar0");
    TargetNode<?, ?> library0 =
        JavaLibraryBuilder.createBuilder(target0).addExportedDep(target4).build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(library0, library1, library2, library3, library4);

    // Ensure the race condition occurs, where we have all of foo:bar0...foo:bar3
    // running, but not called requireRule(foo:bar4) yet.
    CountDownLatch jobsStarted = new CountDownLatch(4);

    // run this with ThreadLimited FJP like our actual parallel implementation
    ForkJoinPool forkJoinPool = MostExecutors.forkJoinPoolWithThreadLimit(4, 0);
    try {
      BuildRuleResolver resolver =
          new MultiThreadedBuildRuleResolver(
              forkJoinPool,
              targetGraph,
              new TargetNodeToBuildRuleTransformer() {
                @Override
                public <T, U extends Description<T>> BuildRule transform(
                    CellProvider cellProvider,
                    TargetGraph targetGraph,
                    BuildRuleResolver ruleResolver,
                    TargetNode<T, U> targetNode) {

                  jobsStarted.countDown();

                  if (!targetNode.getExtraDeps().isEmpty()) {
                    try {
                      // this waits until all of bar0,...bar3 has executed up to this point before
                      // requiring bar4, to create the situation where all 4 threads in ForkJoinPool
                      // are blocked waiting for one dependency that has yet to be executed.
                      jobsStarted.await();
                    } catch (InterruptedException e) {
                      // stop the test if we've been interrupted
                      assumeNoException(e);
                    }

                    targetNode.getExtraDeps().stream().forEach(ruleResolver::requireRule);
                  }
                  return new FakeBuildRule(targetNode.getBuildTarget());
                }
              },
              new TestCellBuilder().build().getCellProvider());

      // mimic our actual parallel action graph construction, in which we call requireRule with
      // threads
      // outside the ForkJoinPool, which will then fork tasks to the ForkJoinPool.
      CompletableFuture first = CompletableFuture.runAsync(() -> resolver.requireRule(target0));
      CompletableFuture second = CompletableFuture.runAsync(() -> resolver.requireRule(target1));
      CompletableFuture third = CompletableFuture.runAsync(() -> resolver.requireRule(target2));
      CompletableFuture fourth = CompletableFuture.runAsync(() -> resolver.requireRule(target3));

      CompletableFuture.allOf(first, second, third, fourth).get();
    } finally {
      forkJoinPool.shutdownNow();
    }
  }
}
