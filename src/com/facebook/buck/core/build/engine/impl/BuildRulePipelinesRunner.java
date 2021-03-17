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
import com.facebook.buck.core.rules.pipeline.RulePipelineState;
import com.facebook.buck.core.rules.pipeline.RulePipelineStateFactory;
import com.facebook.buck.core.rules.pipeline.SupportsPipelining;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/** Constructs build rule pipelines for a single build. */
public class BuildRulePipelinesRunner<T extends RulePipelineState> {

  private final Map<SupportsPipelining<T>, BuildRulePipelineStage<T>> rules =
      new ConcurrentHashMap<>();

  /** Gives the factory a way to construct a {@link RunnableWithFuture} to build the given rule. */
  public void addRule(
      SupportsPipelining<T> rule,
      Function<T, RunnableWithFuture<Optional<BuildResult>>> ruleStepRunnerFactory) {
    BuildRulePipelineStage<T> pipelineStage = getOrCreateStage(rule);

    SupportsPipelining<T> previousRuleInPipeline = rule.getPreviousRuleInPipeline();
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
    if (pipelineStage != null && pipelineStage.pipelineIsReady()) {
      pipelineStage.waitForResult();
    }
  }

  public boolean isPipelineReady(SupportsPipelining<?> rule) {
    BuildRulePipelineStage<?> pipelineStage = getExistingStage(rule);
    return pipelineStage.pipelineIsReady();
  }

  public ListenableFuture<Optional<BuildResult>> getFuture(SupportsPipelining<?> rule) {
    BuildRulePipelineStage<?> pipelineStage = getExistingStage(rule);
    Preconditions.checkState(pipelineStage.pipelineIsReady());
    return pipelineStage.getFuture();
  }

  /** Start execution pipelining rule from a given {@code rootRule} */
  public ListenableFuture<Optional<BuildResult>> runPipelineStartingAt(
      BuildContext context, SupportsPipelining<?> rootRule, ExecutorService executor) {
    @SuppressWarnings("unchecked")
    SupportsPipelining<T> rule = (SupportsPipelining<T>) rootRule;
    RunnableWithFuture<Optional<BuildResult>> runner = newPipelineRunner(context, rule);
    executor.execute(runner);
    return runner.getFuture();
  }

  private RunnableWithFuture<Optional<BuildResult>> newPipelineRunner(
      BuildContext context, SupportsPipelining<T> rootRule) {
    BuildRulePipelineStage<T> rootPipelineStage = getOrCreateStage(rootRule);

    RulePipelineStateFactory<T> pipelineStateFactory = rootRule.getPipelineStateFactory();
    ProjectFilesystem projectFilesystem = rootRule.getProjectFilesystem();
    BuildTarget buildTarget = rootRule.getBuildTarget();
    BuildRulePipeline<T> pipeline =
        new BuildRulePipeline<>(
            rootPipelineStage,
            pipelineStateFactory.createPipelineState(context, projectFilesystem, buildTarget));
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

  private BuildRulePipelineStage<?> getExistingStage(SupportsPipelining<?> rule) {
    return Optional.ofNullable(rules.get(rule)).orElseThrow(IllegalStateException::new);
  }

  private BuildRulePipelineStage<T> getOrCreateStage(SupportsPipelining<T> rule) {
    return rules.computeIfAbsent(rule, BuildRulePipelineStage::new);
  }
}
