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

import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;
import javax.annotation.Nullable;

/**
 * ValueTypeInfo for fields marked @Nullable. This allows other ValueTypeInfos to assume that they
 * always deal with non-null values.
 */
public class NullableValueTypeInfo<T> implements ValueTypeInfo<T> {
  private final ValueTypeInfo<T> inner;

  public NullableValueTypeInfo(ValueTypeInfo<T> inner) {
    this.inner = inner;
  }

  @Override
  public <E extends Exception> void visit(T value, ValueVisitor<E> visitor) throws E {
    visitor.visitNullable(value, inner);
  }

  @Override
  @Nullable
  public <E extends Exception> T create(ValueCreator<E> creator) throws E {
    return creator.createNullable(inner);
  }
}
