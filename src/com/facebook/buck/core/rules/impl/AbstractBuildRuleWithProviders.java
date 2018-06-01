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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.provider.BuildRuleInfoProvider;
import com.facebook.buck.core.rules.provider.BuildRuleInfoProviderCollection;
import com.facebook.buck.core.rules.provider.MissingProviderException;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.Preconditions;
import java.util.Objects;

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

  private final BuildRuleInfoProviderCollection providers;

  protected AbstractBuildRuleWithProviders(BuildRuleInfoProviderCollection providers) {
    this.providers = providers;
    Preconditions.checkState(
        providers.getDefaultProvider().getTypeClass().equals(getClass()),
        "DefaultBuildRuleInfoProvider should have getTypeClass() equal to type of the BuildRule");
  }

  @Override
  public void updateBuildRuleResolver(
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver) {}

  @Override
  public final boolean equals(Object obj) {
    if (!(obj instanceof AbstractBuildRuleWithProviders)) {
      return false;
    }
    AbstractBuildRuleWithProviders that = (AbstractBuildRuleWithProviders) obj;
    return Objects.equals(this.providers, that.providers);
  }

  @Override
  public final int hashCode() {
    return providers.hashCode();
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

  /**
   * During ActionGraph creation, any data exposed to dependent BuildRule will be exposed via the
   * collection of providers obtained from this method.
   *
   * @return an immutable BuildRuleInfoProviderCollection containing all the Providers of this
   *     BuildRule
   */
  @Override
  public final BuildRuleInfoProviderCollection getProviderCollection() {
    return providers;
  }

  // The following methods provide quick access to common BuildRule data directly for use without
  // needing providers, for outside of ActionGraph construction
  @Override
  public final BuildTarget getBuildTarget() {
    return providers.getDefaultProvider().getBuildTarget();
  }

  @Override
  public final ProjectFilesystem getProjectFilesystem() {
    return providers.getDefaultProvider().getProjectFilesystem();
  }

  @Override
  public String getType() {
    return providers.getDefaultProvider().getType();
  }

  @Override
  public boolean isCacheable() {
    return providers.getDefaultProvider().isCacheable();
  }

  @Override
  public boolean hasBuildSteps() {
    return true;
  }

  @Override
  public final String toString() {
    return getFullyQualifiedName();
  }
}
