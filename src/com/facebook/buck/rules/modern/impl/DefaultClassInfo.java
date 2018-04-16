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

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.modern.ClassInfo;
import com.facebook.buck.rules.modern.FieldInfo;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.facebook.buck.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.immutables.BuckStylePackageVisibleImmutable;
import com.facebook.buck.util.immutables.BuckStylePackageVisibleTuple;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.reflect.TypeToken;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Default implementation of ClassInfo. Computes values simply by visiting all referenced fields.
 */
public class DefaultClassInfo<T extends AddsToRuleKey> implements ClassInfo<T> {
  private final String type;
  private final Optional<ClassInfo<? super T>> superInfo;
  private final ImmutableList<FieldInfo<?>> fields;

  DefaultClassInfo(Class<?> clazz, Optional<ClassInfo<? super T>> superInfo) {
    this.type =
        CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, clazz.getSimpleName()).intern();
    this.superInfo = superInfo;

    Optional<Class<?>> immutableBase = findImmutableBase(clazz);
    ImmutableList.Builder<FieldInfo<?>> fieldsBuilder = ImmutableList.builder();
    if (immutableBase.isPresent()) {
      Optional<Class<?>> builder = findImmutableBuilder(clazz);
      ImmutableList<Field> parameterFields;
      if (builder.isPresent()) {
        parameterFields = parameterFieldsFromBuilder(builder.get(), clazz);
      } else {
        parameterFields = parameterFieldsFromConstructor(clazz);
      }
      ImmutableMap<Field, Method> parameterMethods = findMethodsForFields(parameterFields, clazz);
      parameterFields.forEach(
          (field) -> {
            Method method = Preconditions.checkNotNull(parameterMethods.get(field));
            AddToRuleKey addAnnotation = method.getAnnotation(AddToRuleKey.class);
            // TODO(cjhopman): Add @ExcludeFromRuleKey annotation and require that all fields are
            // either explicitly added or explicitly excluded.
            Optional<CustomFieldBehavior> customBehavior =
                Optional.ofNullable(method.getDeclaredAnnotation(CustomFieldBehavior.class));
            if (addAnnotation != null && !addAnnotation.stringify()) {
              Nullable methodNullable = method.getAnnotation(Nullable.class);
              boolean methodOptional = Optional.class.isAssignableFrom(method.getReturnType());
              fieldsBuilder.add(
                  forFieldWithBehavior(
                      field, methodNullable != null || methodOptional, customBehavior));
            } else {
              fieldsBuilder.add(excludedField(field, customBehavior));
            }
          });
    } else {
      for (final Field field : clazz.getDeclaredFields()) {
        Preconditions.checkArgument(
            Modifier.isFinal(field.getModifiers()),
            "All fields of a Buildable must be final (%s.%s)",
            clazz.getSimpleName(),
            field.getName());

        if (Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        field.setAccessible(true);

        Optional<CustomFieldBehavior> customBehavior =
            Optional.ofNullable(field.getDeclaredAnnotation(CustomFieldBehavior.class));
        AddToRuleKey addAnnotation = field.getAnnotation(AddToRuleKey.class);
        // TODO(cjhopman): Add @ExcludeFromRuleKey annotation and require that all fields are either
        // explicitly added or explicitly excluded.
        if (addAnnotation != null && !addAnnotation.stringify()) {
          fieldsBuilder.add(
              forFieldWithBehavior(
                  field, field.getAnnotation(Nullable.class) != null, customBehavior));
        } else {
          fieldsBuilder.add(excludedField(field, customBehavior));
        }
      }
    }

    if (clazz.isMemberClass()) {
      // TODO(cjhopman): This should also iterate over the outer class's class hierarchy.
      Class<?> outerClazz = clazz.getDeclaringClass();
      // I don't think this can happen, but if it does, this needs to be updated to handle it
      // correctly.
      Preconditions.checkArgument(
          !outerClazz.isAnonymousClass()
              && !outerClazz.isMemberClass()
              && !outerClazz.isLocalClass());
      for (final Field field : outerClazz.getDeclaredFields()) {
        field.setAccessible(true);
        if (!Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        Preconditions.checkArgument(
            Modifier.isFinal(field.getModifiers()),
            "All static fields of a Buildable's outer class must be final (%s.%s)",
            outerClazz.getSimpleName(),
            field.getName());
      }
    }
    this.fields = fieldsBuilder.build();
  }

  private ImmutableMap<Field, Method> findMethodsForFields(
      List<Field> parameterFields, Class<?> clazz) {
    Map<String, Field> possibleNamesMap = new LinkedHashMap<>();
    for (Field field : parameterFields) {
      String name = field.getName();
      String ending = name.substring(1);
      Character first = name.charAt(0);
      possibleNamesMap.put("get" + Character.toUpperCase(first) + ending, field);
      possibleNamesMap.put("is" + Character.toUpperCase(first) + ending, field);
      possibleNamesMap.put(name, field);
    }
    ImmutableMap.Builder<Field, Method> builder = ImmutableMap.builder();
    Queue<Class<?>> workQueue = new LinkedList<>();
    Set<Class<?>> seen = new HashSet<>();
    // Collect the superclasses first so that they are added before interfaces. That seems more
    // aesthetically pleasing to me.
    for (Class<?> current = clazz;
        !Object.class.equals(current);
        current = current.getSuperclass()) {
      workQueue.add(current);
    }
    while (!workQueue.isEmpty()) {
      Class<?> cls = workQueue.poll();
      if (seen.add(cls)) {
        workQueue.addAll(Arrays.asList(cls.getInterfaces()));
        if (cls != clazz) {
          for (final Method method : cls.getDeclaredMethods()) {
            Field match = possibleNamesMap.remove(method.getName());
            if (match != null) {
              builder.put(match, method);
            }
          }
        }
      }
    }
    ImmutableMap<Field, Method> result = builder.build();
    Set<Field> missing = Sets.difference(ImmutableSet.copyOf(parameterFields), result.keySet());
    Preconditions.checkState(
        missing.isEmpty(),
        new Object() {
          @Override
          public String toString() {
            return String.format(
                "Could not find methods for fields of class %s: <%s>",
                clazz.getName(),
                Joiner.on(", ").join(missing.stream().map(Field::getName).iterator()));
          }
        });
    return result;
  }

  private ImmutableList<Field> parameterFieldsFromConstructor(Class<?> clazz) {
    Constructor<?>[] constructors = clazz.getDeclaredConstructors();
    Preconditions.checkState(constructors.length > 0);
    for (Constructor<?> candidate : constructors) {
      candidate.setAccessible(true);
      Class<?>[] parameterTypes = candidate.getParameterTypes();
      Field[] declaredFields = clazz.getDeclaredFields();
      if (parameterTypes.length <= declaredFields.length) {
        ImmutableList.Builder<Field> fieldsBuilder = ImmutableList.builder();
        for (int i = 0; i < parameterTypes.length; i++) {
          Field field = declaredFields[i];
          field.setAccessible(true);
          fieldsBuilder.add(field);
        }
        return fieldsBuilder.build();
      }
    }
    throw new IllegalStateException();
  }

  private ImmutableList<Field> parameterFieldsFromBuilder(Class<?> builder, Class<?> clazz) {
    ImmutableList.Builder<Field> fieldsBuilder = ImmutableList.builder();
    for (final Field field : builder.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers())
          && !field.getName().equals("initBits")
          && !field.getName().equals("optBits")) {
        try {
          Field classField = clazz.getDeclaredField(field.getName());
          classField.setAccessible(true);
          fieldsBuilder.add(classField);
        } catch (NoSuchFieldException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    return fieldsBuilder.build();
  }

  private Optional<Class<?>> findImmutableBuilder(Class<?> clazz) {
    Class<?>[] classes = clazz.getDeclaredClasses();
    for (Class<?> inner : classes) {
      if (inner.getSimpleName().equals("Builder")) {
        return Optional.of(inner);
      }
    }
    return Optional.empty();
  }

  private static Optional<Class<?>> findImmutableBase(Class<?> clazz) {
    List<Class<?>> bases =
        Streams.concat(Stream.of(clazz.getSuperclass()), Arrays.stream(clazz.getInterfaces()))
            .filter(DefaultClassInfo::isImmutableBase)
            .collect(Collectors.toList());

    Preconditions.checkState(bases.size() < 2);
    return bases.isEmpty() ? Optional.empty() : Optional.of(bases.get(0));
  }

  private static boolean isImmutableBase(Class<?> clazz) {
    // Value.Immutable only has CLASS retention, so we need to detect this based on our own
    // annotations.
    return clazz.getAnnotation(BuckStyleImmutable.class) != null
        || clazz.getAnnotation(BuckStylePackageVisibleImmutable.class) != null
        || clazz.getAnnotation(BuckStylePackageVisibleTuple.class) != null
        || clazz.getAnnotation(BuckStyleTuple.class) != null;
  }

  @Override
  public String getType() {
    return type;
  }

  @Override
  public <E extends Exception> void visit(T value, ValueVisitor<E> visitor) throws E {
    if (superInfo.isPresent()) {
      superInfo.get().visit(value, visitor);
    }
    for (FieldInfo<?> extractor : fields) {
      extractor.visit(value, visitor);
    }
  }

  @Override
  public Optional<ClassInfo<? super T>> getSuperInfo() {
    return superInfo;
  }

  @Override
  public ImmutableCollection<FieldInfo<?>> getFieldInfos() {
    return fields;
  }

  static FieldInfo<?> forFieldWithBehavior(
      Field field, boolean isNullable, Optional<CustomFieldBehavior> customBehavior) {
    Type type = field.getGenericType();
    ValueTypeInfo<?> valueTypeInfo = ValueTypeInfoFactory.forTypeToken(TypeToken.of(type));
    if (isNullable) {
      valueTypeInfo = new NullableValueTypeInfo<>(valueTypeInfo);
    }
    return new FieldInfo<>(field, valueTypeInfo, customBehavior);
  }

  public static FieldInfo<?> excludedField(Field field, Optional<CustomFieldBehavior> behavior) {
    return new FieldInfo<>(field, ValueTypeInfos.ExcludedValueTypeInfo.INSTANCE, behavior);
  }
}
