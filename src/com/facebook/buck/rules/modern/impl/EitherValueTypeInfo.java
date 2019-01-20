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

import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.facebook.buck.util.types.Either;

/**
 * ValueTypeInfo for Eithers. Basically just a composition of a boolean indicating if it is left or
 * right and then the contained value.
 */
public class EitherValueTypeInfo<L, R> implements ValueTypeInfo<Either<L, R>> {
  private final ValueTypeInfo<L> leftTypeInfo;
  private final ValueTypeInfo<R> rightTypeInfo;

  public EitherValueTypeInfo(ValueTypeInfo<L> leftTypeInfo, ValueTypeInfo<R> rightTypeInfo) {
    this.leftTypeInfo = leftTypeInfo;
    this.rightTypeInfo = rightTypeInfo;
  }

  @Override
  public <E extends Exception> void visit(Either<L, R> value, ValueVisitor<E> visitor) throws E {
    visitor.visitBoolean(value.isLeft());
    if (value.isLeft()) {
      leftTypeInfo.visit(value.getLeft(), visitor);
    } else {
      rightTypeInfo.visit(value.getRight(), visitor);
    }
  }

  @Override
  public <E extends Exception> Either<L, R> create(ValueCreator<E> creator) throws E {
    if (creator.createBoolean()) {
      return Either.ofLeft(leftTypeInfo.create(creator));
    } else {
      return Either.ofRight(rightTypeInfo.create(creator));
    }
  }
}
