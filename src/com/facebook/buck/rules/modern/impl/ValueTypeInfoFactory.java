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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.Toolchain;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.PublicOutputPath;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.impl.ValueTypeInfos.ImmutableListValueTypeInfo;
import com.facebook.buck.rules.modern.impl.ValueTypeInfos.OptionalValueTypeInfo;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
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
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Creates ValueTypeInfos for given Types/TypeTokens. */
public class ValueTypeInfoFactory {

  private static final ConcurrentHashMap<Type, ValueTypeInfo<?>> typeInfos =
      new ConcurrentHashMap<>();

  @SuppressWarnings("unchecked")
  public static <T> ValueTypeInfo<T> forTypeToken(TypeToken<T> typeToken) {
    return (ValueTypeInfo<T>) forType(typeToken.getType());
  }

  // TODO(cjhopman): Figure out if we can use TypeToken throughout.
  static ValueTypeInfo<?> forType(Type type) {
    ValueTypeInfo<?> info = typeInfos.get(type);
    if (info != null) {
      return info;
    }
    try {
      if (type instanceof ParameterizedType
          && !AddsToRuleKey.class.isAssignableFrom(
              (Class<?>) ((ParameterizedType) type).getRawType())) {
        for (Type t : ((ParameterizedType) type).getActualTypeArguments()) {
          // Ensure that each required type argument's ValueTypeInfo is already computed.
          forType(t);
        }
      }
      return typeInfos.computeIfAbsent(type, ValueTypeInfoFactory::computeTypeInfo);
    } catch (Exception t) {
      throw new BuckUncheckedExecutionException(
          t, "When getting type info for type " + type.getTypeName());
    }
  }

  /**
   * A simple type includes no input/output path/data and is either a very simple type (primitives,
   * strings, etc) or one of the supported generic types composed of other simple types.
   */
  static boolean isSimpleType(Type type) {
    if (type instanceof Class) {
      Class<?> rawClass = Primitives.wrap((Class<?>) type);
      // These types need no processing for
      return rawClass.equals(String.class)
          || rawClass.equals(Character.class)
          || rawClass.equals(Boolean.class)
          || rawClass.equals(Byte.class)
          || rawClass.equals(Short.class)
          || rawClass.equals(Integer.class)
          || rawClass.equals(Long.class)
          || rawClass.equals(Float.class)
          || rawClass.equals(Double.class)
          || rawClass.equals(OptionalInt.class);
    } else if (type instanceof WildcardType) {
      WildcardType wildcardType = (WildcardType) type;
      Type[] upperBounds = wildcardType.getUpperBounds();
      Preconditions.checkState(upperBounds.length == 1);
      return false;
    }
    return false;
  }

  private static ValueTypeInfo<?> computeTypeInfo(Type type) {
    Preconditions.checkArgument(!(type instanceof TypeVariable));
    if (type instanceof WildcardType) {
      WildcardType wildcardType = (WildcardType) type;
      Type[] upperBounds = wildcardType.getUpperBounds();
      Preconditions.checkState(upperBounds.length == 1);
      type = upperBounds[0];
    }
    Preconditions.checkArgument(!(type instanceof WildcardType));

    if (isSimpleType(type)) {
      return ValueTypeInfos.forSimpleType(type);
    } else if (type instanceof Class) {
      Class<?> rawClass = Primitives.wrap((Class<?>) type);
      if (rawClass.equals(Path.class)) {
        throw new IllegalArgumentException(
            "Buildables should not have Path references. Use SourcePath or OutputPath instead");
      }

      if (rawClass.isEnum()) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        EnumValueTypeInfo enumValueTypeInfo = new EnumValueTypeInfo(rawClass);
        return enumValueTypeInfo;
      } else if (SourcePath.class.isAssignableFrom(rawClass)) {
        return SourcePathValueTypeInfo.INSTANCE;
      } else if (rawClass.equals(OutputPath.class) || rawClass.equals(PublicOutputPath.class)) {
        return ValueTypeInfos.OutputPathValueTypeInfo.INSTANCE;
      } else if (NonHashableSourcePathContainer.class.isAssignableFrom(rawClass)) {
        return new NonHashableSourcePathContainerValueTypeInfo();
      } else if (BuildTarget.class.isAssignableFrom(rawClass)) {
        return BuildTargetTypeInfo.INSTANCE;
      } else if (UnconfiguredBuildTargetView.class.isAssignableFrom(rawClass)) {
        return UnconfiguredBuildTargetTypeInfo.INSTANCE;
      } else if (TargetConfiguration.class.isAssignableFrom(rawClass)) {
        return TargetConfigurationTypeInfo.INSTANCE;
      } else if (Pattern.class.isAssignableFrom(rawClass)) {
        return PatternValueTypeInfo.INSTANCE;
      } else if (Toolchain.class.isAssignableFrom(rawClass)) {
        @SuppressWarnings("unchecked")
        Class<? extends Toolchain> asToolchain = (Class<? extends Toolchain>) rawClass;
        return new ToolchainTypeInfo<>(asToolchain);
      } else if (AddsToRuleKey.class.isAssignableFrom(rawClass)) {
        return DynamicTypeInfo.INSTANCE;
      }
    } else if (type instanceof ParameterizedType) {
      // This is a parameterized type where one of the parameters requires special handling (i.e.
      // it has input/output path/data).
      ParameterizedType parameterizedType = (ParameterizedType) type;
      Type rawType = parameterizedType.getRawType();
      Preconditions.checkState(rawType instanceof Class<?>);
      Class<?> rawClass = (Class<?>) rawType;

      Type[] typeArguments = ((ParameterizedType) type).getActualTypeArguments();
      if (rawClass.equals(Either.class)) {
        Preconditions.checkState(typeArguments.length == 2);
        return new EitherValueTypeInfo<>(forType(typeArguments[0]), forType(typeArguments[1]));
      } else if (rawClass.equals(Pair.class)) {
        // TODO(cjhopman): handle Pair
        throw new UnsupportedOperationException();
      } else if (Supplier.class.isAssignableFrom(rawClass)) {
        Preconditions.checkState(typeArguments.length == 1);
        return new SupplierValueTypeInfo<>(forType(typeArguments[0]));
      } else if (rawClass.equals(ImmutableList.class)) {
        Preconditions.checkState(typeArguments.length == 1);
        return new ImmutableListValueTypeInfo<>(forType(typeArguments[0]));
      } else if (rawClass.equals(ImmutableSortedSet.class)) {
        Preconditions.checkState(typeArguments.length == 1);
        return new ImmutableSortedSetValueTypeInfo<>(forType(typeArguments[0]));
      } else if (rawClass.equals(ImmutableSortedMap.class)) {
        Preconditions.checkState(typeArguments.length == 2);
        return new ImmutableSortedMapValueTypeInfo<>(
            forType(typeArguments[0]), forType(typeArguments[1]));
      } else if (rawClass.equals(ImmutableSet.class)) {
        Preconditions.checkState(typeArguments.length == 1);
        return new ImmutableSetValueTypeInfo<>(forType(typeArguments[0]));
      } else if (rawClass.equals(ImmutableMap.class)) {
        Preconditions.checkState(typeArguments.length == 2);
        return new ImmutableMapValueTypeInfo<>(
            forType(typeArguments[0]), forType(typeArguments[1]));
      } else if (rawClass.equals(Optional.class)) {
        Preconditions.checkState(typeArguments.length == 1);
        return new OptionalValueTypeInfo<>(forType(typeArguments[0]));
      } else if (AddsToRuleKey.class.isAssignableFrom(rawClass)) {
        return DynamicTypeInfo.INSTANCE;
      }
    }
    throw new IllegalArgumentException("Cannot create ValueTypeInfo for type: " + type);
  }
}
