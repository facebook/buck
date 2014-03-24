/*
 * Copyright 2012-present Facebook, Inc.
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

import com.google.common.annotations.Beta;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;


/**
 * Abstract implementation of a {@link BuildRule} that can be cached. If its current {@link RuleKey}
 * matches the one on disk, then it has no work to do. It should also try to fetch its output from
 * an {@link ArtifactCache} to avoid doing any computation.
 */
@Beta
public abstract class AbstractCachingBuildRule extends AbstractBuildRule implements BuildRule {

  private static final CachingBuildEngine engine = new CachingBuildEngine();
  private final Buildable buildable;

  /** @see Buildable#getInputsToCompareToOutput()  */
  private Iterable<Path> inputsToCompareToOutputs;

  protected AbstractCachingBuildRule(Buildable buildable, BuildRuleParams params) {
    super(params);
    this.buildable = Preconditions.checkNotNull(buildable);
  }

  protected AbstractCachingBuildRule(BuildRuleParams buildRuleParams) {
    super(buildRuleParams);
    this.buildable = Preconditions.checkNotNull(getBuildable());
  }

  /**
   * This rule is designed to be used for precondition checks in subclasses. For example, before
   * running the tests associated with a build rule, it is reasonable to do a sanity check to
   * ensure that the rule has been built.
   */
  // TODO(simons): This is a responsibility of the engine, not the rule
  // TODO(user): This is only used by *TestRule
  protected final synchronized boolean isRuleBuilt() {
    return engine.isRuleBuilt(this);
  }

  @Override
  // TODO(simons): This is a responsibility of the engine, not the rule
  // TODO(user): This can be gotten rid by using BuildEngine in TestCommand.
  public BuildRuleSuccess.Type getBuildResultType() {
    Preconditions.checkState(isRuleBuilt());
    try {
      return engine.getBuildRuleResult(this).getType();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Iterable<Path> getInputs() {
    if (inputsToCompareToOutputs == null) {
      inputsToCompareToOutputs = buildable.getInputsToCompareToOutput();
    }
    return inputsToCompareToOutputs;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    // For a rule that lists its inputs via a "srcs" argument, this may seem redundant, but it is
    // not. Here, the inputs are specified as InputRules, which means that the _contents_ of the
    // files will be hashed. In the case of .set("srcs", srcs), the list of strings itself will be
    // hashed. It turns out that we need both of these in order to construct a RuleKey correctly.
    // Note: appendToRuleKey() should not set("srcs", srcs) if the inputs are order-independent.
    Iterable<Path> inputs = getInputs();
    builder = super.appendToRuleKey(builder)
        .setInputs("buck.inputs", inputs.iterator())
        .setSourcePaths("buck.sourcepaths", SourcePaths.toSourcePathsSortedByNaturalOrder(inputs));
    // TODO(simons): Rename this when no Buildables extend this class.
    return buildable.appendDetailsToRuleKey(builder);
  }

  @Override
  public final ListenableFuture<BuildRuleSuccess> build(final BuildContext context) {
    return engine.build(context, this);
  }
}
