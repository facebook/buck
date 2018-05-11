/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.modern;

import com.facebook.buck.core.rules.modern.annotations.CustomClassBehavior;
import com.facebook.buck.core.rules.modern.annotations.CustomClassBehaviorTag;
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehaviorTag;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Utilities for dealing with CustomFieldBehavior and CustomClassBehavior. */
public class CustomBehaviorUtils {
  /** Returns the class behavior of the requested type (if there is one). */
  public static <C extends CustomClassBehaviorTag> Optional<CustomClassBehaviorTag> getBehavior(
      Class<?> clazz, Class<C> behaviorClass) {
    CustomClassBehavior behavior = clazz.getAnnotation(CustomClassBehavior.class);
    if (behavior == null) {
      return Optional.empty();
    }

    List<Class<? extends CustomClassBehaviorTag>> matches =
        RichStream.from(behavior.value())
            .filter(behaviorClass::isAssignableFrom)
            .collect(Collectors.toList());
    if (matches.isEmpty()) {
      return Optional.empty();
    }
    Preconditions.checkState(matches.size() == 1);
    Class<? extends CustomClassBehaviorTag> tag = matches.get(0);

    try {
      Constructor<? extends CustomClassBehaviorTag> constructor = tag.getDeclaredConstructor();
      constructor.setAccessible(true);
      return Optional.of(constructor.newInstance());
    } catch (NoSuchMethodException
        | IllegalAccessException
        | InstantiationException
        | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  /** Returns the field behavior behavior of the requested type (if there is one). */
  public static <U extends CustomFieldBehaviorTag> Optional<U> get(
      Optional<CustomFieldBehavior> behavior, Class<U> behaviorClass) {
    return behavior.flatMap(b -> get(b, behaviorClass));
  }

  /** Returns the field behavior behavior of the requested type (if there is one). */
  public static <U extends CustomFieldBehaviorTag> Optional<U> get(
      CustomFieldBehavior behavior, Class<U> behaviorClass) {
    if (behavior == null) {
      return Optional.empty();
    }

    List<Class<? extends CustomFieldBehaviorTag>> matches =
        RichStream.from(behavior.value())
            .filter(behaviorClass::isAssignableFrom)
            .collect(Collectors.toList());
    if (matches.isEmpty()) {
      return Optional.empty();
    }
    Preconditions.checkState(matches.size() == 1);
    @SuppressWarnings("unchecked")
    Class<? extends U> tag = (Class<? extends U>) matches.get(0);

    try {
      Constructor<? extends U> constructor = tag.getDeclaredConstructor();
      constructor.setAccessible(true);
      return Optional.of(constructor.newInstance());
    } catch (NoSuchMethodException
        | IllegalAccessException
        | InstantiationException
        | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }
}
