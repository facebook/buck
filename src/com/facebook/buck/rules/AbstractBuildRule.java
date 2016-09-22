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

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.google.common.base.CaseFormat;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Objects;

/**
 * Abstract implementation of a {@link BuildRule} that can be cached. If its current {@link RuleKey}
 * matches the one on disk, then it has no work to do. It should also try to fetch its output from
 * an {@link ArtifactCache} to avoid doing any computation.
 */
public abstract class AbstractBuildRule implements BuildRule {

  private final BuildTarget buildTarget;
  private final Supplier<ImmutableSortedSet<BuildRule>> declaredDeps;
  private final Supplier<ImmutableSortedSet<BuildRule>> extraDeps;
  private final Supplier<ImmutableSortedSet<BuildRule>> deps;
  private final SourcePathResolver resolver;
  private final ProjectFilesystem projectFilesystem;

  private final Supplier<String> typeSupplier = Suppliers.memoize(
      new Supplier<String>() {
        @Override
        public String get() {
          return getTypeForClass();
        }
      });

  protected AbstractBuildRule(BuildRuleParams buildRuleParams, SourcePathResolver resolver) {
    this.buildTarget = buildRuleParams.getBuildTarget();
    this.declaredDeps = buildRuleParams.getDeclaredDeps();
    this.extraDeps = buildRuleParams.getExtraDeps();
    this.deps = buildRuleParams.getTotalDeps();
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
    return deps.get();
  }

  public final ImmutableSortedSet<BuildRule> getDeclaredDeps() {
    return declaredDeps.get();
  }

  public final ImmutableSortedSet<BuildRule> deprecatedGetExtraDeps() {
    return extraDeps.get();
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
    return CaseFormat
        .UPPER_CAMEL
        .to(CaseFormat.LOWER_UNDERSCORE, clazz.getSimpleName())
        .intern();
  }

  public final SourcePathResolver getResolver() {
    return resolver;
  }

  @Override
  public final ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

  @Override
  public final int compareTo(BuildRule that) {
    if (this == that) {
      return 0;
    }

    return this.getBuildTarget().compareTo(that.getBuildTarget());
  }

  @Override
  public final boolean equals(Object obj) {
    if (!(obj instanceof AbstractBuildRule)) {
      return false;
    }
    AbstractBuildRule that = (AbstractBuildRule) obj;
    return Objects.equals(this.buildTarget, that.buildTarget) &&
        Objects.equals(this.getType(), that.getType());
  }

  @Override
  public final int hashCode() {
    return this.buildTarget.hashCode();
  }

  @Override
  public final String toString() {
    return getFullyQualifiedName();
  }

  @Override
  public boolean isCacheable() {
    return true;
  }

}
