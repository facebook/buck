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
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.List;

public class SourceListTypeCoercer implements TypeCoercer<SourceList> {
  private final TypeCoercer<ImmutableSortedSet<SourcePath>> unnamedHeadersTypeCoercer;
  private final TypeCoercer<ImmutableMap<String, SourcePath>> namedHeadersTypeCoercer;

  SourceListTypeCoercer(
      TypeCoercer<String> stringTypeCoercer,
      TypeCoercer<SourcePath> sourcePathTypeCoercer) {
    this.unnamedHeadersTypeCoercer = new SortedSetTypeCoercer<>(sourcePathTypeCoercer);
    this.namedHeadersTypeCoercer = new MapTypeCoercer<>(
        stringTypeCoercer,
        sourcePathTypeCoercer);
  }

  @Override
  public Class<SourceList> getOutputClass() {
    return SourceList.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return unnamedHeadersTypeCoercer.hasElementClass(types) ||
        namedHeadersTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(SourceList object, Traversal traversal) {
    switch (object.getType()) {
      case UNNAMED:
        unnamedHeadersTypeCoercer.traverse(object.getUnnamedSources().get(), traversal);
        break;
      case NAMED:
        namedHeadersTypeCoercer.traverse(object.getNamedSources().get(), traversal);
        break;
    }
  }

  @Override
  public Optional<SourceList> getOptionalValue() {
    return Optional.of(SourceList.ofUnnamedSources(ImmutableSortedSet.<SourcePath>of()));
  }

  @Override
  public SourceList coerce(
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {
    if (object instanceof List) {
      return SourceList.ofUnnamedSources(
          unnamedHeadersTypeCoercer.coerce(
              filesystem,
              pathRelativeToProjectRoot,
              object));
    } else {
      return SourceList.ofNamedSources(
          namedHeadersTypeCoercer.coerce(
              filesystem,
              pathRelativeToProjectRoot,
              object));
    }
  }
}
