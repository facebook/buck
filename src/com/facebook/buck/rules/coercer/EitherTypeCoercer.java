/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.types.Either;
import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;
import java.util.Collection;
import java.util.Map;

/** Coerces a type to either type, trying the left type before the right. */
public class EitherTypeCoercer<LU, RU, Left, Right>
    implements TypeCoercer<Either<LU, RU>, Either<Left, Right>> {
  private final TypeCoercer<LU, Left> leftTypeCoercer;
  private final TypeCoercer<RU, Right> rightTypeCoercer;
  private final TypeToken<Either<Left, Right>> typeToken;
  private final TypeToken<Either<LU, RU>> typeTokenUnconfigured;

  public EitherTypeCoercer(
      TypeCoercer<LU, Left> leftTypeCoercer, TypeCoercer<RU, Right> rightTypeCoercer) {
    this.leftTypeCoercer = leftTypeCoercer;
    this.rightTypeCoercer = rightTypeCoercer;
    this.typeToken =
        new TypeToken<Either<Left, Right>>() {}.where(
                new TypeParameter<Left>() {}, leftTypeCoercer.getOutputType())
            .where(new TypeParameter<Right>() {}, rightTypeCoercer.getOutputType());
    this.typeTokenUnconfigured =
        new TypeToken<Either<LU, RU>>() {}.where(
                new TypeParameter<LU>() {}, leftTypeCoercer.getUnconfiguredType())
            .where(new TypeParameter<RU>() {}, rightTypeCoercer.getUnconfiguredType());
  }

  @Override
  public TypeToken<Either<Left, Right>> getOutputType() {
    return typeToken;
  }

  @Override
  public TypeToken<Either<LU, RU>> getUnconfiguredType() {
    return typeTokenUnconfigured;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return leftTypeCoercer.hasElementClass(types) || rightTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(
      CellNameResolver cellRoots, Either<Left, Right> object, Traversal traversal) {
    if (object.isLeft()) {
      leftTypeCoercer.traverse(cellRoots, object.getLeft(), traversal);
    } else {
      rightTypeCoercer.traverse(cellRoots, object.getRight(), traversal);
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

  private static Type getCoercerType(TypeCoercer<?, ?> coercer) {
    if (coercer instanceof MapTypeCoercer || coercer instanceof SortedMapTypeCoercer) {
      return Type.MAP;
    } else if (coercer instanceof CollectionTypeCoercer || coercer instanceof PairTypeCoercer) {
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
  public Either<LU, RU> coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {

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
        return Either.ofLeft(
            leftTypeCoercer.coerceToUnconfigured(
                cellRoots, filesystem, pathRelativeToProjectRoot, object));
      } catch (CoerceFailedException eLeft) {
        try {
          return Either.ofRight(
              rightTypeCoercer.coerceToUnconfigured(
                  cellRoots, filesystem, pathRelativeToProjectRoot, object));
        } catch (CoerceFailedException eRight) {
          throw new CoerceFailedException(
              String.format("%s, or %s", eLeft.getMessage(), eRight.getMessage()));
        }
      }
    }

    // Only the left coercer matches, so use that to parse the input and let any inner
    // exceptions propagate up.
    if (leftCoercerType == objectType) {
      return Either.ofLeft(
          leftTypeCoercer.coerceToUnconfigured(
              cellRoots, filesystem, pathRelativeToProjectRoot, object));
    }

    // Only the right coercer matches, so use that to parse the input and let any inner
    // exceptions propagate up.
    if (rightCoercerType == objectType) {
      return Either.ofRight(
          rightTypeCoercer.coerceToUnconfigured(
              cellRoots, filesystem, pathRelativeToProjectRoot, object));
    }

    // None of our coercers matched the "type" of the object, so throw the generic
    // error message.
    throw new CoerceFailedException(String.format("cannot parse %s", object));
  }

  @Override
  public boolean unconfiguredToConfiguredCoercionIsIdentity() {
    return leftTypeCoercer.unconfiguredToConfiguredCoercionIsIdentity()
        && rightTypeCoercer.unconfiguredToConfiguredCoercionIsIdentity();
  }

  @SuppressWarnings("unchecked")
  @Override
  public Either<Left, Right> coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Either<LU, RU> object)
      throws CoerceFailedException {
    if (object.isLeft()) {
      Left leftCoerced =
          leftTypeCoercer.coerce(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfiguration,
              object.getLeft());

      // avoid allocation even if coercer is effectively no-op
      if (leftCoerced == object.getLeft()) {
        return (Either<Left, Right>) object;
      }

      return Either.ofLeft(leftCoerced);
    } else {
      Right rightCoerced =
          rightTypeCoercer.coerce(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfiguration,
              object.getRight());

      // avoid allocation even if coercer is effectively no-op
      if (rightCoerced == object.getRight()) {
        return (Either<Left, Right>) object;
      }

      return Either.ofRight(rightCoerced);
    }
  }
}
