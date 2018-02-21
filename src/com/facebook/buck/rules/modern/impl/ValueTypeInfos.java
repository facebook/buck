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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.primitives.Primitives;
import java.lang.reflect.Type;
import java.util.Optional;

class ValueTypeInfos {
  static ValueTypeInfo<?> forSimpleType(Type type) {
    Preconditions.checkState(type instanceof Class<?>);
    Class<?> rawClass = Primitives.wrap((Class<?>) type);
    if (rawClass == String.class) {
      return StringValueTypeInfo.INSTANCE;
    } else if (rawClass == Character.class) {
      return CharacterValueTypeInfo.INSTANCE;
    } else if (rawClass == Boolean.class) {
      return BooleanValueTypeInfo.INSTANCE;
    } else if (rawClass == Byte.class) {
      return ByteValueTypeInfo.INSTANCE;
    } else if (rawClass == Short.class) {
      return ShortValueTypeInfo.INSTANCE;
    } else if (rawClass == Integer.class) {
      return IntegerValueTypeInfo.INSTANCE;
    } else if (rawClass == Long.class) {
      return LongValueTypeInfo.INSTANCE;
    } else if (rawClass == Float.class) {
      return FloatValueTypeInfo.INSTANCE;
    } else if (rawClass == Double.class) {
      return DoubleValueTypeInfo.INSTANCE;
    }
    throw new RuntimeException();
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

  private static class StringValueTypeInfo implements ValueTypeInfo<String> {
    public static final ValueTypeInfo<String> INSTANCE = new StringValueTypeInfo();

    @Override
    public <E extends Exception> void visit(String value, ValueVisitor<E> visitor) throws E {
      visitor.visitString(value);
    }
  }

  private static class CharacterValueTypeInfo implements ValueTypeInfo<Character> {
    public static final ValueTypeInfo<Character> INSTANCE = new CharacterValueTypeInfo();

    @Override
    public <E extends Exception> void visit(Character value, ValueVisitor<E> visitor) throws E {
      visitor.visitCharacter(value);
    }
  }

  private static class BooleanValueTypeInfo implements ValueTypeInfo<Boolean> {
    public static final ValueTypeInfo<Boolean> INSTANCE = new BooleanValueTypeInfo();

    @Override
    public <E extends Exception> void visit(Boolean value, ValueVisitor<E> visitor) throws E {
      visitor.visitBoolean(value);
    }
  }

  private static class ByteValueTypeInfo implements ValueTypeInfo<Byte> {
    public static final ValueTypeInfo<Byte> INSTANCE = new ByteValueTypeInfo();

    @Override
    public <E extends Exception> void visit(Byte value, ValueVisitor<E> visitor) throws E {
      visitor.visitByte(value);
    }
  }

  private static class ShortValueTypeInfo implements ValueTypeInfo<Short> {
    public static final ValueTypeInfo<Short> INSTANCE = new ShortValueTypeInfo();

    @Override
    public <E extends Exception> void visit(Short value, ValueVisitor<E> visitor) throws E {
      visitor.visitShort(value);
    }
  }

  private static class IntegerValueTypeInfo implements ValueTypeInfo<Integer> {
    public static final ValueTypeInfo<Integer> INSTANCE = new IntegerValueTypeInfo();

    @Override
    public <E extends Exception> void visit(Integer value, ValueVisitor<E> visitor) throws E {
      visitor.visitInteger(value);
    }
  }

  private static class LongValueTypeInfo implements ValueTypeInfo<Long> {
    public static final ValueTypeInfo<Long> INSTANCE = new LongValueTypeInfo();

    @Override
    public <E extends Exception> void visit(Long value, ValueVisitor<E> visitor) throws E {
      visitor.visitLong(value);
    }
  }

  private static class FloatValueTypeInfo implements ValueTypeInfo<Float> {
    public static final ValueTypeInfo<Float> INSTANCE = new FloatValueTypeInfo();

    @Override
    public <E extends Exception> void visit(Float value, ValueVisitor<E> visitor) throws E {
      visitor.visitFloat(value);
    }
  }

  private static class DoubleValueTypeInfo implements ValueTypeInfo<Double> {
    public static final ValueTypeInfo<Double> INSTANCE = new DoubleValueTypeInfo();

    @Override
    public <E extends Exception> void visit(Double value, ValueVisitor<E> visitor) throws E {
      visitor.visitDouble(value);
    }
  }
}
