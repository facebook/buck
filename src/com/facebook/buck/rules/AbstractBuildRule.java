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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableCollection;
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
  private final ImmutableSortedSet<BuildRule> declaredDeps;
  private final ImmutableSortedSet<BuildRule> deps;
  private final RuleKeyBuilderFactory ruleKeyBuilderFactory;
  private final BuildRuleType buildRuleType;
  private final SourcePathResolver resolver;
  private final ProjectFilesystem projectFilesystem;
  /** @see #getInputsToCompareToOutput()  */
  @Nullable private ImmutableCollection<Path> inputsToCompareToOutputs;
  @Nullable private volatile RuleKey.Builder.RuleKeyPair ruleKeyPair;

  protected AbstractBuildRule(BuildRuleParams buildRuleParams, SourcePathResolver resolver) {
    this.buildTarget = buildRuleParams.getBuildTarget();
    this.declaredDeps = buildRuleParams.getDeclaredDeps();
    this.deps = buildRuleParams.getDeps();
    this.ruleKeyBuilderFactory = buildRuleParams.getRuleKeyBuilderFactory();
    this.buildRuleType = buildRuleParams.getBuildRuleType();
    this.resolver = resolver;
    this.projectFilesystem = buildRuleParams.getProjectFilesystem();
  }

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

  public final ImmutableSortedSet<BuildRule> getDeclaredDeps() {
    return declaredDeps;
  }

  @Override
  public final BuildRuleType getType() {
    return buildRuleType;
  }

  public final SourcePathResolver getResolver() {
    return resolver;
  }

  @Override
  public final ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

  @Override
  public ImmutableCollection<Path> getInputs() {
    if (inputsToCompareToOutputs == null) {
      inputsToCompareToOutputs = getInputsToCompareToOutput();
    }
    return inputsToCompareToOutputs;
  }

  protected abstract ImmutableCollection<Path> getInputsToCompareToOutput();

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
    ImmutableCollection<Path> inputs = getInputs();
    builder = builder
        .setReflectively("buck.inputs", inputs)
        .setReflectively(
            "buck.sourcepaths",
            SourcePaths.toSourcePathsSortedByNaturalOrder(getProjectFilesystem(), inputs));
    // TODO(simons): Rename this when no Buildables extend this class.
    return appendDetailsToRuleKey(builder);
  }

  protected abstract RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder);

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
          RuleKey.Builder builder = ruleKeyBuilderFactory.newInstance(this, getResolver());
          appendToRuleKey(builder);
          ruleKeyPair = builder.build();
        }
      }
    }
    return ruleKeyPair;
  }

  @Override
  public CacheMode getCacheMode() {
    return CacheMode.ENABLED;
  }

}
