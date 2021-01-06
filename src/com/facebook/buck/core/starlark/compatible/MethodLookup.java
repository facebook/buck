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

package com.facebook.buck.core.starlark.compatible;

import com.google.common.base.CaseFormat;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Used to lookup methods for Starlark compatible objects and perform fast reflective calls. This
 * translates the underscore python naming convention to camel case.
 *
 * <p>Currently, this is only used to lookup struct-like values, although we can augment and edit
 * this to support class method calls in general if we need.
 *
 * <p>This caches the method lookups on a Class level.
 */
public class MethodLookup {

  public static ImmutableMap<String, Method> getMethods(Class<?> cls) {
    return classMethods.getUnchecked(cls);
  }

  private static final LoadingCache<Class<?>, ImmutableMap<String, Method>> classMethods =
      CacheBuilder.newBuilder().build(CacheLoader.from(MethodLookup::getMethodsFromClass));

  private static ImmutableMap<String, Method> getMethodsFromClass(Class<?> clazz) {

    Method[] methods = clazz.getDeclaredMethods();

    ImmutableMap.Builder<String, Method> results =
        ImmutableMap.builderWithExpectedSize(methods.length);
    for (Method m : methods) {
      try {
        // if the method was declared in super, ignore it.
        if (clazz.getSuperclass().getDeclaredMethod(m.getName(), m.getParameterTypes()) != null) {
          continue;
        }
      } catch (NoSuchMethodException e) {
        // no method. continue
      }

      if (isStarlarkCallableStructField(m)) {
        results.put(CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, m.getName()), m);
      }
    }
    return results.build();
  }

  private static boolean isStarlarkCallableStructField(Method method) {
    // we could add more here in terms of requiring annotations, but we don't do that for now.
    return Modifier.isPublic(method.getModifiers())
        && Modifier.isAbstract(method.getModifiers())
        && method.getParameterCount() == 0
        && !method.getReturnType().equals(void.class);
  }

  private MethodLookup() {}
}
