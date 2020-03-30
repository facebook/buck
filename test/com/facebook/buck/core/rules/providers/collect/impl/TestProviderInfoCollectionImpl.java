/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.rules.providers.collect.impl;

import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.rules.providers.ProviderInfo;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.syntax.SkylarkDict;

/**
 * Implementation of {@link ProviderInfoCollection} for tests that automatically creates an empty
 * {@link com.facebook.buck.core.rules.providers.lib.DefaultInfo} if none exists.
 */
public class TestProviderInfoCollectionImpl extends ProviderInfoCollectionImpl {

  protected TestProviderInfoCollectionImpl(
      ImmutableMap<Provider.Key<?>, ? extends ProviderInfo<?>> infoMap) {
    super(infoMap);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static Builder builderWithExpectedSize(int expectedSize) {
    return new Builder(expectedSize);
  }

  public static class Builder extends ProviderInfoCollectionImpl.Builder {

    private Builder() {}

    private Builder(int expectedSize) {
      super(expectedSize);
    }

    @Override
    public Builder put(ProviderInfo<?> info) {
      super.put(info);
      return this;
    }

    public ProviderInfoCollection build() {
      return super.build(new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableList.of()));
    }
  }
}
