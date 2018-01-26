/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.util.types;

import java.util.Objects;
import java.util.function.Function;

/** A discriminated union of two parameters that holds a value of either the LEFT or RIGHT type. */
public abstract class Either<LEFT, RIGHT> {

  /** Returns whether the instance holds a left value. */
  public abstract boolean isLeft();

  /** Returns whether the instance holds a right value. */
  public abstract boolean isRight();

  /**
   * Returns the left value.
   *
   * @throws IllegalStateException if this instance does not hold a left value.
   */
  public abstract LEFT getLeft();

  /**
   * Returns the right value.
   *
   * @throws IllegalStateException if this instance does not hold a right value.
   */
  public abstract RIGHT getRight();

  /** Apply a function based on whether the instance holds a left or right value. */
  public abstract <X> X transform(
      Function<LEFT, X> lhsTransformer, Function<RIGHT, X> rhsTransformer);

  public static <LEFT, RIGHT> Either<LEFT, RIGHT> ofLeft(LEFT value) {
    return new Left<>(value);
  }

  public static <LEFT, RIGHT> Either<LEFT, RIGHT> ofRight(RIGHT value) {
    return new Right<>(value);
  }

  // Close this class to subclassing outside of this file. This avoids ambiguities regarding
  // equality checks.
  private Either() {}

  private static final class Left<LEFT, RIGHT> extends Either<LEFT, RIGHT> {

    private final LEFT value;

    private Left(LEFT value) {
      this.value = value;
    }

    @Override
    public boolean isLeft() {
      return true;
    }

    @Override
    public boolean isRight() {
      return false;
    }

    @Override
    public LEFT getLeft() {
      return value;
    }

    @Override
    public RIGHT getRight() {
      throw new IllegalStateException("Cannot get Right value of a Left either.");
    }

    @Override
    public <X> X transform(Function<LEFT, X> lhsTransformer, Function<RIGHT, X> rhsTransformer) {
      return lhsTransformer.apply(value);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Left<?, ?> left = (Left<?, ?>) o;
      return Objects.equals(value, left.value);
    }

    @Override
    public int hashCode() {
      // Hash a bit denoting that this is a Left value.
      return Objects.hash(false, value);
    }
  }

  private static final class Right<LEFT, RIGHT> extends Either<LEFT, RIGHT> {

    private final RIGHT value;

    private Right(RIGHT value) {
      this.value = value;
    }

    @Override
    public boolean isLeft() {
      return false;
    }

    @Override
    public boolean isRight() {
      return true;
    }

    @Override
    public LEFT getLeft() {
      throw new IllegalStateException("Cannot get Left value of a Right either.");
    }

    @Override
    public RIGHT getRight() {
      return value;
    }

    @Override
    public <X> X transform(Function<LEFT, X> lhsTransformer, Function<RIGHT, X> rhsTransformer) {
      return rhsTransformer.apply(value);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Right<?, ?> right = (Right<?, ?>) o;
      return Objects.equals(value, right.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(true, value);
    }
  }
}
