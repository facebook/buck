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
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;

public class SetTypeCoercer<T> extends CollectionTypeCoercer<ImmutableSet<T>, T> {
  SetTypeCoercer(TypeCoercer<T> elementTypeCoercer) {
    super(elementTypeCoercer);
  }

  @SuppressWarnings("unchecked")
  @Override
  public Class<ImmutableSet<T>> getOutputClass() {
    return (Class<ImmutableSet<T>>) (Class<?>) ImmutableSet.class;
  }

  @Override
  public ImmutableSet<T> coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      Object object)
      throws CoerceFailedException {
    ImmutableSet.Builder<T> builder = ImmutableSet.builder();
    fill(cellRoots, filesystem, pathRelativeToProjectRoot, targetConfiguration, builder, object);
    return builder.build();
  }
}
