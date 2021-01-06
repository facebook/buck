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

package com.facebook.buck.core.rules.resolver.impl;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.config.registry.impl.ConfigurationRuleRegistryFactory;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBinary;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ActionGraphBuilderTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @FunctionalInterface
  private interface ActionGraphBuilderFactory {
    ActionGraphBuilder create(TargetGraph graph, TargetNodeToBuildRuleTransformer transformer);

    default ActionGraphBuilder create(TargetGraph graph) {
      return create(graph, new DefaultTargetNodeToBuildRuleTransformer());
    }
  }

  public ActionGraphBuilderFactory actionGraphBuilderFactory;
  public ExecutorService executorService;

  @Before
  public void setUp() {
    this.executorService = Executors.newFixedThreadPool(4);
    this.actionGraphBuilderFactory =
        (graph, transformer) ->
            new MultiThreadedActionGraphBuilder(
                MoreExecutors.listeningDecorator(this.executorService),
                graph,
                ConfigurationRuleRegistryFactory.createRegistry(TargetGraph.EMPTY),
                transformer,
                new TestCellBuilder().build().getRootCell().getCellProvider());
  }

  @After
  public void tearDown() {
    executorService.shutdownNow();
  }

  @Test
  public void testBuildAndAddToIndexRejectsDuplicateBuildTarget() {
    ActionGraphBuilder graphBuilder = actionGraphBuilderFactory.create(TargetGraph.EMPTY);

    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    graphBuilder.addToIndex(new FakeBuildRule(target));

    // A BuildRuleResolver should allow only one entry for a BuildTarget.
    try {
      graphBuilder.addToIndex(new FakeBuildRule(target));
      fail("Should throw IllegalStateException.");
    } catch (IllegalStateException e) {
      assertEquals(
          "A build rule for this target has already been created: " + target, e.getMessage());
    }
  }

  @Test
  public void testRequireNonExistingBuildRule() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    TargetNode<?> library = JavaLibraryBuilder.createBuilder(target).build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library);
    ActionGraphBuilder graphBuilder = actionGraphBuilderFactory.create(targetGraph);

    BuildRule rule = graphBuilder.requireRule(target);
    assertThat(rule, is(notNullValue()));
    assertThat(rule.getBuildTarget(), is(equalTo(target)));
  }

  @Test
  public void testRequireExistingBuildRule() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    JavaLibraryBuilder builder = JavaLibraryBuilder.createBuilder(target);
    TargetNode<?> library = builder.build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library);
    ActionGraphBuilder graphBuilder = actionGraphBuilderFactory.create(targetGraph);
    BuildRule existing = graphBuilder.requireRule(target);

    assertThat(graphBuilder.getRuleOptional(target).isPresent(), is(true));

    BuildRule rule = graphBuilder.requireRule(target);
    assertThat(rule, is(notNullValue()));
    assertThat(rule.getBuildTarget(), is(equalTo(target)));
    assertThat(rule, is(equalTo(existing)));
  }

  @Test
  public void getRuleWithTypeMissingRule() {
    BuildRuleResolver resolver = actionGraphBuilderFactory.create(TargetGraph.EMPTY);
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(Matchers.containsString("could not be resolved"));
    resolver.getRuleWithType(BuildTargetFactory.newInstance("//:non-existent"), BuildRule.class);
  }

  @Test
  public void getRuleWithTypeWrongType() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    JavaLibraryBuilder builder = JavaLibraryBuilder.createBuilder(target);
    TargetNode<?> library = builder.build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library);
    ActionGraphBuilder graphBuilder = actionGraphBuilderFactory.create(targetGraph);
    graphBuilder.requireRule(target);
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(Matchers.containsString("not of expected type"));
    graphBuilder.getRuleWithType(BuildTargetFactory.newInstance("//foo:bar"), JavaBinary.class);
  }

  @Test
  public void computeIfAbsentComputesOnlyIfAbsent() throws Exception {
    ActionGraphBuilder graphBuilder = actionGraphBuilderFactory.create(TargetGraph.EMPTY);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    AtomicInteger supplierInvoked = new AtomicInteger(0);
    BuildRule buildRule =
        new NoopBuildRuleWithDeclaredAndExtraDeps(
            target, new FakeProjectFilesystem(), TestBuildRuleParams.create());
    BuildRule returnedBuildRule =
        executorService
            .submit(
                () ->
                    graphBuilder.computeIfAbsent(
                        target,
                        passedTarget -> {
                          assertEquals(passedTarget, target);
                          supplierInvoked.incrementAndGet();
                          return buildRule;
                        }))
            .get();
    assertEquals("supplier was called once", supplierInvoked.get(), 1);
    assertSame("returned the same build rule that was generated", returnedBuildRule, buildRule);
    assertSame("the rule can be retrieved again", graphBuilder.getRule(target), buildRule);
    returnedBuildRule =
        executorService
            .submit(
                () ->
                    graphBuilder.computeIfAbsent(
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
    TargetNode<?> library = JavaLibraryBuilder.createBuilder(target).build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library);
    ActionGraphBuilder graphBuilder =
        actionGraphBuilderFactory.create(
            targetGraph,
            new TargetNodeToBuildRuleTransformer() {
              @Override
              public <T extends BuildRuleArg> BuildRule transform(
                  ToolchainProvider toolchainProvider,
                  TargetGraph targetGraph,
                  ConfigurationRuleRegistry configurationRuleRegistry,
                  ActionGraphBuilder graphBuilder,
                  TargetNode<T> targetNode,
                  ProviderInfoCollection providerInfoCollection,
                  CellPathResolver cellPathResolver) {
                Assert.assertFalse(graphBuilder.getRuleOptional(target).isPresent());
                return graphBuilder.computeIfAbsent(target, FakeBuildRule::new);
              }
            });
    graphBuilder.requireRule(target);
  }

  @Test
  public void accessingTargetBeingBuildInDifferentThreadsWaitsForItsCompletion() throws Exception {
    BuildTarget target1 = BuildTargetFactory.newInstance("//foo:bar1");
    TargetNode<?> library1 = JavaLibraryBuilder.createBuilder(target1).build();

    BuildTarget target2 = BuildTargetFactory.newInstance("//foo:bar2");
    TargetNode<?> library2 = JavaLibraryBuilder.createBuilder(target2).build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library1, library2);

    CountDownLatch jobsStarted = new CountDownLatch(2);
    CountDownLatch target1Finish = new CountDownLatch(1);

    ConcurrentHashMap<BuildTarget, Boolean> transformCalls = new ConcurrentHashMap<>();

    ActionGraphBuilder graphBuilder =
        actionGraphBuilderFactory.create(
            targetGraph,
            new TargetNodeToBuildRuleTransformer() {
              @Override
              public <T extends BuildRuleArg> BuildRule transform(
                  ToolchainProvider toolchainProvider,
                  TargetGraph targetGraph,
                  ConfigurationRuleRegistry configurationRuleRegistry,
                  ActionGraphBuilder graphBuilder,
                  TargetNode<T> targetNode,
                  ProviderInfoCollection providerInfoCollection,
                  CellPathResolver cellPathResolver) {
                Boolean existing = transformCalls.put(targetNode.getBuildTarget(), true);
                assertNull("Should only be called once for each build target", existing);
                try {
                  if (targetNode.getBuildTarget().equals(target1)) {
                    jobsStarted.countDown();
                    target1Finish.await();
                  } else if (targetNode.getBuildTarget().equals(target2)) {
                    jobsStarted.countDown();
                    // There's a race condition here where target1 is allowed to proceed before we
                    // start waiting. This will result in a false-negative if this test would fail.
                    graphBuilder.requireRule(target1);
                  } else {
                    throw new AssertionError("only 2 targets should be specified in this test.");
                  }
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
                return new FakeBuildRule(targetNode.getBuildTarget());
              }
            });

    Future<BuildRule> first = executorService.submit(() -> graphBuilder.requireRule(target1));
    Future<BuildRule> second = executorService.submit(() -> graphBuilder.requireRule(target2));
    jobsStarted.await(); // wait for both jobs to start.
    Thread.sleep(10); // Insert a small delay to reduce the chances of race condition.
    target1Finish.countDown();
    first.get();
    second.get();

    assertEquals("transform() should be called exactly twice", 2, transformCalls.size());
  }
}
