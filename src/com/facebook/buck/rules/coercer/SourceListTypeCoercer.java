/*
 * Copyright 2015-present Facebook, Inc.
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
import com.facebook.buck.rules.SourcePath;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.List;

public class SourceListTypeCoercer implements TypeCoercer<SourceList> {
  private final TypeCoercer<ImmutableSortedSet<SourcePath>> unnamedHeadersTypeCoercer;
  private final TypeCoercer<ImmutableSortedMap<String, SourcePath>> namedHeadersTypeCoercer;

  SourceListTypeCoercer(
      TypeCoercer<String> stringTypeCoercer, TypeCoercer<SourcePath> sourcePathTypeCoercer) {
    this.unnamedHeadersTypeCoercer = new SortedSetTypeCoercer<>(sourcePathTypeCoercer);
    this.namedHeadersTypeCoercer =
        new SortedMapTypeCoercer<>(stringTypeCoercer, sourcePathTypeCoercer);
  }

  @Override
  public Class<SourceList> getOutputClass() {
    return SourceList.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return unnamedHeadersTypeCoercer.hasElementClass(types)
        || namedHeadersTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellPathResolver cellRoots, SourceList object, Traversal traversal) {
    switch (object.getType()) {
      case UNNAMED:
        unnamedHeadersTypeCoercer.traverse(cellRoots, object.getUnnamedSources().get(), traversal);
        break;
      case NAMED:
        namedHeadersTypeCoercer.traverse(cellRoots, object.getNamedSources().get(), traversal);
        break;
    }
  }

  @Override
  public SourceList coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof List) {
      return SourceList.ofUnnamedSources(
          unnamedHeadersTypeCoercer.coerce(
              cellRoots, filesystem, pathRelativeToProjectRoot, object));
    } else {
      return SourceList.ofNamedSources(
          namedHeadersTypeCoercer.coerce(cellRoots, filesystem, pathRelativeToProjectRoot, object));
    }
  }
}
