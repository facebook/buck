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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.Beta;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * Abstract implementation of a {@link BuildRule} that can be cached. If its current {@link RuleKey}
 * matches the one on disk, then it has no work to do. It should also try to fetch its output from
 * an {@link ArtifactCache} to avoid doing any computation.
 */
@Beta
public abstract class AbstractBuildRule implements BuildRule {

  private final BuildTarget buildTarget;
  private final Buildable buildable;
  private final ImmutableSortedSet<BuildRule> deps;
  private final ImmutableSet<BuildTargetPattern> visibilityPatterns;
  private final RuleKeyBuilderFactory ruleKeyBuilderFactory;
  /** @see Buildable#getInputsToCompareToOutput()  */
  private Iterable<Path> inputsToCompareToOutputs;
  @Nullable private volatile RuleKey.Builder.RuleKeyPair ruleKeyPair;

  protected AbstractBuildRule(BuildRuleParams buildRuleParams, Buildable buildable) {
    Preconditions.checkNotNull(buildRuleParams);
    this.buildTarget = buildRuleParams.getBuildTarget();
    this.buildable = buildable;
    this.deps = buildRuleParams.getDeps();
    this.visibilityPatterns = buildRuleParams.getVisibilityPatterns();
    this.ruleKeyBuilderFactory = buildRuleParams.getRuleKeyBuilderFactory();

    // Nodes added via graph enhancement are exempt from visibility checks.
    if (!buildTarget.isFlavored()) {
      for (BuildRule dep : this.deps) {
        if (!dep.isVisibleTo(buildTarget)) {
          throw new HumanReadableException("%s depends on %s, which is not visible",
              buildTarget,
              dep);
        }
      }
    }
  }

  @Override
  public abstract BuildRuleType getType();

  @Override
  public BuildableProperties getProperties() {
    return BuildableProperties.NONE;
  }

  @Override
  public final BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public final String getFullyQualifiedName() {
    return buildTarget.getFullyQualifiedName();
  }

  @Override
  public final ImmutableSortedSet<BuildRule> getDeps() {
    return deps;
  }

  @Override
  public final ImmutableSet<BuildTargetPattern> getVisibilityPatterns() {
    return visibilityPatterns;
  }

  @Override
  public final boolean isVisibleTo(BuildTarget target) {
    // Targets in the same build file are always visible to each other.
    if (target.getBaseName().equals(getBuildTarget().getBaseName())) {
      return true;
    }

    for (BuildTargetPattern pattern : getVisibilityPatterns()) {
      if (pattern.apply(target)) {
        return true;
      }
    }

    return false;
  }

  @Override
  public Iterable<Path> getInputs() {
    if (inputsToCompareToOutputs == null) {
      inputsToCompareToOutputs = buildable.getInputsToCompareToOutput();
    }
    return inputsToCompareToOutputs;
  }

  @Override
  public final int compareTo(HasBuildTarget that) {
    return this.getBuildTarget().compareTo(that.getBuildTarget());
  }

  @Override
  public final boolean equals(Object obj) {
    if (!(obj instanceof AbstractBuildRule)) {
      return false;
    }
    AbstractBuildRule that = (AbstractBuildRule) obj;
    return this.buildTarget.equals(that.buildTarget);
  }

  @Override
  public final int hashCode() {
    return this.buildTarget.hashCode();
  }

  @Override
  public final String toString() {
    return getFullyQualifiedName();
  }

  /**
   * {@link BuildRule#getRuleKey()} and {@link BuildRule#getRuleKeyWithoutDeps()} uses this when
   * constructing {@link RuleKey}s for this class. Every subclass that extends the rule state in a
   * way that matters to idempotency must override {@link #appendToRuleKey(RuleKey.Builder)} and
   * append its state to the {@link RuleKey.Builder} returned by its superclass's
   * {@link #appendToRuleKey(RuleKey.Builder)} implementation. Example:
   * <pre>
   * &#x40;Override
   * protected RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
   *   return super.appendToRuleKey(builder)
   *       .set("srcs", srcs),
   *       .set("resources", resources);
   * }
   * </pre>
   */
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    // For a rule that lists its inputs via a "srcs" argument, this may seem redundant, but it is
    // not. Here, the inputs are specified as InputRules, which means that the _contents_ of the
    // files will be hashed. In the case of .set("srcs", srcs), the list of strings itself will be
    // hashed. It turns out that we need both of these in order to construct a RuleKey correctly.
    // Note: appendToRuleKey() should not set("srcs", srcs) if the inputs are order-independent.
    Iterable<Path> inputs = getInputs();
    builder = builder
        .setInputs("buck.inputs", inputs.iterator())
        .setSourcePaths("buck.sourcepaths", SourcePaths.toSourcePathsSortedByNaturalOrder(inputs));
    // TODO(simons): Rename this when no Buildables extend this class.
    return buildable.appendDetailsToRuleKey(builder);
  }


  /**
   * This method should be overridden only for unit testing.
   */
  @Override
  public RuleKey getRuleKey() {
    return getRuleKeyPair().getTotalRuleKey();
  }

  /**
   * Creates a new {@link RuleKey} for this {@link BuildRule} that does not take {@link #getDeps()}
   * into account.
   */
  @Override
  public RuleKey getRuleKeyWithoutDeps() {
    return getRuleKeyPair().getRuleKeyWithoutDeps();
  }

  private RuleKey.Builder.RuleKeyPair getRuleKeyPair() {
    // This uses the "double-checked locking using volatile" pattern:
    // http://www.cs.umd.edu/~pugh/java/memoryModel/DoubleCheckedLocking.html.
    if (ruleKeyPair == null) {
      synchronized (this) {
        if (ruleKeyPair == null) {
          RuleKey.Builder builder = ruleKeyBuilderFactory.newInstance(this);
          appendToRuleKey(builder);
          ruleKeyPair = builder.build();
        }
      }
    }
    return ruleKeyPair;
  }
}
