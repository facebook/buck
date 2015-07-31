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
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Collection;
import java.util.SortedSet;

public class SortedSetTypeCoercer<T extends Comparable<? super T>>
    extends CollectionTypeCoercer<ImmutableSortedSet<T>, T> {

  private final TypeCoercer<T> elementTypeCoercer;

  SortedSetTypeCoercer(TypeCoercer<T> elementTypeCoercer) {
    super(elementTypeCoercer);
    this.elementTypeCoercer = elementTypeCoercer;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Class<ImmutableSortedSet<T>> getOutputClass() {
    return (Class<ImmutableSortedSet<T>>) (Class<?>) ImmutableSortedSet.class;
  }

  @Override
  public Optional<ImmutableSortedSet<T>> getOptionalValue() {
    return Optional.of(ImmutableSortedSet.<T>of());
  }

  protected void fillSortedSet(
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      SortedSet<T> builder,
      Object object) throws CoerceFailedException {

    if (object instanceof Collection) {
      for (Object element : (Iterable<?>) object) {
        // if any element failed, the entire collection fails
        T coercedElement = elementTypeCoercer.coerce(
            filesystem,
            pathRelativeToProjectRoot,
            element);
        boolean alreadyExists = !builder.add(coercedElement);
        if (alreadyExists) {
          throw new CoerceFailedException(
              String.format("duplicate element \"%s\"", coercedElement));
        }
      }
    } else {
      throw CoerceFailedException.simple(object, getOutputClass());
    }
  }

  @Override
  public ImmutableSortedSet<T> coerce(
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    final SortedSet<T> builder = Sets.newTreeSet();
    fillSortedSet(
        filesystem,
        pathRelativeToProjectRoot,
        builder,
        object);
    return ImmutableSortedSet.copyOf(builder);
  }

}
