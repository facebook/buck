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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.Types;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

/** Caches the set of possible {@link ParamInfo}s for each param on a coercable type. */
public class CoercedTypeCache {

  public static final CoercedTypeCache INSTANCE = new CoercedTypeCache();

  public static final ImmutableSet<Class<?>> OPTIONAL_TYPES =
      ImmutableSet.of(Optional.class, OptionalInt.class, OptionalLong.class, OptionalDouble.class);

  /** @return All {@link ParamInfo}s for coercableType. */
  public ImmutableMap<String, ParamInfo> getAllParamInfo(
      TypeCoercerFactory typeCoercerFactory, Class<?> coercableType) {
    return coercedTypeCache.getUnchecked(typeCoercerFactory).getUnchecked(coercableType);
  }

  /**
   * Returns an unpopulated DTO object, and the build method which must be called with it when it is
   * finished being populated.
   */
  @SuppressWarnings("unchecked")
  public static <T> Pair<Object, Function<Object, T>> instantiateSkeleton(
      Class<T> dtoType, BuildTarget buildTarget) {
    try {
      Object builder = dtoType.getMethod("builder").invoke(null);
      Method buildMethod = builder.getClass().getMethod("build");
      return new Pair<>(
          builder,
          x -> {
            try {
              return (T) buildMethod.invoke(x);
            } catch (IllegalAccessException e) {
              throw new IllegalStateException(
                  String.format(
                      "Error building immutable constructor arg for %s: %s",
                      buildTarget, e.getMessage()),
                  e);
            } catch (InvocationTargetException e) {
              if (e.getCause() instanceof IllegalStateException) {
                IllegalStateException cause = (IllegalStateException) e.getCause();
                if (cause.getMessage().contains("Cannot build")
                    && cause.getMessage().contains("required")) {
                  List<String> matches =
                      Splitter.on(CharMatcher.anyOf("[]")).splitToList(cause.getMessage());
                  if (matches.size() >= 2) {
                    throw new HumanReadableException(
                        "%s missing required argument(s): %s", buildTarget, matches.get(1));
                  }
                }
              }
              throw new RuntimeException(
                  String.format(
                      "Error building immutable constructor arg for %s: %s",
                      buildTarget, e.getCause().getMessage()),
                  e.getCause());
            }
          });
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException(
          String.format(
              "Could not instantiate immutable constructor arg type %s: %s",
              dtoType, e.getMessage()),
          e);
    }
  }

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
                      TypeCoercerFactory typeCoercerFactory) {
                    return CacheBuilder.newBuilder()
                        .build(
                            new CacheLoader<Class<?>, ImmutableMap<String, ParamInfo>>() {
                              @Override
                              public ImmutableMap<String, ParamInfo> load(Class<?> coercableType) {

                                if (Types.getSupertypes(coercableType).stream()
                                    .noneMatch(
                                        c -> c.getAnnotation(BuckStyleImmutable.class) != null)) {
                                  // Sniff for @BuckStyleImmutable not @Value.Immutable because the
                                  // latter isn't retained at runtime.
                                  throw new IllegalArgumentException(
                                      String.format(
                                          "Tried to coerce non-Immutable type %s - all args should be "
                                              + "immutable, in which case its super-class should be annotated @BuckStyleImmutable",
                                          coercableType.getName()));
                                }
                                Class<?> builderType;
                                try {
                                  builderType = coercableType.getMethod("builder").getReturnType();
                                } catch (NoSuchMethodException e) {
                                  throw new IllegalArgumentException(
                                      String.format(
                                          "Tried to coerce non-Immutable type %s - be "
                                              + "immutable, in which case they should have a builder method",
                                          coercableType.getName()));
                                }
                                return extractForImmutableBuilder(builderType, typeCoercerFactory);
                              }
                            });
                  }
                });
  }

  @VisibleForTesting
  static ImmutableMap<String, ParamInfo> extractForImmutableBuilder(
      Class<?> coercableType, TypeCoercerFactory typeCoercerFactory) {
    Map<String, Set<Method>> foundSetters = new HashMap<>();
    for (Method method : coercableType.getDeclaredMethods()) {
      if (!method.getName().startsWith("set")) {
        continue;
      }
      foundSetters.putIfAbsent(method.getName(), new HashSet<>());
      foundSetters.get(method.getName()).add(method);
    }
    ImmutableMap.Builder<String, ParamInfo> allInfo = new ImmutableMap.Builder<>();
    for (Map.Entry<String, Set<Method>> entry : foundSetters.entrySet()) {
      if (entry.getValue().size() == 1) {
        ParamInfo paramInfo =
            new ParamInfo(typeCoercerFactory, Iterables.getOnlyElement(entry.getValue()));
        allInfo.put(paramInfo.getName(), paramInfo);
        continue;
      }
      if (entry.getValue().size() > 2) {
        throw new IllegalStateException(
            String.format(
                "Builder for coercable type %s had %d setters named %s. Don't know how to coerce.",
                coercableType.getName(), entry.getValue().size(), entry.getKey()));
      }

      Method takesOptional = null;
      Method takesNonOptional = null;
      for (Method m : entry.getValue()) {
        if (OPTIONAL_TYPES.contains(m.getParameterTypes()[0])) {
          takesOptional = m;
        } else {
          takesNonOptional = m;
        }
      }
      if (takesOptional == null || takesNonOptional == null) {
        throw new IllegalStateException(
            String.format(
                "Builder for coercable type %s had 2 setters named %s but they were not Optional "
                    + "and non-Optional. Don't know how to coerce.",
                coercableType.getName(), entry.getKey()));
      }
      ParamInfo paramInfo = new ParamInfo(typeCoercerFactory, takesOptional);
      allInfo.put(paramInfo.getName(), paramInfo);
    }
    return allInfo.build();
  }
}
