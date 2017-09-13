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

import com.facebook.buck.jvm.java.JavaBinary;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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
            (BuildRuleResolverFactory) SingleThreadedBuildRuleResolver::new,
            MoreExecutors.newDirectExecutorService(),
          },
          {
            MultiThreadedBuildRuleResolver.class,
            (BuildRuleResolverFactory)
                (graph, transformer) ->
                    new MultiThreadedBuildRuleResolver(pool, graph, transformer, null),
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
  public void testBuildAndAddToIndexRejectsDuplicateBuildTarget() throws Exception {
    BuildRuleResolver buildRuleResolver = buildRuleResolverFactory.create(TargetGraph.EMPTY);

    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    JavaLibraryBuilder.createBuilder(target).build(buildRuleResolver);

    // A BuildRuleResolver should allow only one entry for a BuildTarget.
    try {
      JavaLibraryBuilder.createBuilder(target).build(buildRuleResolver);
      fail("Should throw IllegalStateException.");
    } catch (IllegalStateException e) {
      assertEquals(
          "A build rule for this target has already been created: " + target, e.getMessage());
    }
  }

  @Test
  public void testRequireNonExistingBuildRule() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    TargetNode<?, ?> library = JavaLibraryBuilder.createBuilder(target).build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library);
    BuildRuleResolver resolver = buildRuleResolverFactory.create(targetGraph);

    BuildRule rule = resolver.requireRule(target);
    assertThat(rule, is(notNullValue()));
    assertThat(rule.getBuildTarget(), is(equalTo(target)));
  }

  @Test
  public void testRequireExistingBuildRule() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    JavaLibraryBuilder builder = JavaLibraryBuilder.createBuilder(target);
    TargetNode<?, ?> library = builder.build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library);
    BuildRuleResolver resolver = buildRuleResolverFactory.create(targetGraph);
    BuildRule existing = builder.build(resolver);

    assertThat(resolver.getRuleOptional(target).isPresent(), is(true));

    BuildRule rule = resolver.requireRule(target);
    assertThat(rule, is(notNullValue()));
    assertThat(rule.getBuildTarget(), is(equalTo(target)));
    assertThat(rule, is(equalTo(existing)));
  }

  @Test
  public void getRuleWithTypeMissingRule() throws Exception {
    BuildRuleResolver resolver = buildRuleResolverFactory.create(TargetGraph.EMPTY);
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(Matchers.containsString("could not be resolved"));
    resolver.getRuleWithType(BuildTargetFactory.newInstance("//:non-existent"), BuildRule.class);
  }

  @Test
  public void getRuleWithTypeWrongType() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    JavaLibraryBuilder builder = JavaLibraryBuilder.createBuilder(target);
    TargetNode<?, ?> library = builder.build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library);
    BuildRuleResolver resolver = buildRuleResolverFactory.create(targetGraph);
    builder.build(resolver);
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
}
