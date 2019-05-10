/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.rules.impl;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.RuleKeyObjectSink;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.base.CaseFormat;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Abstract implementation of a {@link BuildRule} that can be cached. If its current {@link RuleKey}
 * matches the one on disk, then it has no work to do. It should also try to fetch its output from
 * an {@link com.facebook.buck.artifact_cache.ArtifactCache} to avoid doing any computation.
 */
public abstract class AbstractBuildRule implements BuildRule {
  private final BuildTarget buildTarget;
  private final ProjectFilesystem projectFilesystem;
  private final Supplier<String> typeSupplier = MoreSuppliers.memoize(this::getTypeForClass);
  private final int hashCode;

  protected AbstractBuildRule(BuildTarget buildTarget, ProjectFilesystem projectFilesystem) {
    this.buildTarget = buildTarget;
    this.projectFilesystem = projectFilesystem;
    this.hashCode = computeHashCode();
  }

  /** Allows setting the fields after creation. Should only be used when deserializing. */
  protected static void injectFields(
      AbstractBuildRule rule, ProjectFilesystem filesystem, BuildTarget target) {
    setField("projectFilesystem", rule, filesystem);
    setField("buildTarget", rule, target);
    setField("typeSupplier", rule, MoreSuppliers.memoize(rule::getTypeForClass));
    setField("hashCode", rule, rule.computeHashCode());
  }

  private static void setField(String fieldName, Object instance, @Nullable Object value) {
    try {
      Field field = AbstractBuildRule.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(instance, value);
    } catch (IllegalAccessException | NoSuchFieldException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public final BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public final ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

  @Override
  public String getType() {
    return typeSupplier.get();
  }

  private String getTypeForClass() {
    Class<?> clazz = getClass();
    if (clazz.isAnonymousClass()) {
      clazz = clazz.getSuperclass();
    }
    return CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, clazz.getSimpleName()).intern();
  }

  @Override
  public boolean isCacheable() {
    return true;
  }

  @Override
  public boolean hasBuildSteps() {
    return true;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {}

  @Override
  public void updateBuildRuleResolver(BuildRuleResolver ruleResolver) {}

  @Override
  public final String toString() {
    return getFullyQualifiedName();
  }

  @Override
  public final boolean equals(Object obj) {
    if (!(obj instanceof AbstractBuildRule)) {
      return false;
    }
    AbstractBuildRule that = (AbstractBuildRule) obj;
    return Objects.equals(this.buildTarget, that.buildTarget)
        && Objects.equals(this.getType(), that.getType());
  }

  @Override
  public final int hashCode() {
    return hashCode;
  }

  private final int computeHashCode() {
    return this.buildTarget.hashCode();
  }

  @Override
  public ImmutableSet<BuildTarget> getDependencies() {
    return ImmutableSet.copyOf(
        Collections2.transform(getBuildDeps(), rule -> rule.getBuildTarget()));
  }

  @Override
  public ImmutableSet<SourcePath> getOutputs() {
    @Nullable SourcePath output = getSourcePathToOutput();
    if (output == null) {
      return ImmutableSet.of();
    }
    return ImmutableSet.of(getSourcePathToOutput());
  }

  @Override
  public void execute(
      ExecutionContext executionContext,
      BuildContext buildContext,
      BuildableContext buildableContext)
      throws StepFailedException, InterruptedException {
    for (Step step : getBuildSteps(buildContext, buildableContext)) {
      StepRunner.runStep(executionContext, step);
    }
  }
}
