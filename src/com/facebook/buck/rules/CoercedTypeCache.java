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

package com.facebook.buck.rules;

import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class CoercedTypeCache {
  private final LoadingCache<Class<?>, ImmutableSet<ParamInfo>> coercedTypeCache;

  public CoercedTypeCache(TypeCoercerFactory typeCoercerFactory) {
    coercedTypeCache = CacheBuilder.newBuilder()
        .build(new CacheLoader<Class<?>, ImmutableSet<ParamInfo>>() {
          @Override
          public ImmutableSet<ParamInfo> load(Class<?> key) throws Exception {
            ImmutableSet.Builder<ParamInfo> allInfo = ImmutableSet.builder();

            for (Field field : key.getFields()) {
              if (Modifier.isFinal(field.getModifiers())) {
                continue;
              }
              allInfo.add(new ParamInfo(typeCoercerFactory, key, field));
            }

            return allInfo.build();
          }
        });
  }

  public ImmutableSet<ParamInfo> getAllParamInfo(Class<?> key) {
    return coercedTypeCache.getUnchecked(key);
  }
}
