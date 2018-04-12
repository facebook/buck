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

import com.facebook.buck.core.rules.provider.BuildRuleInfoProvider.ProviderKey;
import com.google.common.collect.ImmutableMap;

/**
 * A container for storing a collection of {@link BuildRuleInfoProvider} for {@link
 * com.facebook.buck.rules.BuildRule}. This is basically a wrapper around an ImmutableMap that
 * provides more convenient provider access and building.
 *
 * <p>The supplied {@link BuildRuleProviderCollection} must contain {@link
 * DefaultBuildRuleInfoProvider} or {@link MissingProviderException} will be thrown
 */
public final class BuildRuleInfoProviderCollection {

  private final ImmutableMap<BuildRuleInfoProvider.ProviderKey, BuildRuleInfoProvider> providerMap;

  // provides quick access to default provider, which is commonly accessed so we don't need frequent
  // map lookup
  private final DefaultBuildRuleInfoProvider defaultProvider;

  private BuildRuleInfoProviderCollection(
      ImmutableMap<BuildRuleInfoProvider.ProviderKey, BuildRuleInfoProvider> providerMap) {
    this.providerMap = providerMap;
    this.defaultProvider = get(DefaultBuildRuleInfoProvider.KEY);
  }

  /**
   * @param key the key for the provider we want to retrieve
   * @param <T> the specific type of the provider we want to retrieve
   * @return the provider of type T, if exists
   * @throws MissingProviderException if the desired provider doesn't exist
   */
  @SuppressWarnings("unchecked")
  public <T extends BuildRuleInfoProvider> T get(BuildRuleInfoProvider.ProviderKey key)
      throws MissingProviderException {

    T provider = (T) providerMap.get(key);

    if (provider == null) {
      throw new MissingProviderException("Cannot find provider %s in %s", key, this);
    }
    return provider;
  }

  /** Provides a quick access to the default provider without needing map lookup */
  public DefaultBuildRuleInfoProvider getDefaultProvider() {
    return defaultProvider;
  }

  @Override
  public final boolean equals(Object obj) {
    if (!(obj instanceof BuildRuleInfoProviderCollection)) {
      return false;
    }
    BuildRuleInfoProviderCollection that = (BuildRuleInfoProviderCollection) obj;
    return this.providerMap.equals(that.providerMap);
  }

  @Override
  public final int hashCode() {
    return this.providerMap.hashCode();
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for the BuildRuleInfoProviderCollection that ensures consistency in {@link ProviderKey}
   * and the corresponding {@link BuildRuleInfoProvider}
   */
  public static class Builder {

    private ImmutableMap.Builder<BuildRuleInfoProvider.ProviderKey, BuildRuleInfoProvider> map =
        ImmutableMap.builder();

    private Builder() {}

    public Builder put(BuildRuleInfoProvider buildRuleInfoProvider) {
      map.put(buildRuleInfoProvider.getKey(), buildRuleInfoProvider);
      return this;
    }

    /**
     * @param providers an iterable of {@link BuildRuleInfoProvider} to add to this collection
     * @return the builder
     */
    public Builder putAll(Iterable<BuildRuleInfoProvider> providers) {
      for (BuildRuleInfoProvider buildRuleInfoProvider : providers) {
        put(buildRuleInfoProvider);
      }
      return this;
    }

    public BuildRuleInfoProviderCollection build() {
      return new BuildRuleInfoProviderCollection(map.build());
    }
  }
}
