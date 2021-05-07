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

package com.facebook.buck.core.build.engine.impl;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.pipeline.CompilationDaemonStep;
import com.facebook.buck.core.rules.pipeline.RulePipelineState;
import com.facebook.buck.core.rules.pipeline.RulePipelineStateFactory;
import com.facebook.buck.core.rules.pipeline.StateHolder;
import com.facebook.buck.core.rules.pipeline.SupportsPipelining;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.AbstractMessage;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Constructs build rule pipelines for a single build. */
public class BuildRulePipelinesRunner<State extends RulePipelineState> {

  private final Map<SupportsPipelining<State>, BuildRulePipelineStage<State>> rules =
      new ConcurrentHashMap<>();

  /** Gives the factory a way to construct a {@link RunnableWithFuture} to build the given rule. */
  public void addRule(
      SupportsPipelining<State> rule,
      BiFunction<StateHolder<State>, Boolean, RunnableWithFuture<Optional<BuildResult>>>
          ruleStepRunnerFactory) {
    BuildRulePipelineStage<State> pipelineStage = getOrCreateStage(rule);

    SupportsPipelining<State> previousRuleInPipeline = rule.getPreviousRuleInPipeline();
    if (previousRuleInPipeline != null) {
      verifyRules(rule, previousRuleInPipeline);
      // set next stage for the previous stage to the current stage.
      getOrCreateStage(previousRuleInPipeline).setNextStage(pipelineStage);
    }
    pipelineStage.setRuleStepRunnerFactory(ruleStepRunnerFactory);
  }

  private void verifyRules(
      SupportsPipelining<?> rule, SupportsPipelining<?> previousRuleInPipeline) {
    Preconditions.checkState(
        previousRuleInPipeline.getPipelineStateFactory() == rule.getPipelineStateFactory(),
        "To help ensure that rules have pipeline-compatible rule keys, all rules in a pipeline must share a PipelineStateFactory instance.");
    Set<BuildRule> currentDeps = rule.getBuildDeps();
    Set<BuildRule> previousDeps = previousRuleInPipeline.getBuildDeps();
    Preconditions.checkState(
        currentDeps.contains(previousRuleInPipeline),
        "Each rule in a pipeline must depend on the previous rule in the pipeline.");
    Set<BuildRule> extraDeps =
        Sets.difference(
            currentDeps, Sets.union(previousDeps, ImmutableSet.of(previousRuleInPipeline)));
    Preconditions.checkState(
        extraDeps.isEmpty(),
        "Each rule in a pipeline cannot depend on rules which are not also dependencies of the previous rule in the pipeline. "
            + "This ensures that each rule in the pipeline is ready to build as soon as the previous one completes. "
            + "%s has extra deps <%s>.",
        rule,
        Joiner.on(", ").join(extraDeps));
  }

  /**
   * Removes a rule from pipeline eligibility. If a pipeline is already running the rule, waits for
   * it to complete before returning.
   */
  public void removeRule(SupportsPipelining<?> rule) {
    BuildRulePipelineStage<? extends RulePipelineState> pipelineStage = rules.remove(rule);
    if (pipelineStage != null && pipelineStage.isReady()) {
      pipelineStage.waitForResult();
    }
  }

  public boolean isPipelineReady(SupportsPipelining<?> rule) {
    BuildRulePipelineStage<?> pipelineStage = getExistingStage(rule);
    return pipelineStage.isReady();
  }

  /** Returns a future for pipelining rule */
  public ListenableFuture<Optional<BuildResult>> getFuture(SupportsPipelining<?> rule) {
    BuildRulePipelineStage<?> pipelineStage = getExistingStage(rule);
    Preconditions.checkState(pipelineStage.isReady());
    return pipelineStage.getFuture();
  }

  /** Start execution pipelining rule from a given {@code rootRule} */
  public ListenableFuture<Optional<BuildResult>> runPipelineStartingAt(
      BuildContext context, SupportsPipelining<?> rootRule, ExecutorService executor) {
    @SuppressWarnings("unchecked")
    SupportsPipelining<State> rule = (SupportsPipelining<State>) rootRule;
    RunnableWithFuture<Optional<BuildResult>> runner = newPipelineRunner(context, rule);
    executor.execute(runner);
    return runner.getFuture();
  }

  private RunnableWithFuture<Optional<BuildResult>> newPipelineRunner(
      BuildContext context, SupportsPipelining<State> rootRule) {
    BuildRulePipelineStage<State> rootPipelineStage = getOrCreateStage(rootRule);
    StateHolder<State> stateHolder = createStateHolder(context, rootRule);
    BuildRulePipeline<State> pipeline = new BuildRulePipeline<>(rootPipelineStage, stateHolder);
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

  private StateHolder<State> createStateHolder(
      BuildContext context, SupportsPipelining<State> rootRule) {
    RulePipelineStateFactory<State, ?> pipelineStateFactory = rootRule.getPipelineStateFactory();
    ProjectFilesystem projectFilesystem = rootRule.getProjectFilesystem();
    BuildTarget buildTarget = rootRule.getBuildTarget();

    AbstractMessage pipelineStateMessage =
        pipelineStateFactory.createPipelineStateMessage(context, projectFilesystem, buildTarget);

    if (rootRule.supportsCompilationDaemon()) {
      @SuppressWarnings("unchecked")
      Function<AbstractMessage, CompilationDaemonStep> compilationStepCreatorFunction =
          (Function<AbstractMessage, CompilationDaemonStep>)
              pipelineStateFactory.getCompilationStepCreatorFunction(context, projectFilesystem);
      return StateHolder.fromCompilationStep(
          compilationStepCreatorFunction.apply(pipelineStateMessage));
    }

    @SuppressWarnings("unchecked")
    Function<AbstractMessage, State> stateCreatorFunction =
        (Function<AbstractMessage, State>) pipelineStateFactory.getStateCreatorFunction();
    return StateHolder.fromState(stateCreatorFunction.apply(pipelineStateMessage));
  }

  private BuildRulePipelineStage<?> getExistingStage(SupportsPipelining<?> rule) {
    return Optional.ofNullable(rules.get(rule)).orElseThrow(IllegalStateException::new);
  }

  private BuildRulePipelineStage<State> getOrCreateStage(SupportsPipelining<State> rule) {
    return rules.computeIfAbsent(rule, ignore -> new BuildRulePipelineStage<>());
  }
}
