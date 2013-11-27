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
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;

import javax.annotation.Nullable;

abstract class AbstractBuildRule implements BuildRule {

  private final BuildTarget buildTarget;
  private final ImmutableSortedSet<BuildRule> deps;
  private final ImmutableSet<BuildTargetPattern> visibilityPatterns;
  private final RuleKeyBuilderFactory ruleKeyBuilderFactory;
  @Nullable private volatile RuleKey.Builder.RuleKeyPair ruleKeyPair;

  protected AbstractBuildRule(BuildRuleParams buildRuleParams) {
    Preconditions.checkNotNull(buildRuleParams);
    this.buildTarget = buildRuleParams.getBuildTarget();
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
  public final int compareTo(BuildRule that) {
    return this.getFullyQualifiedName().compareTo(that.getFullyQualifiedName());
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
   * {@link #getRuleKey()} and {@link #getRuleKeyWithoutDeps()} uses this when constructing
   * {@link RuleKey}s for this class. Every subclass that extends the rule state in a way that
   * matters to idempotency must override
   * {@link #appendToRuleKey(com.facebook.buck.rules.RuleKey.Builder)} and append its state to the
   * {@link RuleKey.Builder} returned by its superclass's
   * {@link #appendToRuleKey(com.facebook.buck.rules.RuleKey.Builder)} implementation. Example:
   * <pre>
   * &#x40;Override
   * protected RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
   *   return super.appendToRuleKey(builder)
   *       .set("srcs", srcs),
   *       .set("resources", resources);
   * }
   * </pre>
   */
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder;
  }

  /**
   * This method should be overridden only for unit testing.
   */
  @Override
  public RuleKey getRuleKey() throws IOException {
    return getRuleKeyPair().getTotalRuleKey();
  }

  /**
   * Creates a new {@link RuleKey} for this {@link BuildRule} that does not take {@link #getDeps()}
   * into account.
   */
  @Override
  public RuleKey getRuleKeyWithoutDeps() throws IOException {
    return getRuleKeyPair().getRuleKeyWithoutDeps();
  }

  private RuleKey.Builder.RuleKeyPair getRuleKeyPair() throws IOException {
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

  /**
   * @return Whether the input path directs to a file in the buck generated files folder.
   */
  public static boolean isGeneratedFile(String pathRelativeToProjectRoot) {
    return pathRelativeToProjectRoot.startsWith(BuckConstant.GEN_DIR);
  }
}
