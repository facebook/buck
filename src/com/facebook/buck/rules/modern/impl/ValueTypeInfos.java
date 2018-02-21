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

import com.facebook.buck.rules.modern.OutputPath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

class ValueTypeInfos {
  /** ValueTypeInfo for simple (String, int, etc) types. */
  static class SimpleValueTypeInfo implements ValueTypeInfo<Object> {
    static final ValueTypeInfo<Object> INSTANCE = new SimpleValueTypeInfo();

    @Override
    public <E extends Exception> void visit(Object value, ValueVisitor<E> visitor) throws E {
      visitor.visitSimple(value);
    }
  }

  /** ValueTypeInfo for OutputPaths. */
  static class OutputPathValueTypeInfo implements ValueTypeInfo<OutputPath> {
    public static final OutputPathValueTypeInfo INSTANCE = new OutputPathValueTypeInfo();

    @Override
    public <E extends Exception> void visit(OutputPath value, ValueVisitor<E> visitor) throws E {
      visitor.visitOutputPath(value);
    }
  }

  /** ValueTypeInfo for Optionals. */
  static class OptionalValueTypeInfo<T> implements ValueTypeInfo<Optional<T>> {
    private final ValueTypeInfo<T> innerType;

    OptionalValueTypeInfo(ValueTypeInfo<T> valueTypeInfo) {
      this.innerType = valueTypeInfo;
    }

    @Override
    public <E extends Exception> void visit(Optional<T> value, ValueVisitor<E> visitor) throws E {
      visitor.visitOptional(value, innerType);
    }
  }

  private abstract static class IterableValueTypeInfo<T, C extends Iterable<T>>
      implements ValueTypeInfo<C> {

    protected final ValueTypeInfo<T> innerType;

    IterableValueTypeInfo(ValueTypeInfo<T> innerType) {
      this.innerType = innerType;
    }
  }

  /** ValueTypeInfo for ImmutableSortedSets. */
  static class ImmutableSortedSetValueTypeInfo<T>
      extends IterableValueTypeInfo<T, ImmutableSortedSet<T>> {
    ImmutableSortedSetValueTypeInfo(ValueTypeInfo<T> innerType) {
      super(innerType);
    }

    @Override
    public <E extends Exception> void visit(ImmutableSortedSet<T> value, ValueVisitor<E> visitor)
        throws E {
      visitor.visitSet(value, innerType);
    }
  }

  /** ValueTypeInfo for ImmutableLists. */
  static class ImmutableListValueTypeInfo<T> extends IterableValueTypeInfo<T, ImmutableList<T>> {
    ImmutableListValueTypeInfo(ValueTypeInfo<T> innerType) {
      super(innerType);
    }

    @Override
    public <E extends Exception> void visit(ImmutableList<T> value, ValueVisitor<E> visitor)
        throws E {
      visitor.visitList(value, innerType);
    }
  }
}
