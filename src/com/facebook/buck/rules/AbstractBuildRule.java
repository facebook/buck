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
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.annotations.Beta;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableSortedSet;

import org.immutables.value.Value;

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
  private final ImmutableSortedSet<BuildRule> extraDeps;
  private final ImmutableSortedSet<BuildRule> deps;
  private final RuleKeyBuilderFactory ruleKeyBuilderFactory;
  private final SourcePathResolver resolver;
  private final ProjectFilesystem projectFilesystem;
  @Nullable private volatile RuleKeyPair ruleKeyPair;

  protected AbstractBuildRule(BuildRuleParams buildRuleParams, SourcePathResolver resolver) {
    this.buildTarget = buildRuleParams.getBuildTarget();
    this.declaredDeps = buildRuleParams.getDeclaredDeps();
    this.extraDeps = buildRuleParams.getExtraDeps();
    this.deps = buildRuleParams.getDeps();
    this.ruleKeyBuilderFactory = buildRuleParams.getRuleKeyBuilderFactory();
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

  private ImmutableSortedSet<BuildRule> getExtraDeps() {
    return extraDeps;
  }

  @Override
  public final String getType() {
    return CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, getClass().getSimpleName());
  }

  public final SourcePathResolver getResolver() {
    return resolver;
  }

  @Override
  public final ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
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

  private RuleKeyPair getRuleKeyPair() {
    // This uses the "double-checked locking using volatile" pattern:
    // http://www.cs.umd.edu/~pugh/java/memoryModel/DoubleCheckedLocking.html.
    if (ruleKeyPair == null) {
      synchronized (this) {
        if (ruleKeyPair == null) {
          RuleKey.Builder builder = ruleKeyBuilderFactory.newInstance(this);
          RuleKey ruleKeyWithoutDeps = builder.build();
          // Now introduce the deps into the RuleKey.
          builder.setReflectively("deps", getDeclaredDeps());
          builder.setReflectively("buck.extraDeps", getExtraDeps());
          RuleKey totalRuleKey = builder.build();
          ruleKeyPair = RuleKeyPair.of(totalRuleKey, ruleKeyWithoutDeps);
        }
      }
    }
    return ruleKeyPair;
  }

  @BuckStyleImmutable
  @Value.Immutable
  public interface AbstractRuleKeyPair {

    @Value.Parameter
    RuleKey getTotalRuleKey();

    @Value.Parameter
    RuleKey getRuleKeyWithoutDeps();

  }

}
