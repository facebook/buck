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

package com.facebook.buck.rules;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.provider.BuildRuleInfoProvider;
import com.facebook.buck.rules.provider.BuildRuleInfoProviderCollection;
import com.facebook.buck.rules.provider.MissingProviderException;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.base.CaseFormat;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Abstract implementation of a {@link BuildRule} that can be cached and is implemented using {@link
 * BuildRuleInfoProvider}. If its current {@link RuleKey} matches the one on disk, then it has no
 * work to do. It should also try to fetch its output from an {@link
 * com.facebook.buck.artifact_cache.ArtifactCache} to avoid doing any computation.
 *
 * <p>TODO collapse {@link AbstractBuildRule} and this class into one once all BuildRules are
 * migrated to using BuildRuleInfoProvider
 */
public abstract class AbstractBuildRuleWithProviders implements BuildRule {
  private final BuildTarget buildTarget;
  private final ProjectFilesystem projectFilesystem;
  private final Supplier<String> typeSupplier = MoreSuppliers.memoize(this::getTypeForClass);

  private final BuildRuleInfoProviderCollection providers;

  protected AbstractBuildRuleWithProviders(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleInfoProviderCollection providers) {
    this.buildTarget = buildTarget;
    this.projectFilesystem = projectFilesystem;
    this.providers = providers;
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
  public final String toString() {
    return getFullyQualifiedName();
  }

  @Override
  public final boolean equals(Object obj) {
    if (!(obj instanceof AbstractBuildRuleWithProviders)) {
      return false;
    }
    AbstractBuildRuleWithProviders that = (AbstractBuildRuleWithProviders) obj;
    return Objects.equals(this.buildTarget, that.buildTarget)
        && Objects.equals(this.providers, that.providers)
        && Objects.equals(this.getType(), that.getType());
  }

  @Override
  public final int hashCode() {
    return Objects.hash(buildTarget, providers);
  }

  @Override
  public final boolean hasProviders() {
    return true;
  }

  /**
   * During ActionGraph creation, any data exposed to dependent BuildRule will be exposed via the
   * {@link BuildRuleInfoProvider}s obtained from this method.
   *
   * @param providerKey the key to the provider desired
   * @param <T> The type of the provider
   * @return The provider of type T based on the given key
   * @throws MissingProviderException
   */
  @Override
  public final <T extends BuildRuleInfoProvider> T getProvider(T.ProviderKey providerKey)
      throws MissingProviderException {
    return providers.get(providerKey);
  }
}
