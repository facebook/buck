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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.pipeline.RulePipelineState;
import com.facebook.buck.core.rules.pipeline.SupportsPipelining;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import javax.annotation.Nullable;

/** Constructs build rule pipelines for a single build. */
public class BuildRulePipelinesRunner {
  private final ConcurrentHashMap<
          SupportsPipelining<? extends RulePipelineState>,
          BuildRulePipelineStage<? extends RulePipelineState>>
      rules = new ConcurrentHashMap<>();

  /** Gives the factory a way to construct a {@link RunnableWithFuture} to build the given rule. */
  public <T extends RulePipelineState> void addRule(
      SupportsPipelining<T> rule,
      Function<T, RunnableWithFuture<Optional<BuildResult>>> ruleStepRunnerFactory) {
    BuildRulePipelineStage<T> pipelineStage = getPipelineStage(rule);

    SupportsPipelining<T> previousRuleInPipeline = rule.getPreviousRuleInPipeline();
    if (previousRuleInPipeline != null) {
      Preconditions.checkState(
          previousRuleInPipeline.getPipelineStateFactory() == rule.getPipelineStateFactory(),
          "To help ensure that rules have pipeline-compatible rule keys, all rules in a pipeline must share a PipelineStateFactory instance.");
      SortedSet<BuildRule> currentDeps = rule.getBuildDeps();
      SortedSet<BuildRule> previousDeps = previousRuleInPipeline.getBuildDeps();
      Preconditions.checkState(
          currentDeps.contains(previousRuleInPipeline),
          "Each rule in a pipeline must depend on the previous rule in the pipeline.");
      SetView<BuildRule> extraDeps =
          Sets.difference(
              currentDeps, Sets.union(previousDeps, Collections.singleton(previousRuleInPipeline)));
      Preconditions.checkState(
          extraDeps.isEmpty(),
          "Each rule in a pipeline cannot depend on rules which are not also dependencies of the previous rule in the pipeline. "
              + "This ensures that each rule in the pipeline is ready to build as soon as the previous one completes. "
              + "%s has extra deps <%s>.",
          rule,
          Joiner.on(", ").join(extraDeps));
      getPipelineStage(previousRuleInPipeline).setNextStage(pipelineStage);
    }

    pipelineStage.setRuleStepRunnerFactory(ruleStepRunnerFactory);
  }

  /**
   * Removes a rule from pipeline eligibility. If a pipeline is already running the rule, waits for
   * it to complete before returning.
   */
  public void removeRule(SupportsPipelining<?> rule) {
    BuildRulePipelineStage<? extends RulePipelineState> pipelineStage = rules.remove(rule);
    if (pipelineStage != null && pipelineStage.pipelineBuilt()) {
      pipelineStage.cancelAndWait();
    }
  }

  public boolean runningPipelinesContainRule(SupportsPipelining<?> rule) {
    BuildRulePipelineStage<?> pipelineStage = getPipelineStage(rule);
    return pipelineStage.pipelineBuilt();
  }

  public ListenableFuture<Optional<BuildResult>> getFuture(SupportsPipelining<?> rule) {
    BuildRulePipelineStage<?> pipelineStage = getPipelineStage(rule);
    Preconditions.checkState(pipelineStage.pipelineBuilt());
    return pipelineStage.getFuture();
  }

  public <T extends RulePipelineState>
      ListenableFuture<Optional<BuildResult>> runPipelineStartingAt(
          BuildContext context, SupportsPipelining<T> rootRule, ExecutorService executor) {
    RunnableWithFuture<Optional<BuildResult>> runner = newPipelineRunner(context, rootRule);
    executor.execute(runner);
    return runner.getFuture();
  }

  private <T extends RulePipelineState> RunnableWithFuture<Optional<BuildResult>> newPipelineRunner(
      BuildContext context, SupportsPipelining<T> rootRule) {
    BuildRulePipelineStage<T> rootPipelineStage = getPipelineStage(rootRule);
    Preconditions.checkState(!rootPipelineStage.pipelineBuilt());

    BuildRulePipeline<T> pipeline =
        new BuildRulePipeline<>(
            rootPipelineStage,
            rootRule.getPipelineStateFactory().newInstance(context, rootRule.getBuildTarget()));
    return new RunnableWithFuture<Optional<BuildResult>>() {
      @Override
      public ListenableFuture<Optional<BuildResult>> getFuture() {
        // This runner is created when building the root rule locally. It gives back the future for
        // that root rule so that the dependents of that rule can begin compiling, while this
        // runner actually continues building the rest of the pipeline.
        return rootPipelineStage.getFuture();
      }

      @Override
      public void run() {
        pipeline.run();
      }
    };
  }

  private <T extends RulePipelineState> BuildRulePipelineStage<T> getPipelineStage(
      SupportsPipelining<T> rule) {
    @SuppressWarnings("unchecked")
    BuildRulePipelineStage<T> result =
        (BuildRulePipelineStage<T>)
            rules.computeIfAbsent(rule, key -> new BuildRulePipelineStage<T>());

    return result;
  }

  /**
   * Runs a list of rules one after another on the same thread, allowing each to access shared
   * state.
   */
  private static class BuildRulePipeline<T extends RulePipelineState> implements Runnable {
    @Nullable private T state;
    private final List<BuildRulePipelineStage<T>> rules = new ArrayList<>();

    public BuildRulePipeline(BuildRulePipelineStage<T> rootRule, T state) {
      this.state = state;

      buildPipeline(rootRule);
    }

    private void buildPipeline(BuildRulePipelineStage<T> firstStage) {
      BuildRulePipelineStage<T> walker = firstStage;

      while (walker != null) {
        rules.add(walker);
        walker.setPipeline(this);
        walker = walker.getNextStage();
      }
    }

    public T getState() {
      return Preconditions.checkNotNull(state);
    }

    @Override
    public void run() {
      try {
        Throwable error = null;
        for (BuildRulePipelineStage<T> rule : rules) {
          if (error == null) {
            rule.run();
            error = rule.getError();
          } else {
            // It doesn't really matter what error we use here -- we just want the future to
            // complete so that Buck doesn't hang. We use the real error in case it ever is shown
            // to the user (which does not happen as of the time of this comment, but for safety).
            rule.abort(error);
          }
          // If everything is working correctly, each rule in the pipeline should show itself
          // complete before we start the next one. Just a sanity check against weird behavior
          // creeping in.
          Preconditions.checkState(rule.getFuture().isDone() || rule.getFuture().isCancelled());
        }
      } finally {
        Preconditions.checkNotNull(state).close();
        state = null;
        rules.clear();
      }
    }
  }

  /**
   * Creates and runs the steps for a single build rule within a pipeline, cascading any failures to
   * rules later in the pipeline.
   */
  private static class BuildRulePipelineStage<T extends RulePipelineState>
      implements RunnableWithFuture<Optional<BuildResult>> {
    private final SettableFuture<Optional<BuildResult>> future = SettableFuture.create();
    @Nullable private BuildRulePipelineStage<T> nextStage;
    @Nullable private Throwable error = null;
    @Nullable private Function<T, RunnableWithFuture<Optional<BuildResult>>> ruleStepRunnerFactory;
    @Nullable private BuildRulePipeline<T> pipeline;
    @Nullable private RunnableWithFuture<Optional<BuildResult>> runner;

    @SuppressWarnings("CheckReturnValue")
    private BuildRulePipelineStage() {
      Futures.catching(future, Throwable.class, throwable -> error = throwable);
    }

    public void setRuleStepRunnerFactory(
        Function<T, RunnableWithFuture<Optional<BuildResult>>> ruleStepsFactory) {
      Preconditions.checkState(this.ruleStepRunnerFactory == null);
      this.ruleStepRunnerFactory = ruleStepsFactory;
    }

    public void setPipeline(BuildRulePipeline<T> pipeline) {
      Preconditions.checkState(this.pipeline == null);
      this.pipeline = pipeline;
    }

    public void setNextStage(BuildRulePipelineStage<T> nextStage) {
      Preconditions.checkState(this.nextStage == null);
      this.nextStage = nextStage;
    }

    @Nullable
    public BuildRulePipelineStage<T> getNextStage() {
      return nextStage;
    }

    public boolean pipelineBuilt() {
      return pipeline != null;
    }

    public void cancelAndWait() {
      // For now there's no cancel (cuz it's not hooked up at all), but we can at least wait
      try {
        getFuture().get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) { // NOPMD
        // Ignore; the future is hooked up elsewhere and that location will handle the exceptions
      }
    }

    @Nullable
    public Throwable getError() {
      return error;
    }

    @Override
    public SettableFuture<Optional<BuildResult>> getFuture() {
      return future;
    }

    @Override
    public void run() {
      Preconditions.checkNotNull(pipeline);
      Preconditions.checkNotNull(ruleStepRunnerFactory);

      runner = ruleStepRunnerFactory.apply(pipeline.getState());
      future.setFuture(runner.getFuture());
      runner.run();
    }

    public void abort(Throwable error) {
      future.setException(error);
    }
  }
}
