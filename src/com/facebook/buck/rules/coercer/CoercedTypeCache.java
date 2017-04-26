/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Caches the set of possible {@link ParamInfo}s for each param on a coercable type.
 */
public class CoercedTypeCache {

  public static final CoercedTypeCache INSTANCE = new CoercedTypeCache();

  private final LoadingCache<
      TypeCoercerFactory,
      LoadingCache<Class<?>, ImmutableSet<ParamInfo>>> coercedTypeCache;

  private CoercedTypeCache() {
    coercedTypeCache = CacheBuilder.newBuilder()
        .build(
            new CacheLoader<TypeCoercerFactory, LoadingCache<Class<?>, ImmutableSet<ParamInfo>>>() {
              @Override
              public LoadingCache<Class<?>, ImmutableSet<ParamInfo>> load(
                  TypeCoercerFactory typeCoercerFactory) throws Exception {
                return CacheBuilder.newBuilder()
                    .build(new CacheLoader<Class<?>, ImmutableSet<ParamInfo>>() {
                      @Override
                      public ImmutableSet<ParamInfo> load(Class<?> coercableType) throws Exception {
                        ImmutableSet.Builder<ParamInfo> allInfo = ImmutableSet.builder();

                        for (Field field : coercableType.getFields()) {
                          if (Modifier.isFinal(field.getModifiers())) {
                            continue;
                          }
                          allInfo.add(new ParamInfo(typeCoercerFactory, coercableType, field));
                        }

                        return allInfo.build();
                      }
                });
          }
        });
  }

  /**
   * @return All {@link ParamInfo}s for coercableType.
   */
  public ImmutableSet<ParamInfo> getAllParamInfo(
      TypeCoercerFactory typeCoercerFactory,
      Class<?> coercableType) {
    return coercedTypeCache.getUnchecked(typeCoercerFactory).getUnchecked(coercableType);
  }
}
