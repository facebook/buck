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

package com.facebook.buck.rules.coercer;

import com.google.common.base.Preconditions;

import java.util.Objects;

import javax.annotation.Nullable;

/**
 * A simple discriminated union of two parameters.
 *
 * Note that the corresponding coercer #{link EitherTypeCoercer} is left-biased. That is, if the
 * input value can coerce to the left parameter, then the left value will be populated, even if it
 * can also be coerced to the right parameter.
 */
public final class Either<LEFT, RIGHT> {
  @Nullable public final LEFT left;
  @Nullable public final RIGHT right;

  private Either(@Nullable LEFT left, @Nullable RIGHT right) {
    Preconditions.checkState(
        left != null ^ right != null,
        "Exactly one of left or right must be set");
    this.left = left;
    this.right = right;
  }

  public boolean isLeft() {
    return left != null;
  }

  public boolean isRight() {
    return right != null;
  }

  public LEFT getLeft() {
    return Preconditions.checkNotNull(left);
  }

  public RIGHT getRight() {
    return Preconditions.checkNotNull(right);
  }

  @Override
  public int hashCode() {
    return Objects.hash(left, right);
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof Either) {
      Either<?, ?> that = (Either<?, ?>) obj;
      return Objects.equals(this.left, that.left) &&
          Objects.equals(this.right, that.right);
    }
    return false;
  }

  public static <LEFT, RIGHT> Either<LEFT, RIGHT> ofLeft(LEFT value) {
    return new Either<>(value, null);
  }

  public static <LEFT, RIGHT> Either<LEFT, RIGHT> ofRight(RIGHT value) {
    return new Either<>(null, value);
  }
}
