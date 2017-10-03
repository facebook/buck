/*
 * Copyright 2016-present Facebook, Inc.
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

import com.google.common.base.Preconditions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.annotation.Nullable;

/** Extracts a value of a given field, that is assumed to be accessible. */
public class ValueMethodValueExtractor implements ValueExtractor {
  private final Method method;

  public ValueMethodValueExtractor(Method method) {
    Preconditions.checkArgument(method.getParameterCount() == 0);
    Preconditions.checkArgument(!method.getReturnType().equals(Void.class));
    // TODO(cjhopman): Should this do any other verification of the signature/annotations on the
    // method?
    this.method = method;
  }

  @Override
  public String getFullyQualifiedName() {
    return method.getDeclaringClass() + "." + method.getName();
  }

  @Override
  public String getName() {
    return method.getName();
  }

  @Override
  @Nullable
  public Object getValue(Object obj) {
    try {
      return method.invoke(obj);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }
}
