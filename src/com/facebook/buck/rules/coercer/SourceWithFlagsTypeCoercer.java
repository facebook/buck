/*
 * Copyright 2014-present Facebook, Inc.
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
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetNode;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.function.Function;

/**
 * A type coercer to handle source entries with a list of flags.
 */
public class SourceWithFlagsTypeCoercer extends TypeCoercer<SourceWithFlags> {
  private final TypeCoercer<Pair<SourcePath, ImmutableList<String>>> sourcePathWithFlagsTypeCoercer;

  SourceWithFlagsTypeCoercer(
      TypeCoercer<SourcePath> sourcePathTypeCoercer,
      TypeCoercer<ImmutableList<String>> flagsTypeCoercer) {
    this.sourcePathWithFlagsTypeCoercer =
        new PairTypeCoercer<>(sourcePathTypeCoercer, flagsTypeCoercer);
  }

  @Override
  public Class<SourceWithFlags> getOutputClass() {
    return SourceWithFlags.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return sourcePathWithFlagsTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(SourceWithFlags object, Traversal traversal) {
    sourcePathWithFlagsTypeCoercer.traverse(
        new Pair<>(object.getSourcePath(), object.getFlags()),
        traversal);
  }

  @Override
  public SourceWithFlags coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {
    if (object instanceof SourceWithFlags) {
      return (SourceWithFlags) object;
    }

    Object source;
    Object flags;
    if (object instanceof String) {
      source = object;
      flags = ImmutableList.of();
    } else if (object instanceof Collection<?> && ((Collection<?>) object).size() == 2) {
      Iterator<?> iterator = ((Collection<?>) object).iterator();
      source = iterator.next();
      flags = iterator.next();
    } else {
      throw CoerceFailedException.simple(
          object,
          getOutputClass(),
          "input should be either a source path or a pair of a source path and a list of flags");
    }

    Pair<SourcePath, ImmutableList<String>> sourcePathWithFlags =
        sourcePathWithFlagsTypeCoercer.coerce(
            cellRoots,
            filesystem,
            pathRelativeToProjectRoot,
            ImmutableList.of(source, flags));
    return SourceWithFlags.of(
        sourcePathWithFlags.getFirst(),
        sourcePathWithFlags.getSecond());
  }

  @Override
  public <U> SourceWithFlags mapAllInternal(
      Function<U, U> function,
      Class<U> targetClass,
      SourceWithFlags object) throws CoerceFailedException {
    if (!sourcePathWithFlagsTypeCoercer.hasElementClass(TargetNode.class)) {
      return object;
    }
    Pair<SourcePath, ImmutableList<String>> pair =
        new Pair<>(object.getSourcePath(), object.getFlags());
    pair = sourcePathWithFlagsTypeCoercer.mapAll(function, targetClass, pair);
    return SourceWithFlags.of(pair.getFirst(), pair.getSecond());
  }
}
