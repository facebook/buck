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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.List;

public class SourceSetTypeCoercer extends SourceSetConcatable implements TypeCoercer<SourceSet> {
  private final TypeCoercer<ImmutableSet<SourcePath>> unnamedHeadersTypeCoercer;
  private final TypeCoercer<ImmutableMap<String, SourcePath>> namedHeadersTypeCoercer;

  SourceSetTypeCoercer(
      TypeCoercer<String> stringTypeCoercer, TypeCoercer<SourcePath> sourcePathTypeCoercer) {
    this.unnamedHeadersTypeCoercer = new SetTypeCoercer<>(sourcePathTypeCoercer);
    this.namedHeadersTypeCoercer = new MapTypeCoercer<>(stringTypeCoercer, sourcePathTypeCoercer);
  }

  @Override
  public Class<SourceSet> getOutputClass() {
    return SourceSet.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return unnamedHeadersTypeCoercer.hasElementClass(types)
        || namedHeadersTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellPathResolver cellRoots, SourceSet object, Traversal traversal) {
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
  public SourceSet coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof List) {
      return SourceSet.ofUnnamedSources(
          unnamedHeadersTypeCoercer.coerce(
              cellRoots, filesystem, pathRelativeToProjectRoot, object));
    } else {
      return SourceSet.ofNamedSources(
          namedHeadersTypeCoercer.coerce(cellRoots, filesystem, pathRelativeToProjectRoot, object));
    }
  }
}
