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

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.modern.ClassInfo;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.common.base.Preconditions;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Creates and caches the default ClassInfo implementations. */
public class DefaultClassInfoFactory {
  private static final Logger LOG = Logger.get(DefaultClassInfo.class);

  /** Returns the ClassInfo for the object based on its runtime type. */
  public static <T extends AddsToRuleKey> ClassInfo<T> forInstance(T value) {
    @SuppressWarnings("unchecked")
    ClassInfo<T> classInfo = (ClassInfo<T>) getClassInfo(value.getClass());
    return classInfo;
  }

  private static final ConcurrentHashMap<Class<?>, ClassInfo<? extends AddsToRuleKey>> classesInfo =
      new ConcurrentHashMap<>();

  private static ClassInfo<?> getClassInfo(Class<?> clazz) {
    ClassInfo<?> info = classesInfo.get(clazz);
    if (info != null) {
      return info;
    }
    try {
      Preconditions.checkArgument(
          AddsToRuleKey.class.isAssignableFrom(clazz),
          "%s is not assignable to AddsToRuleKey.",
          clazz.getName());
      Class<?> superClazz = clazz.getSuperclass();
      if (isIgnoredBaseClass(superClazz)) {
        // This ensures that classesInfo holds an entry for the super class and computeClassInfo
        // can get it without having to modify the map.
        LOG.verbose(
            String.format(
                "Getting superclass %s info for %s.", clazz.getName(), superClazz.getName()));
        getClassInfo(superClazz);
      }
      info = classesInfo.computeIfAbsent(clazz, DefaultClassInfoFactory::computeClassInfo);
      return info;
    } catch (Exception e) {
      throw new BuckUncheckedExecutionException(
          e, "When getting class info for %s: %s", clazz.getName(), e.getMessage());
    }
  }

  private static <T extends AddsToRuleKey> ClassInfo<T> computeClassInfo(Class<?> clazz) {
    // Lambdas, anonymous and local classes can easily access variables that we can't find via
    // reflection.
    Preconditions.checkArgument(
        !clazz.isAnonymousClass(), "ModernBuildRules cannot be or reference anonymous classes.");
    Preconditions.checkArgument(
        !clazz.isLocalClass(), "ModernBuildRules cannot be or reference local classes.");
    Preconditions.checkArgument(
        !clazz.isSynthetic(), "ModernBuildRules cannot be or reference synthetic classes.");
    // We don't want to have to deal with inner non-static classes (and verifying usage of state
    // from the outer class).
    Preconditions.checkArgument(
        !clazz.isMemberClass() || Modifier.isStatic(clazz.getModifiers()),
        "ModernBuildRules cannot be or reference inner non-static classes.");

    Optional<ClassInfo<? super T>> superInfo = Optional.empty();
    Class<?> superClazz = clazz.getSuperclass();
    if (isIgnoredBaseClass(superClazz)) {
      // It's guaranteed that the superclass's info is already computed.
      @SuppressWarnings("unchecked")
      ClassInfo<? super T> superClazzInfo = (ClassInfo<? super T>) classesInfo.get(superClazz);
      superInfo = Optional.of(superClazzInfo);
    }
    return new DefaultClassInfo<>(clazz, superInfo);
  }

  private static boolean isIgnoredBaseClass(Class<?> superClazz) {
    return !superClazz.equals(Object.class) && !superClazz.equals(ModernBuildRule.class);
  }
}
