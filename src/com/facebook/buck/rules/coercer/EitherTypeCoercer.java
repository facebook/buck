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

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

/**
 * Coerces a type to either type, trying the left type before the right.
 */
public class EitherTypeCoercer<Left, Right> implements TypeCoercer<Either<Left, Right>> {
  private final TypeCoercer<Left> leftTypeCoercer;
  private final TypeCoercer<Right> rightTypeCoercer;

  public EitherTypeCoercer(TypeCoercer<Left> leftTypeCoercer, TypeCoercer<Right> rightTypeCoercer) {
    this.leftTypeCoercer = Preconditions.checkNotNull(leftTypeCoercer);
    this.rightTypeCoercer = Preconditions.checkNotNull(rightTypeCoercer);

    // disallow either of eithers, it doesn't work well with traversals
    Preconditions.checkState(
        !(leftTypeCoercer instanceof EitherTypeCoercer ||
            rightTypeCoercer instanceof EitherTypeCoercer),
        "Either of Eithers is not allowed");
  }

  @SuppressWarnings("unchecked")
  @Override
  public Class<Either<Left, Right>> getOutputClass() {
    return (Class<Either<Left, Right>>) (Class<?>) Either.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return leftTypeCoercer.hasElementClass(types) || rightTypeCoercer.hasElementClass(types);
  }

  @Override
  public Optional<Either<Left, Right>> getOptionalValue() {
    return Optional.absent();
  }

  @Override
  public boolean traverse(Object object, Traversal traversal) {
    // This does introspection and pick the side that contains child elements to try first.
    // This is not terribly robust, but is sufficient for most use cases.
    if (leftTypeCoercer instanceof CollectionTypeCoercer ||
        leftTypeCoercer instanceof MapTypeCoercer) {
      return leftTypeCoercer.traverse(object, traversal) ||
          rightTypeCoercer.traverse(object, traversal);
    } else {
      return rightTypeCoercer.traverse(object, traversal) ||
          leftTypeCoercer.traverse(object, traversal);
    }
  }

  @Override
  public Either<Left, Right> coerce(
      BuildRuleResolver buildRuleResolver,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {
    // Try to coerce as left type.
    try {
      return Either.ofLeft(
          leftTypeCoercer.coerce(buildRuleResolver, filesystem, pathRelativeToProjectRoot, object));
    } catch (CoerceFailedException e) {
      // Try to coerce as right type.
      try {
        return Either.ofRight(
            rightTypeCoercer.coerce(
                buildRuleResolver, filesystem, pathRelativeToProjectRoot, object));
      } catch (CoerceFailedException e1) {
        // Fail, but report that the current coercer failed, not the child ones.
        throw CoerceFailedException.simple(pathRelativeToProjectRoot, object, getOutputClass());
      }
    }
  }
}
