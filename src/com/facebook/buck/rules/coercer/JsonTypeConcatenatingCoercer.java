/*
 * Copyright 2018-present Facebook, Inc.
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
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.select.impl.SelectorListFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;

/**
 * The purpose of this {@link TypeCoercer} to be used together with {@link SelectorListFactory} to
 * resolve configurable attributes that contain values of JSON-compatible types.
 *
 * <p>This coercer does not transform the objects, but only provides a way to concatenate elements.
 */
public class JsonTypeConcatenatingCoercer implements TypeCoercer<Object> {

  private final Class<Object> elementClass;

  @SuppressWarnings("unchecked")
  JsonTypeConcatenatingCoercer(Class<?> elementClass) {
    this.elementClass = (Class<Object>) elementClass;
  }

  @Override
  public Class<Object> getOutputClass() {
    return elementClass;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return false;
  }

  @Override
  public void traverse(CellPathResolver cellRoots, Object object, Traversal traversal) {}

  @Override
  public Object coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      Object object)
      throws CoerceFailedException {
    // No transformations here, just return the same object
    return object;
  }
}
