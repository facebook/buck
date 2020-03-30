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

package com.facebook.buck.rules.keys;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rulekey.MissingExcludeReporter;
import com.facebook.buck.core.rules.actions.AbstractAction;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.providers.annotations.ImmutableInfo;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Stream;

class ReflectiveAlterKeyLoader extends CacheLoader<Class<?>, ImmutableCollection<AlterRuleKey>> {
  private static final Comparator<ValueExtractor> COMPARATOR =
      (o1, o2) -> {
        String name1 = o1.getFullyQualifiedName();
        String name2 = o2.getFullyQualifiedName();
        return name1.compareTo(name2);
      };

  @Override
  @SuppressWarnings("unchecked")
  public ImmutableCollection<AlterRuleKey> load(Class<?> key) {
    ImmutableList.Builder<AlterRuleKey> builder = ImmutableList.builder();
    List<Class<?>> superClasses = new ArrayList<>();

    // Collect the superclasses first so that they are added before interfaces. That seems more
    // aesthetically pleasing to me.
    for (Class<?> current = key; isBuckType(current); current = current.getSuperclass()) {
      superClasses.add(current);
    }

    LinkedHashSet<Class<?>> superClassesAndInterfaces = new LinkedHashSet<>();
    Queue<Class<?>> workQueue = new LinkedList<>(superClasses);
    while (!workQueue.isEmpty()) {
      Class<?> cls = workQueue.poll();
      if (superClassesAndInterfaces.add(cls)) {
        Stream.of(cls.getInterfaces()).filter(x -> isBuckType(x)).forEach(workQueue::add);
      }
    }

    for (Class<?> current : superClassesAndInterfaces) {
      ImmutableSortedMap.Builder<ValueExtractor, AlterRuleKey> sortedExtractors =
          ImmutableSortedMap.orderedBy(COMPARATOR);

      if (Action.class.isAssignableFrom(key)) {
        getExtractorsForActions((Class<? extends Action>) current, sortedExtractors);
      } else {
        getExtractorsForObject(key, current, sortedExtractors);
      }
      builder.addAll(sortedExtractors.build().values());
    }
    return builder.build();
  }

  private void getExtractorsForActions(
      Class<? extends Action> current,
      ImmutableSortedMap.Builder<ValueExtractor, AlterRuleKey> sortedExtractors) {
    for (Field field : current.getDeclaredFields()) {
      Preconditions.checkArgument(
          Modifier.isFinal(field.getModifiers()),
          "All fields of Action must be final but %s.%s is not.",
          current.getSimpleName(),
          field.getName());
      AddToRuleKey annotation = field.getAnnotation(AddToRuleKey.class);
      if (annotation == null) {
        // Only AbstractAction gets to hide fields from its rulekey.
        if (AbstractAction.class.equals(current)) {
          continue;
        }
        throw new RuntimeException(
            String.format(
                "All fields of Action must annotated with @AddToRuleKey.",
                current.getSimpleName(),
                field.getName()));
      }
      field.setAccessible(true);
      ValueExtractor valueExtractor = new FieldValueExtractor(field);
      sortedExtractors.put(valueExtractor, createAlterRuleKey(valueExtractor, false));
    }
  }

  private void getExtractorsForObject(
      Class<?> key,
      Class<?> current,
      ImmutableSortedMap.Builder<ValueExtractor, AlterRuleKey> sortedExtractors) {
    for (Field field : current.getDeclaredFields()) {
      field.setAccessible(true);
      AddToRuleKey annotation = field.getAnnotation(AddToRuleKey.class);
      if (annotation != null) {
        ValueExtractor valueExtractor = new FieldValueExtractor(field);
        sortedExtractors.put(
            valueExtractor, createAlterRuleKey(valueExtractor, annotation.stringify()));
      } else {
        ExcludeFromRuleKey excludeAnnotation = field.getAnnotation(ExcludeFromRuleKey.class);
        if (excludeAnnotation != null) {
          MissingExcludeReporter.reportExcludedField(key, field, excludeAnnotation);
        } else {
          MissingExcludeReporter.reportFieldMissingAnnotation(key, field);
        }
      }
    }
    for (Method method : current.getDeclaredMethods()) {
      method.setAccessible(true);
      AddToRuleKey annotation = method.getAnnotation(AddToRuleKey.class);
      if (annotation != null) {
        if (method.isSynthetic()) {
          continue;
        }

        Preconditions.checkState(
            hasImmutableAnnotation(current) && AddsToRuleKey.class.isAssignableFrom(current),
            "AddToRuleKey can only be applied to methods of Immutables. It cannot be applied to %s.%s(...)",
            current.getName(),
            method.getName());

        ValueExtractor valueExtractor = new ValueMethodValueExtractor(method);
        sortedExtractors.put(
            valueExtractor, createAlterRuleKey(valueExtractor, annotation.stringify()));
      }
      // For methods, we're unable here to determine whether we expect that a method should or
      // shouldn't have an annotation.
    }
  }

  private static boolean isBuckType(Class<?> current) {
    return current.getName().startsWith("com.facebook.buck.");
  }

  private boolean hasImmutableAnnotation(Class<?> current) {
    // Value.Immutable only has CLASS retention, so we need to detect this based on our own
    // annotations.
    return current.getAnnotation(RuleArg.class) != null
        || current.getAnnotation(BuckStylePrehashedValue.class) != null
        || current.getAnnotation(BuckStyleValueWithBuilder.class) != null
        || current.getAnnotation(BuckStyleValue.class) != null
        || current.getAnnotation(ImmutableInfo.class) != null;
  }

  private AlterRuleKey createAlterRuleKey(ValueExtractor valueExtractor, boolean stringify) {
    if (stringify) {
      return new StringifyAlterRuleKey(valueExtractor);
    } else {
      return new DefaultAlterRuleKey(valueExtractor);
    }
  }
}
