/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.platform;

import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.PlatformResolver;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * An implementation of {@link PlatformResolver} that caches platforms created by a given delegate.
 */
public class CachingPlatformResolver implements PlatformResolver {

  private final PlatformResolver delegate;

  private final LoadingCache<UnconfiguredBuildTargetView, Platform> platformCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<UnconfiguredBuildTargetView, Platform>() {
                @Override
                public Platform load(UnconfiguredBuildTargetView buildTarget) {
                  return delegate.getPlatform(buildTarget);
                }
              });

  public CachingPlatformResolver(PlatformResolver delegate) {
    this.delegate = delegate;
  }

  @Override
  public Platform getPlatform(UnconfiguredBuildTargetView buildTarget) {
    return platformCache.getUnchecked(buildTarget);
  }
}
