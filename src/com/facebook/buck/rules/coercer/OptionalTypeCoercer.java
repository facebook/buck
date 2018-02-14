/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.CellPathResolver;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Optional;

public class OptionalTypeCoercer<T> implements TypeCoercer<Optional<T>> {

  private final TypeCoercer<T> coercer;

  public OptionalTypeCoercer(TypeCoercer<T> coercer) {
    Preconditions.checkArgument(
        !coercer.getOutputClass().isAssignableFrom(Optional.class),
        "Nested optional fields are ambiguous.");
    this.coercer = coercer;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Class<Optional<T>> getOutputClass() {
    return (Class<Optional<T>>) (Class<?>) Optional.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return coercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellPathResolver cellRoots, Optional<T> object, Traversal traversal) {
    if (object.isPresent()) {
      coercer.traverse(cellRoots, object.get(), traversal);
    }
  }

  @Override
  public Optional<T> coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object == null) {
      return Optional.empty();
    }
    return Optional.of(coercer.coerce(cellRoots, filesystem, pathRelativeToProjectRoot, object));
  }
}
