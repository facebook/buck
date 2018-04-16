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
import com.facebook.buck.rules.modern.ClassInfo;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.facebook.buck.rules.modern.annotations.CustomFieldBehavior;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.lang.reflect.Field;
import java.util.Map.Entry;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * An abstract implementation of ValueVisitor used for implementations that only care about some
 * underlying non-composed types.
 */
abstract class AbstractValueVisitor<E extends Exception> implements ValueVisitor<E> {
  @Override
  public <T> void visitList(ImmutableList<T> value, ValueTypeInfo<T> innerType) throws E {
    for (T e : value) {
      innerType.visit(e, this);
    }
  }

  @Override
  public <T> void visitSet(ImmutableSortedSet<T> value, ValueTypeInfo<T> innerType) throws E {
    for (T e : value) {
      innerType.visit(e, this);
    }
  }

  @Override
  public <K, V> void visitMap(
      ImmutableSortedMap<K, V> value, ValueTypeInfo<K> keyType, ValueTypeInfo<V> valueType)
      throws E {
    for (Entry<K, V> entry : value.entrySet()) {
      keyType.visit(entry.getKey(), this);
      valueType.visit(entry.getValue(), this);
    }
  }

  @Override
  public <T> void visitOptional(Optional<T> value, ValueTypeInfo<T> innerType) throws E {
    if (value.isPresent()) {
      innerType.visit(value.get(), this);
    }
  }

  @Override
  public <T> void visitNullable(@Nullable T value, ValueTypeInfo<T> inner) throws E {
    if (value != null) {
      inner.visit(value, this);
    }
  }

  @Override
  public <T> void visitField(
      Field field,
      T value,
      ValueTypeInfo<T> valueTypeInfo,
      Optional<CustomFieldBehavior> customBehavior)
      throws E {
    valueTypeInfo.visit(value, this);
  }

  @Override
  public <T extends AddsToRuleKey> void visitDynamic(T value, ClassInfo<T> classInfo) throws E {
    classInfo.visit(value, this);
  }

  protected abstract void visitSimple(Object value) throws E;

  @Override
  public void visitString(String value) throws E {
    visitSimple(value);
  }

  @Override
  public void visitCharacter(Character value) throws E {
    visitSimple(value);
  }

  @Override
  public void visitBoolean(Boolean value) throws E {
    visitSimple(value);
  }

  @Override
  public void visitByte(Byte value) throws E {
    visitSimple(value);
  }

  @Override
  public void visitShort(Short value) throws E {
    visitSimple(value);
  }

  @Override
  public void visitInteger(Integer value) throws E {
    visitSimple(value);
  }

  @Override
  public void visitLong(Long value) throws E {
    visitSimple(value);
  }

  @Override
  public void visitFloat(Float value) throws E {
    visitSimple(value);
  }

  @Override
  public void visitDouble(Double value) throws E {
    visitSimple(value);
  }
}
