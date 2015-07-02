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
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.util.List;

public class SourceWithFlagsListTypeCoercer implements TypeCoercer<SourceWithFlagsList> {
  private final TypeCoercer<ImmutableList<SourceWithFlags>> unnamedSourcesTypeCoercer;
  private final TypeCoercer<ImmutableMap<String, SourceWithFlags>> namedSourcesTypeCoercer;

  SourceWithFlagsListTypeCoercer(
      TypeCoercer<String> stringTypeCoercer,
      TypeCoercer<SourceWithFlags> sourceWithFlagsTypeCoercer) {
    this.unnamedSourcesTypeCoercer = new ListTypeCoercer<>(sourceWithFlagsTypeCoercer);
    this.namedSourcesTypeCoercer = new MapTypeCoercer<>(
        stringTypeCoercer,
        sourceWithFlagsTypeCoercer);
  }

  @Override
  public Class<SourceWithFlagsList> getOutputClass() {
    return SourceWithFlagsList.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return unnamedSourcesTypeCoercer.hasElementClass(types) ||
        namedSourcesTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(SourceWithFlagsList object, Traversal traversal) {
    switch (object.getType()) {
      case UNNAMED:
        unnamedSourcesTypeCoercer.traverse(object.getUnnamedSources().get(), traversal);
        break;
      case NAMED:
        namedSourcesTypeCoercer.traverse(object.getNamedSources().get(), traversal);
        break;
      default:
        throw new RuntimeException("Unhandled type: " + object.getType());
    }
  }

  @Override
  public Optional<SourceWithFlagsList> getOptionalValue() {
    return Optional.of(SourceWithFlagsList.ofUnnamedSources(ImmutableList.<SourceWithFlags>of()));
  }

  @Override
  public SourceWithFlagsList coerce(
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {
    if (object instanceof List) {
      return SourceWithFlagsList.ofUnnamedSources(
          unnamedSourcesTypeCoercer.coerce(
              filesystem,
              pathRelativeToProjectRoot,
              object));
    } else {
      return SourceWithFlagsList.ofNamedSources(
          namedSourcesTypeCoercer.coerce(
              filesystem,
              pathRelativeToProjectRoot,
              object));
    }
  }
}
