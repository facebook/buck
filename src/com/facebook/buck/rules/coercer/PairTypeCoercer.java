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
import com.facebook.buck.model.Pair;
import com.google.common.base.Optional;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;

/**
 * Coerces from a 2-element collection into a pair.
 */
public class PairTypeCoercer<FIRST, SECOND> implements TypeCoercer<Pair<FIRST, SECOND>> {
  private TypeCoercer<FIRST> firstTypeCoercer;
  private TypeCoercer<SECOND> secondTypeCoercer;

  public PairTypeCoercer(
      TypeCoercer<FIRST> firstTypeCoercer, TypeCoercer<SECOND> secondTypeCoercer) {
    this.firstTypeCoercer = firstTypeCoercer;
    this.secondTypeCoercer = secondTypeCoercer;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Class<Pair<FIRST, SECOND>> getOutputClass() {
    return (Class<Pair<FIRST, SECOND>>) (Class<?>) Pair.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return firstTypeCoercer.hasElementClass(types) || secondTypeCoercer.hasElementClass(types);
  }

  @Override
  public Optional<Pair<FIRST, SECOND>> getOptionalValue() {
    return Optional.absent();
  }

  @Override
  public void traverse(Pair<FIRST, SECOND> object, Traversal traversal) {
    firstTypeCoercer.traverse(object.getFirst(), traversal);
    secondTypeCoercer.traverse(object.getSecond(), traversal);
  }

  @Override
  public Pair<FIRST, SECOND> coerce(
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof Collection) {
      Collection<?> collection = (Collection<?>) object;
      if (collection.size() != 2) {
        throw CoerceFailedException.simple(
            object,
            getOutputClass(),
            "input collection should have 2 elements");
      }
      Iterator<?> iterator = collection.iterator();
      FIRST first = firstTypeCoercer.coerce(
          filesystem,
          pathRelativeToProjectRoot,
          iterator.next());
      SECOND second = secondTypeCoercer.coerce(
          filesystem,
          pathRelativeToProjectRoot,
          iterator.next());
      return new Pair<>(first, second);
    } else {
      throw CoerceFailedException.simple(
          object,
          getOutputClass(),
          "input object should be a 2-element collection");
    }
  }
}
