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

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleImmutable;
import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleTuple;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.rules.modern.ClassInfo;
import com.facebook.buck.rules.modern.FieldInfo;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.common.reflect.TypeToken;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
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
  private static final Logger LOG = Logger.get(DefaultClassInfo.class);

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
      ImmutableMap<Field, Boolean> parameterFields = parameterFieldsFromFields(clazz);
      ImmutableMap<Field, Method> parameterMethods =
          findMethodsForFields(parameterFields.keySet(), clazz);
      parameterFields.forEach(
          (field, isLazy) -> {
            Method method = parameterMethods.get(field);
            if (method == null) {
              return;
            }
            AddToRuleKey addAnnotation = method.getAnnotation(AddToRuleKey.class);
            // TODO(cjhopman): Add @ExcludeFromRuleKey annotation and require that all fields are
            // either explicitly added or explicitly excluded.
            Optional<CustomFieldBehavior> customBehavior =
                Optional.ofNullable(method.getDeclaredAnnotation(CustomFieldBehavior.class));
            if (addAnnotation != null && !addAnnotation.stringify()) {
              if (isLazy) {
                throw new RuntimeException(
                    "@Value.Lazy fields cannot be @AddToRuleKey, change it to @Value.Derived.");
              }
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
        // TODO(cjhopman): Make this a Precondition.
        if (!Modifier.isFinal(field.getModifiers())) {
          LOG.warn(
              "All fields of a Buildable must be final (%s.%s)",
              clazz.getSimpleName(), field.getName());
        }

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
        // TODO(cjhopman): Make this a Precondition.
        if (!Modifier.isFinal(field.getModifiers())) {
          LOG.warn(
              "All static fields of a Buildable's outer class must be final (%s.%s)",
              outerClazz.getSimpleName(), field.getName());
        }
      }
    }
    this.fields = fieldsBuilder.build();
  }

  private ImmutableMap<Field, Method> findMethodsForFields(
      Iterable<Field> parameterFields, Class<?> clazz) {
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
    List<Field> badFields = new ArrayList<>();
    for (Field field : parameterFields) {
      if (result.containsKey(field)) {
        // Found a match.
        continue;
      }
      if (field.getName().equals("hashCode")) {
        // From prehash=true.
        continue;
      }
      badFields.add(field);
    }

    Preconditions.checkState(
        badFields.isEmpty(),
        new Object() {
          @Override
          public String toString() {
            return String.format(
                "Could not find methods for fields of class %s: <%s>",
                clazz.getName(),
                Joiner.on(", ").join(badFields.stream().map(Field::getName).iterator()));
          }
        });
    return result;
  }

  private ImmutableMap<Field, Boolean> parameterFieldsFromFields(Class<?> clazz) {
    Field[] fields = clazz.getDeclaredFields();
    ImmutableMap.Builder<Field, Boolean> fieldsBuilder =
        ImmutableMap.builderWithExpectedSize(fields.length);
    Field ignoredField = null;
    for (Field field : fields) {
      field.setAccessible(true);
      // static fields can be ignored and volatile fields are used for handling lazy field states.
      if (Modifier.isStatic(field.getModifiers()) || Modifier.isVolatile(field.getModifiers())) {
        ignoredField = field;
        continue;
      }
      boolean isLazy = ignoredField != null && ignoredField.getName().endsWith("LAZY_INIT_BIT");
      fieldsBuilder.put(field, isLazy);
    }
    return fieldsBuilder.build();
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
