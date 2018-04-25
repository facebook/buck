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

package com.facebook.buck.core.rules.provider;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.CaseFormat;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/**
 * The default data provided by all {@link com.facebook.buck.rules.BuildRule} and contains standard
 * fields available to be passed to dependant BuildRules during action graph construction.
 *
 * <p>{@link ProviderCollection} must contain one and only one of this default provider
 */
@Value.Immutable(builder = false, copy = false)
public abstract class DefaultBuildRuleInfoProvider implements BuildRuleInfoProvider {

  public static final ProviderKey KEY =
      ImmutableProviderKey.of(ImmutableDefaultBuildRuleInfoProvider.class);

  /** @return the class of the corresponding {@link com.facebook.buck.rules.BuildRule} */
  @Value.Parameter
  public abstract Class<?> getTypeClass();

  @Value.Parameter
  public abstract BuildTarget getBuildTarget();

  /** @return the build target name of the corresonding BuildRule */
  public String getFullyQualifiedName() {
    return getBuildTarget().getFullyQualifiedName();
  }

  /**
   * @return the type of the {@link com.facebook.buck.rules.BuildRule} as defined by the interface
   *     {@link com.facebook.buck.rules.BuildRule#getType()}
   */
  @Value.Lazy
  public String getType() {
    Class<?> clazz = getTypeClass();
    if (clazz.isAnonymousClass()) {
      clazz = clazz.getSuperclass();
    }
    return CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, clazz.getSimpleName()).intern();
  }

  /** @return the same value as {@link #getFullyQualifiedName()} */
  @Override
  public String toString() {
    return getFullyQualifiedName();
  }

  /** @return The output of the BuildRule */
  @Nullable
  @Value.Parameter
  public abstract SourcePath getSourcePathToOutput();

  /**
   * @return the {@link ProjectFilesystem} that the corresponding {@link
   *     com.facebook.buck.rules.BuildRule} operates on
   */
  @Value.Parameter
  public abstract ProjectFilesystem getProjectFilesystem();

  /**
   * Whether this {@link BuildRule} can be cached.
   *
   * <p>Uncached build rules are never written out to cache, never read from cache, and not included
   * in cache statistics. This rule is useful for artifacts which cannot be easily normalized.
   *
   * <p>Uncached rules are not always rebuilt, however, as long as the existing on-disk
   * representation is up to date. This means that these rules can take advantage of {@link
   * com.facebook.buck.rules.keys.SupportsInputBasedRuleKey} to prevent rebuilding.
   */
  @Value.Parameter
  public abstract boolean isCacheable();

  /**
   * @return a {@link DefaultBuildRuleInfoProvider} with default {@code isCacheable()} to {@code
   *     true}
   */
  public static DefaultBuildRuleInfoProvider of(
      Class<?> typeClass,
      BuildTarget buildTarget,
      @Nullable SourcePath sourcePathToOutput,
      ProjectFilesystem projectFilesystem) {
    return ImmutableDefaultBuildRuleInfoProvider.of(
        typeClass, buildTarget, sourcePathToOutput, projectFilesystem, true);
  }
}
