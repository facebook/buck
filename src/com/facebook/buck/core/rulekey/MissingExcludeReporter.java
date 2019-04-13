/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rulekey;

import com.facebook.buck.core.util.log.Logger;
import com.google.common.collect.Sets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * Utility for reporting issues of fields/methods not being annotated to be added to rulekeys.
 *
 * <p>This will only log a violation for a specific field/method once.
 */
public class MissingExcludeReporter {
  private static final Logger LOG = Logger.get(MissingExcludeReporter.class);

  private static final Set<Field> reportedFields = Sets.newConcurrentHashSet();
  private static final Set<Method> reportedMethods = Sets.newConcurrentHashSet();

  // TODO(cjhopman): We'd like to make the missing cases to be LOG.warn so that we can get soft
  // errors for them, but they are too numerous right now.

  /**
   * Report a field that's being processed for rulekeys and is missing both @{@link AddToRuleKey}
   * and @{@link ExcludeFromRuleKey}.
   */
  public static void reportFieldMissingAnnotation(Class<?> instanceClass, Field field) {
    if (reportedFields.add(field)) {
      if (!shouldReport(instanceClass)) {
        return;
      }
      Class<?> declaringClass = field.getDeclaringClass();
      LOG.debug(
          "Field %s.%s should be annotated with @AddToRuleKey or @ExcludeFromRuleKey%s.",
          declaringClass.getName(),
          field.getName(),
          maybeGetAddedFromInstanceMessage(declaringClass, instanceClass));
    }
  }

  /**
   * Report a method that's being processed for rulekeys and is missing both @{@link AddToRuleKey}
   * and @{@link ExcludeFromRuleKey}.
   */
  public static void reportMethodMissingAnnotation(Class<?> instanceClass, Method method) {
    if (reportedMethods.add(method)) {
      if (!shouldReport(instanceClass)) {
        return;
      }
      Class<?> declaringClass = method.getDeclaringClass();
      LOG.debug(
          "Method %s.%s should be annotated with @AddToRuleKey or @ExcludeFromRuleKey (added from instance of %s).",
          declaringClass.getName(), method.getName(), instanceClass.getName());
    }
  }

  /**
   * Report a field that's being processed for rulekeys and is annotated with @{@link
   * ExcludeFromRuleKey}.
   */
  public static void reportExcludedField(
      Class<?> instanceClass, Field field, ExcludeFromRuleKey annotation) {
    if (!annotation.shouldReport()) {
      return;
    }
    if (reportedFields.add(field)) {
      if (!shouldReport(instanceClass)) {
        return;
      }
      Class<?> declaringClass = field.getDeclaringClass();
      LOG.debug(
          "Method %s.%s is excluded from rulekeys%s. This is generally "
              + "bad practice and may cause correctness issues. Reason provided: %s",
          declaringClass.getName(),
          field.getName(),
          maybeGetAddedFromInstanceMessage(declaringClass, instanceClass),
          annotation.value());
    }
  }

  /**
   * Report a method that's being processed for rulekeys and is annotated with @{@link
   * ExcludeFromRuleKey}.
   */
  public static void reportExcludedMethod(
      Class<?> instanceClass, Method method, ExcludeFromRuleKey annotation) {
    if (!annotation.shouldReport()) {
      return;
    }
    if (reportedMethods.add(method)) {
      if (!shouldReport(instanceClass)) {
        return;
      }
      Class<?> declaringClass = method.getDeclaringClass();
      LOG.debug(
          "Method %s.%s is excluded from rulekeys (added from instance of %s). This is generally "
              + "bad practice and may cause correctness issues. Reason provided: %s",
          declaringClass.getName(), method.getName(), instanceClass.getName(), annotation.value());
    }
  }

  private static boolean shouldReport(Class<?> instanceClass) {
    return !AllowsNonAnnotatedFields.class.isAssignableFrom(instanceClass);
  }

  private static String maybeGetAddedFromInstanceMessage(
      Class<?> declaringClass, Class<?> instanceClass) {
    return declaringClass == instanceClass
        ? ""
        : String.format(" (added from instance of %s)", declaringClass.getName());
  }
}
