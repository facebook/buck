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

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.primitives.Primitives;
import com.google.common.reflect.TypeToken;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

class FieldTypeInfoFactory {
  private static final ConcurrentHashMap<Type, FieldTypeInfo<?>> fieldTypesInfo =
      new ConcurrentHashMap<>();

  @SuppressWarnings("unchecked")
  static <T> FieldTypeInfo<T> forFieldTypeToken(TypeToken<T> typeToken) {
    return (FieldTypeInfo<T>) forFieldType(typeToken.getType());
  }

  // TODO(cjhopman): Figure out if we can use TypeToken throughout.
  static FieldTypeInfo<?> forFieldType(Type type) {
    FieldTypeInfo<?> info = fieldTypesInfo.get(type);
    if (info != null) {
      return info;
    }
    try {
      if (type instanceof ParameterizedType) {
        for (Type t : ((ParameterizedType) type).getActualTypeArguments()) {
          // Ensure that each required type argument's FieldTypeInfo is already computed.
          forFieldType(t);
        }
      }
      return fieldTypesInfo.computeIfAbsent(type, FieldTypeInfoFactory::computeFieldTypeInfo);
    } catch (Exception t) {
      throw new RuntimeException(
          "Failed getting field type info for type " + type.getTypeName(), t);
    }
  }

  /**
   * A simple type includes no input/output path/data and is either a very simple type (primitives,
   * strings, etc) or one of the supported generic types composed of other simple types.
   */
  static boolean isSimpleType(Type type) {
    // TODO(cjhopman): enum should probably be considered a simple type.
    if (type instanceof Class) {
      Class<?> rawClass = Primitives.wrap((Class<?>) type);
      // These types need no processing for
      return rawClass.equals(String.class)
          || rawClass.equals(Boolean.class)
          || rawClass.equals(Byte.class)
          || rawClass.equals(Short.class)
          || rawClass.equals(Integer.class)
          || rawClass.equals(Long.class)
          || rawClass.equals(Float.class)
          || rawClass.equals(Double.class);
    } else if (type instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) type;
      checkSupportedGeneric(parameterizedType);
      Type[] typeArguments = parameterizedType.getActualTypeArguments();
      return Arrays.stream(typeArguments).allMatch(FieldTypeInfoFactory::isSimpleType);
    }
    throw new IllegalArgumentException(
        String.format("%s is not a Class or ParameterizedType.", type.getTypeName()));
  }

  static void checkSupportedGeneric(ParameterizedType parameterizedType) {
    Type rawType = parameterizedType.getRawType();
    if (rawType instanceof Class<?>) {
      Class<?> rawClass = (Class<?>) rawType;
      if (rawClass.isAssignableFrom(ImmutableSet.class)) {
        throw new IllegalArgumentException(
            "Don't use ImmutableSet in Buildables. Use ImmutableSortedSet instead.");
      } else if (rawClass.isAssignableFrom(ImmutableMap.class)) {
        throw new IllegalArgumentException(
            "Don't use ImmutableMap in Buildables. Use ImmutableSortedMap instead.");
      } else if (rawClass.equals(Either.class)
          || rawClass.equals(Pair.class)
          || rawClass.equals(ImmutableList.class)
          || rawClass.equals(ImmutableSortedSet.class)
          || rawClass.equals(ImmutableSortedMap.class)
          || rawClass.equals(Optional.class)) {
        return;
      }
    }
    throw new IllegalArgumentException("Unsupported type: " + parameterizedType);
  }

  private static FieldTypeInfo<?> computeFieldTypeInfo(Type type) {
    Preconditions.checkArgument(!(type instanceof TypeVariable));
    Preconditions.checkArgument(!(type instanceof WildcardType));

    if (isSimpleType(type)) {
      return FieldTypeInfos.SimpleFieldTypeInfo.INSTANCE;
    } else if (type instanceof Class) {
      Class<?> rawClass = Primitives.wrap((Class<?>) type);
      if (rawClass.equals(Path.class)) {
        throw new IllegalArgumentException(
            "Buildables should not have Path fields. Use SourcePath or OutputPath instead");
      } else if (SourcePath.class.isAssignableFrom(rawClass)) {
        return SourcePathFieldTypeInfo.INSTANCE;
      }

      if (rawClass.isEnum()) {
        // TODO(cjhopman): handle enums
        throw new UnsupportedOperationException();
      }

      if (rawClass.equals(OutputPath.class)) {
        return FieldTypeInfos.OutputPathFieldTypeInfo.INSTANCE;
      }
    } else if (type instanceof ParameterizedType) {
      // This is a parameterized type where one of the parameters requires special handling (i.e.
      // it has input/output path/data).
      ParameterizedType parameterizedType = (ParameterizedType) type;
      Type rawType = parameterizedType.getRawType();
      Preconditions.checkState(rawType instanceof Class<?>);
      Class<?> rawClass = (Class<?>) rawType;

      if (((Class<?>) rawType).isAssignableFrom(ImmutableSet.class)) {
        throw new IllegalArgumentException(
            "Don't use ImmutableSet in Buildables. Use ImmutableSortedSet instead.");
      } else if (((Class<?>) rawType).isAssignableFrom(ImmutableMap.class)) {
        throw new IllegalArgumentException(
            "Don't use ImmutableMap in Buildables. Use ImmutableSortedMap instead.");
      } else if (rawClass.equals(Either.class)) {
        // TODO(cjhopman): handle Either
        throw new UnsupportedOperationException();
      } else if (rawClass.equals(Pair.class)) {
        // TODO(cjhopman): handle Pair
        throw new UnsupportedOperationException();
      } else if (rawClass.equals(ImmutableList.class)) {
        Type[] typeArguments = ((ParameterizedType) type).getActualTypeArguments();
        Preconditions.checkState(typeArguments.length == 1);
        return new FieldTypeInfos.IterableFieldTypeInfo<>(forFieldType(typeArguments[0]));
      } else if (rawClass.equals(ImmutableSortedSet.class)) {
        // SortedSet is tested second because it is a subclass of Set, and therefore can
        // be assigned to something of type Set, but not vice versa.
        Type[] typeArguments = ((ParameterizedType) type).getActualTypeArguments();
        Preconditions.checkState(typeArguments.length == 1);
        return new FieldTypeInfos.IterableFieldTypeInfo<>(forFieldType(typeArguments[0]));
      } else if (rawClass.equals(ImmutableSortedMap.class)) {
        // TODO(cjhopman): handle ImmutableSortedMap
        throw new UnsupportedOperationException();
      } else if (rawClass.equals(Optional.class)) {
        Type[] typeArguments = ((ParameterizedType) type).getActualTypeArguments();
        Preconditions.checkState(typeArguments.length == 1);
        return new FieldTypeInfos.OptionalFieldTypeInfo<>(forFieldType(typeArguments[0]));
      }
    }
    throw new IllegalArgumentException("Cannot create FieldTypeInfo for type: " + type);
  }
}
