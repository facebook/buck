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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.parser.BuildTargetParser;
import com.google.common.base.Optional;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/**
 * Coerces a type to either type, trying the left type before the right.
 */
public class EitherTypeCoercer<Left, Right> implements TypeCoercer<Either<Left, Right>> {
  private final TypeCoercer<Left> leftTypeCoercer;
  private final TypeCoercer<Right> rightTypeCoercer;

  public EitherTypeCoercer(TypeCoercer<Left> leftTypeCoercer, TypeCoercer<Right> rightTypeCoercer) {
    this.leftTypeCoercer = leftTypeCoercer;
    this.rightTypeCoercer = rightTypeCoercer;
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
  public void traverse(Either<Left, Right> object, Traversal traversal) {
    if (object.isLeft()) {
      leftTypeCoercer.traverse(object.getLeft(), traversal);
    } else {
      rightTypeCoercer.traverse(object.getRight(), traversal);
    }
  }

  // Classifications for the "type" of object/coercer.  We use this to unambiguously
  // choose which "side" of the coercion fork we want to choose, rather than relying
  // on catching `CoerceFailedException` exceptions, which can lead to really unhelpful
  // error messages hitting the user.
  private enum Type {
    DEFAULT,
    COLLECTION,
    MAP,
  }

  private static <T> Type getCoercerType(TypeCoercer<T> coercer) {
    if (coercer instanceof MapTypeCoercer) {
      return Type.MAP;
    } else if (coercer instanceof CollectionTypeCoercer) {
      return Type.COLLECTION;
    } else {
      return Type.DEFAULT;
    }
  }

  private static Type getObjectType(Object object) {
    if (object instanceof Map) {
      return Type.MAP;
    } else if (object instanceof Collection) {
      return Type.COLLECTION;
    } else {
      return Type.DEFAULT;
    }
  }

  @Override
  public Either<Left, Right> coerce(
      BuildTargetParser buildTargetParser,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {

    // Determine the "type" of the object we're coercing and our left and right coercers.
    Type objectType = getObjectType(object);
    Type leftCoercerType = getCoercerType(leftTypeCoercer);
    Type rightCoercerType = getCoercerType(rightTypeCoercer);

    // If both coercers match, try the left one first, and if it fails try the right
    // side.  If neither work, throw an exception combining the two errors.  Long term,
    // we probably should require some way to "choose" a side without relying on failures,
    // as this would make errors reported to the user much more clear.
    if (leftCoercerType == objectType && rightCoercerType == objectType) {
      try {
        return Either.ofLeft(leftTypeCoercer.coerce(
            buildTargetParser,
            filesystem,
            pathRelativeToProjectRoot,
            object));
      } catch (CoerceFailedException eLeft) {
        try {
          return Either.ofRight(rightTypeCoercer.coerce(
              buildTargetParser,
              filesystem,
              pathRelativeToProjectRoot,
              object));
        } catch (CoerceFailedException eRight) {
          throw new CoerceFailedException(String.format(
              "%s, or %s",
              eLeft.getMessage(),
              eRight.getMessage()));
        }
      }

    }

    // Only the left coercer matches, so use that to parse the input and let any inner
    // exceptions propagate up.
    if (leftCoercerType == objectType) {
      return Either.ofLeft(leftTypeCoercer.coerce(
          buildTargetParser,
          filesystem,
          pathRelativeToProjectRoot,
          object));
    }

    // Only the right coercer matches, so use that to parse the input and let any inner
    // exceptions propagate up.
    if (rightCoercerType == objectType) {
      return Either.ofRight(rightTypeCoercer.coerce(
          buildTargetParser,
          filesystem,
          pathRelativeToProjectRoot,
          object));
    }

    // None of our coercers matched the "type" of the object, so throw the generic
    // error message.
    throw new CoerceFailedException(String.format("cannot parse %s", object));
  }
}
