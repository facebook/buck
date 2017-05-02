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
import com.google.common.collect.ImmutableMap;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Caches the set of possible {@link ParamInfo}s for each param on a coercable type. */
public class CoercedTypeCache {

  public static final CoercedTypeCache INSTANCE = new CoercedTypeCache();

  private final LoadingCache<
          TypeCoercerFactory, LoadingCache<Class<?>, ImmutableMap<String, ParamInfo>>>
      coercedTypeCache;

  private CoercedTypeCache() {
    coercedTypeCache =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<
                    TypeCoercerFactory, LoadingCache<Class<?>, ImmutableMap<String, ParamInfo>>>() {
                  @Override
                  public LoadingCache<Class<?>, ImmutableMap<String, ParamInfo>> load(
                      TypeCoercerFactory typeCoercerFactory) throws Exception {
                    return CacheBuilder.newBuilder()
                        .build(
                            new CacheLoader<Class<?>, ImmutableMap<String, ParamInfo>>() {
                              @Override
                              public ImmutableMap<String, ParamInfo> load(Class<?> coercableType)
                                  throws Exception {
                                ImmutableMap.Builder<String, ParamInfo> allInfo =
                                    new ImmutableMap.Builder<>();

                                for (Field field : coercableType.getFields()) {
                                  if (Modifier.isFinal(field.getModifiers())) {
                                    continue;
                                  }
                                  ParamInfo paramInfo =
                                      new ParamInfo(typeCoercerFactory, coercableType, field);
                                  allInfo.put(paramInfo.getName(), paramInfo);
                                }

                                return allInfo.build();
                              }
                            });
                  }
                });
  }

  /** @return All {@link ParamInfo}s for coercableType. */
  public ImmutableMap<String, ParamInfo> getAllParamInfo(
      TypeCoercerFactory typeCoercerFactory, Class<?> coercableType) {
    return coercedTypeCache.getUnchecked(typeCoercerFactory).getUnchecked(coercableType);
  }

  /** Returns an unpopulated DTO object. */
  public static <T> T instantiateSkeleton(Class<T> dtoType) {
    try {
      return dtoType.newInstance();
    } catch (IllegalAccessException | InstantiationException e) {
      throw new IllegalStateException(
          String.format(
              "Could not instantiate constructor arg type %s: %s", dtoType, e.getMessage(), e));
    }
  }
}
