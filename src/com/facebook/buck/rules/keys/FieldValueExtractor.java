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

import java.lang.reflect.Field;
import javax.annotation.Nullable;

/** Extracts a value of a given field, that is assumed to be accessible. */
public class FieldValueExtractor implements ValueExtractor {
  private final Field field;

  FieldValueExtractor(Field field) {
    this.field = field;
  }

  @Override
  public String getFullyQualifiedName() {
    return field.getDeclaringClass() + "." + field.getName();
  }

  @Override
  public String getName() {
    return field.getName();
  }

  @Override
  @Nullable
  public Object getValue(Object obj) {
    try {
      return field.get(obj);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }
}
