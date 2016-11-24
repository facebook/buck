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
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.TargetNode;
import com.google.common.collect.ImmutableCollection;

import java.nio.file.Path;
import java.util.Collection;
import java.util.function.Function;

public abstract class CollectionTypeCoercer<C extends ImmutableCollection<T>, T>
    extends TypeCoercer<C> {
  private final TypeCoercer<T> elementTypeCoercer;

  CollectionTypeCoercer(TypeCoercer<T> elementTypeCoercer) {
    this.elementTypeCoercer = elementTypeCoercer;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return elementTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(C object, Traversal traversal) {
    traversal.traverse(object);
    for (T element : object) {
      elementTypeCoercer.traverse(element, traversal);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public <U> C mapAllInternal(
      Function<U, U> function,
      Class<U> targetClass,
      C object) throws CoerceFailedException {
    if (!elementTypeCoercer.hasElementClass(TargetNode.class)) {
      return object;
    }
    C.Builder<T> builder = getBuilder();
    for (T element : object) {
      builder.add(elementTypeCoercer.mapAll(function, targetClass, element));
    }
    return (C) builder.build();
  }

  protected abstract C.Builder<T> getBuilder();

  /**
   * Helper method to add coerced elements to the builder.
   */
  protected void fill(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      ImmutableCollection.Builder<T> builder,
      Object object) throws CoerceFailedException {
    if (object instanceof Collection) {
      for (Object element : (Iterable<?>) object) {
        // if any element failed, the entire collection fails
        T coercedElement = elementTypeCoercer.coerce(
            cellRoots,
            filesystem,
            pathRelativeToProjectRoot,
            element);
        builder.add(coercedElement);
      }
    } else {
      throw CoerceFailedException.simple(object, getOutputClass());
    }
  }
}
