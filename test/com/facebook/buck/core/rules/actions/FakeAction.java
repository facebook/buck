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

package com.facebook.buck.core.rules.actions;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.util.Objects;

public class FakeAction extends AbstractAction {

  @AddToRuleKey private final HashableWrapper hasheableWrapper;
  @AddToRuleKey private final ImmutableSortedSet<Artifact> srcs;
  @AddToRuleKey private final ImmutableSortedSet<Artifact> deps;

  public FakeAction(
      ActionRegistry actionRegistry,
      ImmutableSortedSet<Artifact> srcs,
      ImmutableSortedSet<Artifact> deps,
      ImmutableSortedSet<Artifact> outputs,
      FakeActionExecuteLambda executeFunction) {
    super(
        actionRegistry,
        ImmutableSortedSet.copyOf(Sets.union(srcs, deps)),
        outputs.stream()
            .map(Artifact::asOutputArtifact)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
        "fake-action");
    this.hasheableWrapper = new HashableWrapper(executeFunction);
    this.srcs = srcs;
    this.deps = deps;
  }

  @Override
  public ActionExecutionResult execute(ActionExecutionContext executionContext) {
    return hasheableWrapper.executeFunction.apply(
        srcs,
        deps,
        outputs.stream()
            .map(OutputArtifact::getArtifact)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
        executionContext);
  }

  @Override
  public boolean isCacheable() {
    return false;
  }

  public FakeActionExecuteLambda getExecuteFunction() {
    return hasheableWrapper.executeFunction;
  }

  @FunctionalInterface
  public interface FakeActionExecuteLambda {
    ActionExecutionResult apply(
        ImmutableSortedSet<Artifact> srcs,
        ImmutableSortedSet<Artifact> deps,
        ImmutableSortedSet<Artifact> outputs,
        ActionExecutionContext context);
  }

  /**
   * For testing purposes only, where we create a hacky wrapper around lambdas that lets it be
   * hashed for rulekeys
   */
  private static class HashableWrapper implements AddsToRuleKey {

    private final FakeActionExecuteLambda executeFunction;

    @AddToRuleKey
    @SuppressWarnings("unused")
    private final int hash;

    private HashableWrapper(FakeActionExecuteLambda executeFunction) {
      this.executeFunction = executeFunction;
      this.hash = Objects.hash(executeFunction);
    }
  }
}
