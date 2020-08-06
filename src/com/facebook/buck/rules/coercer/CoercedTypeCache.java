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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.description.arg.DataTransferObject;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.util.Types;
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

/** Caches the set of possible {@link ParamInfo}s for each param on a coercable type. */
class CoercedTypeCache {

  public static final ImmutableSet<Class<?>> OPTIONAL_TYPES =
      ImmutableSet.of(Optional.class, OptionalInt.class, OptionalLong.class, OptionalDouble.class);

  private final TypeCoercerFactory typeCoercerFactory;

  /**
   * Returns an unpopulated DTO object, and the build method which must be called with it when it is
   * finished being populated.
   */
  @SuppressWarnings("unchecked")
  <T extends DataTransferObject> DataTransferObjectDescriptor<T> getConstructorArgDescriptor(
      Class<T> dtoType) {
    return (DataTransferObjectDescriptor<T>) constructorArgDescriptorCache.getUnchecked(dtoType);
  }

  private final LoadingCache<Class<? extends DataTransferObject>, DataTransferObjectDescriptor<?>>
      constructorArgDescriptorCache;

  CoercedTypeCache(TypeCoercerFactory typeCoercerFactory) {
    this.typeCoercerFactory = typeCoercerFactory;

    constructorArgDescriptorCache =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<
                    Class<? extends DataTransferObject>, DataTransferObjectDescriptor<?>>() {
                  @Override
                  public DataTransferObjectDescriptor<?> load(
                      Class<? extends DataTransferObject> dtoType) {
                    return newConstructorArgDescriptor(dtoType);
                  }
                });
  }

  @SuppressWarnings("unchecked")
  private <T extends DataTransferObject>
      DataTransferObjectDescriptor<T> newConstructorArgDescriptor(Class<T> dtoType) {
    try {
      Method builderMethod = dtoType.getMethod("builder");
      Method buildMethod = builderMethod.getReturnType().getMethod("build");
      return ImmutableDataTransferObjectDescriptor.of(
          dtoType,
          () -> {
            try {
              return builderMethod.invoke(null);
            } catch (IllegalAccessException | InvocationTargetException e) {
              throw new IllegalStateException(
                  String.format(
                      "Could not instantiate immutable constructor arg type %s: %s",
                      dtoType, e.getMessage()),
                  e);
            }
          },
          paramTypes(dtoType),
          x -> {
            try {
              return (T) buildMethod.invoke(x);
            } catch (IllegalAccessException e) {
              throw new IllegalStateException(
                  String.format("Error building immutable constructor arg: %s", e.getMessage()), e);
            } catch (InvocationTargetException e) {
              if (e.getCause() instanceof IllegalStateException) {
                IllegalStateException cause = (IllegalStateException) e.getCause();
                if (cause.getMessage().contains("Cannot build")
                    && cause.getMessage().contains("required")) {
                  List<String> matches =
                      Splitter.on(CharMatcher.anyOf("[]")).splitToList(cause.getMessage());
                  if (matches.size() >= 2) {
                    throw new DataTransferObjectDescriptor.BuilderBuildFailedException(
                        String.format("missing required argument(s): %s", matches.get(1)));
                  }
                }
              }
              throw new RuntimeException(
                  String.format(
                      "Error building immutable constructor: %s", e.getCause().getMessage()),
                  e.getCause());
            }
          });
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(
          String.format(
              "Could not instantiate immutable constructor arg type %s: %s",
              dtoType, e.getMessage()),
          e);
    }
  }

  private ImmutableMap<String, ParamInfo<?>> paramTypes(Class<?> coercableType) {
    if (Types.getSupertypes(coercableType).stream()
        .noneMatch(c -> c.getAnnotation(RuleArg.class) != null)) {
      // Sniff for @BuckStyleImmutable not @RuleArg because the
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
    return extractForImmutableBuilder(builderType);
  }

  @VisibleForTesting
  ImmutableMap<String, ParamInfo<?>> extractForImmutableBuilder(Class<?> coercableType) {
    Map<String, Set<Method>> foundSetters = new HashMap<>();
    for (Method method : coercableType.getDeclaredMethods()) {
      if (!method.getName().startsWith("set")) {
        continue;
      }
      foundSetters.putIfAbsent(method.getName(), new HashSet<>());
      foundSetters.get(method.getName()).add(method);
    }
    ImmutableMap.Builder<String, ParamInfo<?>> allInfo = new ImmutableMap.Builder<>();
    for (Map.Entry<String, Set<Method>> entry : foundSetters.entrySet()) {
      if (entry.getValue().size() == 1) {
        ParamInfo<?> paramInfo =
            ReflectionParamInfo.of(typeCoercerFactory, Iterables.getOnlyElement(entry.getValue()));
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
      ParamInfo<?> paramInfo = ReflectionParamInfo.of(typeCoercerFactory, takesOptional);
      allInfo.put(paramInfo.getName(), paramInfo);
    }
    return allInfo.build();
  }
}
