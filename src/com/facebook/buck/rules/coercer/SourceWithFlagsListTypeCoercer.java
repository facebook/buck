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
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.reflect.TypeToken;
import java.util.List;

/** Coerce to {@link com.facebook.buck.rules.coercer.SourceWithFlagsList}. */
public class SourceWithFlagsListTypeCoercer implements TypeCoercer<Object, SourceWithFlagsList> {
  private final TypeCoercer<ImmutableList<Object>, ImmutableSortedSet<SourceWithFlags>>
      unnamedSourcesTypeCoercer;
  private final TypeCoercer<
          ImmutableSortedMap<String, Object>, ImmutableSortedMap<String, SourceWithFlags>>
      namedSourcesTypeCoercer;

  SourceWithFlagsListTypeCoercer(
      TypeCoercer<String, String> stringTypeCoercer,
      TypeCoercer<Object, SourceWithFlags> sourceWithFlagsTypeCoercer) {
    this.unnamedSourcesTypeCoercer = new SortedSetTypeCoercer<>(sourceWithFlagsTypeCoercer);
    this.namedSourcesTypeCoercer =
        new SortedMapTypeCoercer<>(stringTypeCoercer, sourceWithFlagsTypeCoercer);
  }

  @Override
  public TypeToken<SourceWithFlagsList> getOutputType() {
    return TypeToken.of(SourceWithFlagsList.class);
  }

  @Override
  public TypeToken<Object> getUnconfiguredType() {
    return TypeToken.of(Object.class);
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return unnamedSourcesTypeCoercer.hasElementClass(types)
        || namedSourcesTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(
      CellNameResolver cellRoots, SourceWithFlagsList object, Traversal traversal) {
    switch (object.getType()) {
      case UNNAMED:
        unnamedSourcesTypeCoercer.traverse(cellRoots, object.getUnnamedSources().get(), traversal);
        break;
      case NAMED:
        namedSourcesTypeCoercer.traverse(cellRoots, object.getNamedSources().get(), traversal);
        break;
      default:
        throw new RuntimeException("Unhandled type: " + object.getType());
    }
  }

  @Override
  public Object coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    return object;
  }

  @Override
  public SourceWithFlagsList coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object object)
      throws CoerceFailedException {
    if (object instanceof List) {
      return SourceWithFlagsList.ofUnnamedSources(
          unnamedSourcesTypeCoercer.coerceBoth(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfiguration,
              object));
    } else {
      return SourceWithFlagsList.ofNamedSources(
          namedSourcesTypeCoercer.coerceBoth(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfiguration,
              object));
    }
  }
}
