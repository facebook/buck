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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

public class SourceListTypeCoercer extends TypeCoercer<SourceList> {
  private final TypeCoercer<ImmutableSortedSet<SourcePath>> unnamedSourcesTypeCoercer;
  private final TypeCoercer<ImmutableSortedMap<String, SourcePath>> namedSourcesTypeCoercer;

  SourceListTypeCoercer(
      TypeCoercer<String> stringTypeCoercer,
      TypeCoercer<SourcePath> sourcePathTypeCoercer) {
    this.unnamedSourcesTypeCoercer = new SortedSetTypeCoercer<>(sourcePathTypeCoercer);
    this.namedSourcesTypeCoercer = new SortedMapTypeCoercer<>(
        stringTypeCoercer,
        sourcePathTypeCoercer);
  }

  @Override
  public Class<SourceList> getOutputClass() {
    return SourceList.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return unnamedSourcesTypeCoercer.hasElementClass(types) ||
        namedSourcesTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(SourceList object, Traversal traversal) {
    switch (object.getType()) {
      case UNNAMED:
        unnamedSourcesTypeCoercer.traverse(object.getUnnamedSources().get(), traversal);
        break;
      case NAMED:
        namedSourcesTypeCoercer.traverse(object.getNamedSources().get(), traversal);
        break;
    }
  }

  @Override
  public SourceList coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {
    if (object instanceof List) {
      return SourceList.ofUnnamedSources(
          unnamedSourcesTypeCoercer.coerce(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              object));
    } else {
      return SourceList.ofNamedSources(
          namedSourcesTypeCoercer.coerce(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              object));
    }
  }

  @Override
  public <U> SourceList mapAllInternal(
      Function<U, U> function,
      Class<U> targetClass,
      SourceList object) throws CoerceFailedException {
    switch (object.getType()) {
      case UNNAMED:
        if (!unnamedSourcesTypeCoercer.hasElementClass(TargetNode.class)) {
          return object;
        }
        return SourceList.ofUnnamedSources(
            unnamedSourcesTypeCoercer.mapAll(
                function,
                targetClass,
                object.getUnnamedSources().get()));
      case NAMED:
        if (!namedSourcesTypeCoercer.hasElementClass(TargetNode.class)) {
          return object;
        }
        return SourceList.ofNamedSources(
            namedSourcesTypeCoercer.mapAll(
                function,
                targetClass,
                object.getNamedSources().get()));
    }
    throw new RuntimeException(String.format("Unhandled type: '%s'", object.getType()));
  }
}
