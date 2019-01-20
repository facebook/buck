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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Collection;

/** A type coercer to handle source entries with a list of flags. */
public class SourceWithFlagsTypeCoercer implements TypeCoercer<SourceWithFlags> {
  private final TypeCoercer<SourcePath> sourcePathTypeCoercer;
  private final TypeCoercer<ImmutableList<String>> flagsTypeCoercer;
  private final TypeCoercer<Pair<SourcePath, ImmutableList<String>>> sourcePathWithFlagsTypeCoercer;

  SourceWithFlagsTypeCoercer(
      TypeCoercer<SourcePath> sourcePathTypeCoercer,
      TypeCoercer<ImmutableList<String>> flagsTypeCoercer) {
    this.sourcePathTypeCoercer = sourcePathTypeCoercer;
    this.flagsTypeCoercer = flagsTypeCoercer;
    this.sourcePathWithFlagsTypeCoercer =
        new PairTypeCoercer<>(sourcePathTypeCoercer, flagsTypeCoercer);
  }

  @Override
  public Class<SourceWithFlags> getOutputClass() {
    return SourceWithFlags.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return sourcePathTypeCoercer.hasElementClass(types) || flagsTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellPathResolver cellRoots, SourceWithFlags object, Traversal traversal) {
    sourcePathTypeCoercer.traverse(cellRoots, object.getSourcePath(), traversal);
    flagsTypeCoercer.traverse(cellRoots, ImmutableList.copyOf(object.getFlags()), traversal);
  }

  @Override
  public SourceWithFlags coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof SourceWithFlags) {
      return (SourceWithFlags) object;
    }

    // We're expecting one of two types here. They can be differentiated pretty easily.
    if (object instanceof String) {
      return SourceWithFlags.of(
          sourcePathTypeCoercer.coerce(cellRoots, filesystem, pathRelativeToProjectRoot, object));
    }

    // If we get this far, we're dealing with a Pair of a SourcePath and a String.
    if (object instanceof Collection<?> && ((Collection<?>) object).size() == 2) {
      Pair<SourcePath, ImmutableList<String>> sourcePathWithFlags =
          sourcePathWithFlagsTypeCoercer.coerce(
              cellRoots, filesystem, pathRelativeToProjectRoot, object);
      return SourceWithFlags.of(sourcePathWithFlags.getFirst(), sourcePathWithFlags.getSecond());
    }

    throw CoerceFailedException.simple(
        object,
        getOutputClass(),
        "input should be either a source path or a pair of a source path and a list of flags");
  }
}
