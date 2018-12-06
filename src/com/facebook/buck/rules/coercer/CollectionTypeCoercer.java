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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableCollection;
import java.nio.file.Path;
import java.util.Collection;

public abstract class CollectionTypeCoercer<C extends ImmutableCollection<T>, T>
    implements TypeCoercer<C> {
  private final TypeCoercer<T> elementTypeCoercer;

  CollectionTypeCoercer(TypeCoercer<T> elementTypeCoercer) {
    this.elementTypeCoercer = elementTypeCoercer;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return elementTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellPathResolver cellRoots, C object, Traversal traversal) {
    traversal.traverse(object);
    for (T element : object) {
      elementTypeCoercer.traverse(cellRoots, element, traversal);
    }
  }

  /** Helper method to add coerced elements to the builder. */
  protected void fill(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      ImmutableCollection.Builder<T> builder,
      Object object)
      throws CoerceFailedException {
    if (object instanceof Collection) {
      Iterable<?> iterable = (Iterable<?>) object;
      fill(cellRoots, filesystem, pathRelativeToProjectRoot, builder, iterable);
    } else {
      throw CoerceFailedException.simple(object, getOutputClass());
    }
  }

  /** Populates collection builder with coerced elements from {@code iterable}. */
  private void fill(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      ImmutableCollection.Builder<T> builder,
      Iterable<?> iterable)
      throws CoerceFailedException {
    for (Object element : iterable) {
      // if any element failed, the entire collection fails
      T coercedElement =
          elementTypeCoercer.coerce(cellRoots, filesystem, pathRelativeToProjectRoot, element);
      builder.add(coercedElement);
    }
  }
}
