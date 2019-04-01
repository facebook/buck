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

import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * An implementation of {@link TargetPlatformResolver} that caches platforms created by a given
 * delegate.
 */
public class CachingTargetPlatformResolver implements TargetPlatformResolver {

  private final TargetPlatformResolver delegate;

  private final LoadingCache<TargetConfiguration, Platform> platformCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<TargetConfiguration, Platform>() {
                @Override
                public Platform load(TargetConfiguration targetConfiguration) {
                  return delegate.getTargetPlatform(targetConfiguration);
                }
              });

  public CachingTargetPlatformResolver(TargetPlatformResolver delegate) {
    this.delegate = delegate;
  }

  @Override
  public Platform getTargetPlatform(TargetConfiguration targetConfiguration) {
    return platformCache.getUnchecked(targetConfiguration);
  }
}
