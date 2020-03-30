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
import com.facebook.buck.util.types.Pair;
import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;
import java.util.Collection;
import java.util.Iterator;

/** Coerces from a 2-element collection into a pair. */
public class PairTypeCoercer<FU, SU, FIRST, SECOND>
    implements TypeCoercer<Pair<FU, SU>, Pair<FIRST, SECOND>> {
  private TypeCoercer<FU, FIRST> firstTypeCoercer;
  private TypeCoercer<SU, SECOND> secondTypeCoercer;
  private final TypeToken<Pair<FIRST, SECOND>> typeToken;
  private final TypeToken<Pair<FU, SU>> typeTokenUnconfigured;

  public PairTypeCoercer(
      TypeCoercer<FU, FIRST> firstTypeCoercer, TypeCoercer<SU, SECOND> secondTypeCoercer) {
    this.firstTypeCoercer = firstTypeCoercer;
    this.secondTypeCoercer = secondTypeCoercer;
    this.typeToken =
        new TypeToken<Pair<FIRST, SECOND>>() {}.where(
                new TypeParameter<FIRST>() {}, firstTypeCoercer.getOutputType())
            .where(new TypeParameter<SECOND>() {}, secondTypeCoercer.getOutputType());
    this.typeTokenUnconfigured =
        new TypeToken<Pair<FU, SU>>() {}.where(
                new TypeParameter<FU>() {}, firstTypeCoercer.getUnconfiguredType())
            .where(new TypeParameter<SU>() {}, secondTypeCoercer.getUnconfiguredType());
  }

  @Override
  public TypeToken<Pair<FIRST, SECOND>> getOutputType() {
    return typeToken;
  }

  @Override
  public TypeToken<Pair<FU, SU>> getUnconfiguredType() {
    return typeTokenUnconfigured;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return firstTypeCoercer.hasElementClass(types) || secondTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(
      CellNameResolver cellRoots, Pair<FIRST, SECOND> object, Traversal traversal) {
    firstTypeCoercer.traverse(cellRoots, object.getFirst(), traversal);
    secondTypeCoercer.traverse(cellRoots, object.getSecond(), traversal);
  }

  @Override
  public Pair<FU, SU> coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof Collection) {
      Collection<?> collection = (Collection<?>) object;
      if (collection.size() != 2) {
        throw CoerceFailedException.simple(
            object, getOutputType(), "input collection should have 2 elements");
      }
      Iterator<?> iterator = collection.iterator();
      FU first =
          firstTypeCoercer.coerceToUnconfigured(
              cellRoots, filesystem, pathRelativeToProjectRoot, iterator.next());
      SU second =
          secondTypeCoercer.coerceToUnconfigured(
              cellRoots, filesystem, pathRelativeToProjectRoot, iterator.next());
      return new Pair<>(first, second);
    } else {
      throw CoerceFailedException.simple(
          object, getOutputType(), "input object should be a 2-element collection");
    }
  }

  @Override
  public Pair<FIRST, SECOND> coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Pair<FU, SU> object)
      throws CoerceFailedException {

    return new Pair<>(
        firstTypeCoercer.coerce(
            cellRoots,
            filesystem,
            pathRelativeToProjectRoot,
            targetConfiguration,
            hostConfiguration,
            object.getFirst()),
        secondTypeCoercer.coerce(
            cellRoots,
            filesystem,
            pathRelativeToProjectRoot,
            targetConfiguration,
            hostConfiguration,
            object.getSecond()));
  }
}
