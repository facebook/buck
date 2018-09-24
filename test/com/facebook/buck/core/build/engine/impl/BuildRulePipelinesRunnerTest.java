/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.core.build.engine.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.pipeline.RulePipelineState;
import com.facebook.buck.core.rules.pipeline.RulePipelineStateFactory;
import com.facebook.buck.core.rules.pipeline.SupportsPipelining;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.ExternalResource;

public class BuildRulePipelinesRunnerTest {
  @Rule public PipelineTester tester = new PipelineTester();
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testPipelineRunsAllRules() throws Exception {
    tester
        .setNumRules(4)
        .startPipelineAtRule(0)
        .allowRuleToFinish(0)
        .allowRuleToFinish(1)
        .allowRuleToFinish(2)
        .allowRuleToFinish(3)
        .waitForEntirePipelineToFinish()
        .assertRuleRan(0)
        .assertRuleRan(1)
        .assertRuleRan(2)
        .assertRuleRan(3);
  }

  @Test
  public void testCanStartPipelineInMiddle() throws Exception {
    tester
        .setNumRules(4)
        .startPipelineAtRule(1)
        .allowRuleToFinish(
            0) // So the test doesn't time out if the pipeline starts in the wrong place
        .allowRuleToFinish(1)
        .allowRuleToFinish(2)
        .allowRuleToFinish(3)
        .waitForEntirePipelineToFinish()
        .assertRuleDidNotRun(0)
        .assertRuleRan(1)
        .assertRuleRan(2)
        .assertRuleRan(3);
  }

  @Test
  public void testNotPipelined() {
    tester.setNumRules(4);

    assertFalse(tester.isRuleRunning(1));
  }

  @Test
  public void testRuleFuturesCorrect() throws Exception {
    tester.setNumRules(4).startPipelineAtRule(0);

    assertFalse(tester.getFutureForRule(0).isDone());
    assertFalse(tester.getFutureForRule(1).isDone());
    assertFalse(tester.getFutureForRule(2).isDone());
    assertFalse(tester.getFutureForRule(3).isDone());

    tester.allowRuleToFinish(0).waitForRuleToStart(1);

    assertTrue(tester.getFutureForRule(0).isDone());
    assertFalse(tester.getFutureForRule(1).isDone());
    assertFalse(tester.getFutureForRule(2).isDone());
    assertFalse(tester.getFutureForRule(3).isDone());

    tester.allowRuleToFinish(1).waitForRuleToStart(2);

    assertTrue(tester.getFutureForRule(0).isDone());
    assertTrue(tester.getFutureForRule(1).isDone());
    assertFalse(tester.getFutureForRule(2).isDone());
    assertFalse(tester.getFutureForRule(3).isDone());

    tester.allowRuleToFinish(2).waitForRuleToStart(3);

    assertTrue(tester.getFutureForRule(0).isDone());
    assertTrue(tester.getFutureForRule(1).isDone());
    assertTrue(tester.getFutureForRule(2).isDone());
    assertFalse(tester.getFutureForRule(3).isDone());

    tester.allowRuleToFinish(3).waitForEntirePipelineToFinish();

    assertTrue(tester.getFutureForRule(0).isDone());
    assertTrue(tester.getFutureForRule(1).isDone());
    assertTrue(tester.getFutureForRule(2).isDone());
    assertTrue(tester.getFutureForRule(3).isDone());
  }

  @Test
  public void testPipelineRunnerSetsFutureAfterFirstRule() throws Exception {
    tester.setNumRules(4).startPipelineAtRule(0).allowRuleToFinish(0).waitForRuleToStart(1);

    assertTrue(tester.getPipelineRunnableFuture().isDone());
    assertFalse(tester.getFutureForRule(1).isDone());
  }

  @Test
  public void testFailurePropagatesToLaterRules() throws Exception {
    tester
        .setNumRules(4)
        .startPipelineAtRule(0)
        .allowRuleToFinish(0)
        .failRule(1, new Throwable())
        .waitForEntirePipelineToFinish()
        .assertRuleDidNotRun(2)
        .assertRuleDidNotRun(3);

    assertTrue(tester.getFutureForRule(2).isDone());
    assertTrue(tester.getFutureForRule(3).isDone());
  }

  @Test
  public void testSinglePipelineState() throws Exception {
    tester
        .setNumRules(4)
        .startPipelineAtRule(0)
        .allowRuleToFinish(0)
        .allowRuleToFinish(1)
        .allowRuleToFinish(2)
        .allowRuleToFinish(3)
        .waitForEntirePipelineToFinish();

    TestPipelineState state = tester.getPipelineStateForRule(0);

    assertSame(state, tester.getPipelineStateForRule(1));
    assertSame(state, tester.getPipelineStateForRule(2));
    assertSame(state, tester.getPipelineStateForRule(3));
  }

  @Test
  public void testPipelineStateClosedAtEnd() throws Exception {
    tester
        .setNumRules(4)
        .startPipelineAtRule(0)
        .allowRuleToFinish(0)
        .allowRuleToFinish(1)
        .allowRuleToFinish(2)
        .allowRuleToFinish(3)
        .waitForEntirePipelineToFinish();

    assertTrue(tester.getPipelineStateForRule(0).isClosed());
  }

  @Test
  public void testDoesntDependOnPrevious() {
    BuildRulePipelinesRunner runner = new BuildRulePipelinesRunner();

    TestPipelineRule first = new TestPipelineRule("//pipeline:1", null);
    runner.addRule(first, first::newRunner);
    TestPipelineRule second = new TestPipelineRule("//pipeline:1", first, new BuildRule[] {});
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage(
        "Each rule in a pipeline must depend on the previous rule in the pipeline.");
    runner.addRule(second, second::newRunner);
  }

  @Test
  public void testIncludesExtraDeps() {
    BuildRulePipelinesRunner runner = new BuildRulePipelinesRunner();

    TestPipelineRule first = new TestPipelineRule("//pipeline:1", null);
    runner.addRule(first, first::newRunner);
    FakeBuildRule other = new FakeBuildRule("//other:1");
    TestPipelineRule second = new TestPipelineRule("//pipeline:1", first, first, other);
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage(
        Matchers.containsString(
            "Each rule in a pipeline cannot depend on rules which are not also"));
    thrown.expectMessage(Matchers.containsString("//pipeline:1 has extra deps <//other:1>."));
    runner.addRule(second, second::newRunner);
  }

  private static class PipelineTester extends ExternalResource {
    private final BuildRulePipelinesRunner runner = new BuildRulePipelinesRunner();
    private final List<TestPipelineRule> rules = new ArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private Future<?> pipelineRunnableFuture;

    public PipelineTester setNumRules(int numRules) {
      TestPipelineRule previousRule = null;
      for (int i = 0; i < numRules; i++) {
        TestPipelineRule newRule =
            new TestPipelineRule(String.format("//:rule%d", i), previousRule);
        rules.add(newRule);

        runner.addRule(newRule, newRule::newRunner);

        previousRule = newRule;
      }

      return this;
    }

    public PipelineTester allowRuleToFinish(int ruleNum) {
      rules.get(ruleNum).allowToFinish();
      return this;
    }

    public PipelineTester failRule(int ruleNum, Throwable error) {
      rules.get(ruleNum).causeToFail(error);
      return this;
    }

    public PipelineTester waitForRuleToStart(int ruleNum) throws InterruptedException {
      rules.get(ruleNum).waitForStart();
      return this;
    }

    public PipelineTester startPipelineAtRule(int ruleNum) {
      pipelineRunnableFuture =
          runner.runPipelineStartingAt(FakeBuildContext.NOOP_CONTEXT, rules.get(ruleNum), executor);
      return this;
    }

    public PipelineTester waitForEntirePipelineToFinish() throws InterruptedException {
      executor.shutdown();
      executor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
      return this;
    }

    public PipelineTester assertRuleRan(int ruleNum) {
      assertTrue(rules.get(ruleNum).didRun());
      return this;
    }

    public PipelineTester assertRuleDidNotRun(int ruleNum) {
      assertFalse(rules.get(ruleNum).didRun());
      return this;
    }

    public Future<?> getPipelineRunnableFuture() {
      return pipelineRunnableFuture;
    }

    public ListenableFuture<Optional<BuildResult>> getFutureForRule(int ruleNum) {
      assertTrue(isRuleRunning(ruleNum));
      return runner.getFuture(rules.get(ruleNum));
    }

    public boolean isRuleRunning(int ruleNum) {
      return runner.runningPipelinesContainRule(rules.get(ruleNum));
    }

    public TestPipelineState getPipelineStateForRule(int ruleNum) {
      return rules.get(ruleNum).pipeline;
    }

    @Override
    protected void after() {
      super.after();
      rules.forEach(TestPipelineRule::allowToFinish);
      executor.shutdown();
    }
  }

  private static class TestPipelineState implements RulePipelineState {
    private boolean isClosed;

    @SuppressWarnings("unused")
    public TestPipelineState(
        BuildContext buildContext, ProjectFilesystem filesystem, BuildTarget buildTarget) {}

    @Override
    public void close() {
      isClosed = true;
    }

    public boolean isClosed() {
      return isClosed;
    }
  }

  private static class TestPipelineRule extends FakeBuildRule
      implements SupportsPipelining<TestPipelineState> {
    private final TestPipelineRule previous;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch mayFinish = new CountDownLatch(1);
    private final SettableFuture<Optional<BuildResult>> future = SettableFuture.create();
    private boolean didRun = false;
    private Throwable error = null;

    private TestPipelineState pipeline;

    public TestPipelineRule(String target, TestPipelineRule previous) {
      this(target, previous, getDeps(previous));
    }

    public TestPipelineRule(String target, TestPipelineRule previous, BuildRule... deps) {
      super(target, deps);
      this.previous = previous;
    }

    private static BuildRule[] getDeps(TestPipelineRule previous) {
      if (previous == null) {
        return new BuildRule[0];
      } else {
        SortedSet<BuildRule> previousDeps = previous.getBuildDeps();
        List<BuildRule> rules = new ArrayList<>(previousDeps.size() + 1);
        rules.addAll(previousDeps);
        rules.add(previous);
        return rules.toArray(new BuildRule[0]);
      }
    }

    public void causeToFail(Throwable error) {
      this.error = error;
      mayFinish.countDown();
    }

    public void allowToFinish() {
      mayFinish.countDown();
    }

    public void waitForStart() throws InterruptedException {
      started.await();
    }

    public boolean didRun() {
      return didRun;
    }

    @Override
    public boolean useRulePipelining() {
      return true;
    }

    @Nullable
    @Override
    public SupportsPipelining<TestPipelineState> getPreviousRuleInPipeline() {
      return previous;
    }

    @Override
    public ImmutableList<? extends Step> getPipelinedBuildSteps(
        BuildContext context, BuildableContext buildableContext, TestPipelineState state) {
      return ImmutableList.of();
    }

    public RunnableWithFuture<Optional<BuildResult>> newRunner(TestPipelineState pipeline) {
      assertNull(this.pipeline);
      this.pipeline = pipeline;
      return new RunnableWithFuture<Optional<BuildResult>>() {
        @Override
        public ListenableFuture<Optional<BuildResult>> getFuture() {
          return future;
        }

        @Override
        public void run() {
          started.countDown();
          try {
            didRun = true;
            mayFinish.await();
          } catch (InterruptedException e) {
            throw new AssertionError();
          }
          if (error != null) {
            future.setException(error);
          } else {
            future.set(
                Optional.of(
                    BuildResult.success(
                        TestPipelineRule.this,
                        BuildRuleSuccessType.BUILT_LOCALLY,
                        CacheResult.ignored())));
          }
        }
      };
    }

    @Override
    public RulePipelineStateFactory<TestPipelineState> getPipelineStateFactory() {
      return TestPipelineState::new;
    }
  }
}
