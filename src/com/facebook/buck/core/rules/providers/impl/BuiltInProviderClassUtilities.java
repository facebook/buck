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

package com.facebook.buck.core.rules.providers.impl;

import com.facebook.buck.core.rules.providers.annotations.ImmutableInfo;

/** Some common utilities for {@link BuiltInProvider}s */
class BuiltInProviderClassUtilities {
  private BuiltInProviderClassUtilities() {}

  /**
   * @return the class that defined the api of the {@link BuiltInProviderInfo}. This is the class
   *     that has the {@link ImmutableInfo} annotation.
   */
  @SuppressWarnings("unchecked")
  static <U> Class<U> findDeclaringClass(Class<?> maybeInfoClass) {
    if (maybeInfoClass.getAnnotation(ImmutableInfo.class) != null) {
      return (Class<U>) maybeInfoClass;
    }
    if (maybeInfoClass.getSuperclass() == BuiltInProviderInfo.class) {
      throw new IllegalArgumentException(
          String.format(
              "BuiltInProviders are required to be annotated with %s", ImmutableInfo.class));
    }
    return findDeclaringClass(maybeInfoClass.getSuperclass());
  }
}
