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
package com.facebook.buck.core.rules.providers.impl;

import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.InfoInterface;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.Provider.Key;
import com.google.devtools.build.lib.packages.SkylarkInfo;
import com.google.devtools.build.lib.packages.SkylarkProvider;
import com.google.devtools.build.lib.skylarkinterface.StarlarkContext;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import java.util.Optional;
import javax.annotation.Nullable;

/** Implementation of {@link ProviderInfoCollection}. */
public class ProviderInfoCollectionImpl implements ProviderInfoCollection {

  private final ImmutableMap<Key, InfoInterface> infoMap;

  private ProviderInfoCollectionImpl(ImmutableMap<Key, InfoInterface> infoMap) {
    this.infoMap = infoMap;
  }

  @Override
  public Optional<SkylarkInfo> get(SkylarkProvider provider) {
    return Optional.ofNullable((SkylarkInfo) get(provider.getKey()));
  }

  @Override
  public <T extends InfoInterface> Optional<T> get(BuiltinProvider<T> provider) {
    return Optional.ofNullable(provider.getValueClass().cast(get(provider.getKey())));
  }

  @Override
  public Object getIndex(Object key, Location loc, StarlarkContext context) throws EvalException {
    verifyKeyIsProvider(
        key, loc, "Type Target only supports indexing by object constructors, got %s instead");
    return get(((Provider) key).getKey());
  }

  @Override
  public boolean containsKey(Object key, Location loc, StarlarkContext context)
      throws EvalException {
    verifyKeyIsProvider(
        key, loc, "Type Target only supports querying by object constructors, got %s instead");
    return get(((Provider) key).getKey()) != null;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static Builder builderWithExpectedSize(int expectedSize) {
    return new Builder(expectedSize);
  }

  @Nullable
  private InfoInterface get(Provider.Key providerKey) {
    return infoMap.get(providerKey);
  }

  private void verifyKeyIsProvider(Object key, Location loc, String s) throws EvalException {
    if (!(key instanceof Provider)) {
      throw new EvalException(loc, String.format(s, EvalUtils.getDataTypeName(key)));
    }
  }

  public static class Builder implements ProviderInfoCollection.Builder {

    private final ImmutableMap.Builder<Provider.Key, InfoInterface> mapBuilder;

    private Builder() {
      mapBuilder = ImmutableMap.builder();
    }

    private Builder(int expectedSize) {
      mapBuilder = ImmutableMap.builderWithExpectedSize(expectedSize);
    }

    @Override
    public ProviderInfoCollection.Builder put(InfoInterface info) {
      mapBuilder.put(info.getProvider().getKey(), info);
      return this;
    }

    @Override
    public ProviderInfoCollection build() {
      return new ProviderInfoCollectionImpl(mapBuilder.build());
    }
  }
}
