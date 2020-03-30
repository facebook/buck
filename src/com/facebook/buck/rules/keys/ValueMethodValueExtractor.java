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

import com.google.common.base.Preconditions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Extracts a value of a given field, that is assumed to be accessible. */
public class ValueMethodValueExtractor implements ValueExtractor {
  private static final Pattern GET_PATTERN = Pattern.compile("get[A-Z].*");
  private static final Pattern IS_PATTERN = Pattern.compile("is[A-Z].*");

  private final Method method;
  private final String name;
  private final String qualifiedName;

  ValueMethodValueExtractor(Method method) {
    Preconditions.checkArgument(method.getParameterCount() == 0);
    Preconditions.checkArgument(!method.getReturnType().equals(Void.class));
    // TODO(cjhopman): Should this do any other verification of the signature/annotations on the
    // method?
    this.method = method;
    this.qualifiedName = method.getDeclaringClass() + "." + method.getName();
    String methodName = method.getName();

    // Strip the leading get/is from names.
    if (GET_PATTERN.matcher(methodName).matches()) {
      this.name = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
    } else if (IS_PATTERN.matcher(methodName).matches()) {
      this.name = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
    } else {
      this.name = methodName;
    }
  }

  @Override
  public String getFullyQualifiedName() {
    return qualifiedName;
  }

  @Override
  public String getName() {
    return name;
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
