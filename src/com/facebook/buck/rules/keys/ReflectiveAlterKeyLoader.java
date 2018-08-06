/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleImmutable;
import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleTuple;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

class ReflectiveAlterKeyLoader extends CacheLoader<Class<?>, ImmutableCollection<AlterRuleKey>> {
  private static final Comparator<ValueExtractor> COMPARATOR =
      (o1, o2) -> {
        String name1 = o1.getFullyQualifiedName();
        String name2 = o2.getFullyQualifiedName();
        return name1.compareTo(name2);
      };

  @Override
  public ImmutableCollection<AlterRuleKey> load(Class<?> key) throws Exception {
    ImmutableList.Builder<AlterRuleKey> builder = ImmutableList.builder();
    List<Class<?>> superClasses = new ArrayList<>();

    // Collect the superclasses first so that they are added before interfaces. That seems more
    // aesthetically pleasing to me.
    for (Class<?> current = key; !Object.class.equals(current); current = current.getSuperclass()) {
      superClasses.add(current);
    }

    LinkedHashSet<Class<?>> superClassesAndInterfaces = new LinkedHashSet<>();
    Queue<Class<?>> workQueue = new LinkedList<>(superClasses);
    while (!workQueue.isEmpty()) {
      Class<?> cls = workQueue.poll();
      if (superClassesAndInterfaces.add(cls)) {
        workQueue.addAll(Arrays.asList(cls.getInterfaces()));
      }
    }

    for (Class<?> current : superClassesAndInterfaces) {
      ImmutableSortedMap.Builder<ValueExtractor, AlterRuleKey> sortedExtractors =
          ImmutableSortedMap.orderedBy(COMPARATOR);
      for (Field field : current.getDeclaredFields()) {
        field.setAccessible(true);
        AddToRuleKey annotation = field.getAnnotation(AddToRuleKey.class);
        if (annotation != null) {
          ValueExtractor valueExtractor = new FieldValueExtractor(field);
          sortedExtractors.put(
              valueExtractor, createAlterRuleKey(valueExtractor, annotation.stringify()));
        }
      }
      for (Method method : current.getDeclaredMethods()) {
        method.setAccessible(true);
        AddToRuleKey annotation = method.getAnnotation(AddToRuleKey.class);
        if (annotation != null) {
          Preconditions.checkState(
              hasImmutableAnnotation(current) && AddsToRuleKey.class.isAssignableFrom(current),
              "AddToRuleKey can only be applied to methods of Immutables. It cannot be applied to %s.%s(...)",
              current.getName(),
              method.getName());

          ValueExtractor valueExtractor = new ValueMethodValueExtractor(method);
          sortedExtractors.put(
              valueExtractor, createAlterRuleKey(valueExtractor, annotation.stringify()));
        }
      }
      builder.addAll(sortedExtractors.build().values());
    }
    return builder.build();
  }

  private boolean hasImmutableAnnotation(Class<?> current) {
    // Value.Immutable only has CLASS retention, so we need to detect this based on our own
    // annotations.
    return current.getAnnotation(BuckStyleImmutable.class) != null
        || current.getAnnotation(BuckStylePackageVisibleImmutable.class) != null
        || current.getAnnotation(BuckStylePackageVisibleTuple.class) != null
        || current.getAnnotation(BuckStyleTuple.class) != null;
  }

  private AlterRuleKey createAlterRuleKey(ValueExtractor valueExtractor, boolean stringify) {
    if (stringify) {
      return new StringifyAlterRuleKey(valueExtractor);
    } else {
      return new DefaultAlterRuleKey(valueExtractor);
    }
  }
}
