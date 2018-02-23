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

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.rules.AddsToRuleKey;
import java.lang.reflect.Field;

/** Holds a java.lang.reflect.Field and a ValueTypeInfo for a field referenced from a Buildable. */
public class FieldInfo<T> {
  private Field field;
  private ValueTypeInfo<T> valueTypeInfo;

  FieldInfo(Field field, ValueTypeInfo<T> valueTypeInfo) {
    this.field = field;
    this.valueTypeInfo = valueTypeInfo;
  }

  private T getValue(AddsToRuleKey value, Field field) {
    try {
      @SuppressWarnings("unchecked")
      T converted = (T) field.get(value);
      return converted;
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  public <E extends Exception> void visit(AddsToRuleKey value, ValueVisitor<E> visitor) throws E {
    visitor.visitField(field, getValue(value, field), valueTypeInfo);
  }

  public ValueTypeInfo<T> getValueTypeInfo() {
    return valueTypeInfo;
  }

  public Field getField() {
    return field;
  }
}
