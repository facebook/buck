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
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.HostTargetConfigurationResolver;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.UnconfiguredSourcePath;
import com.facebook.buck.core.sourcepath.UnconfiguredSourceWithFlags;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.UnconfiguredStringWithMacros;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import java.util.Collection;

/** A type coercer to handle source entries with a list of flags. */
public class SourceWithFlagsTypeCoercer
    implements TypeCoercer<UnconfiguredSourceWithFlags, SourceWithFlags> {
  private final TypeCoercer<UnconfiguredSourcePath, SourcePath> sourcePathTypeCoercer;
  private final TypeCoercer<
          ImmutableList<UnconfiguredStringWithMacros>, ImmutableList<StringWithMacros>>
      flagsTypeCoercer;
  private final TypeCoercer<
          Pair<UnconfiguredSourcePath, ImmutableList<UnconfiguredStringWithMacros>>,
          Pair<SourcePath, ImmutableList<StringWithMacros>>>
      sourcePathWithFlagsTypeCoercer;

  SourceWithFlagsTypeCoercer(
      TypeCoercer<UnconfiguredSourcePath, SourcePath> sourcePathTypeCoercer,
      TypeCoercer<ImmutableList<UnconfiguredStringWithMacros>, ImmutableList<StringWithMacros>>
          flagsTypeCoercer) {
    this.sourcePathTypeCoercer = sourcePathTypeCoercer;
    this.flagsTypeCoercer = flagsTypeCoercer;
    this.sourcePathWithFlagsTypeCoercer =
        new PairTypeCoercer<>(sourcePathTypeCoercer, flagsTypeCoercer);
  }

  @Override
  public TypeToken<SourceWithFlags> getOutputType() {
    return TypeToken.of(SourceWithFlags.class);
  }

  @Override
  public TypeToken<UnconfiguredSourceWithFlags> getUnconfiguredType() {
    return TypeToken.of(UnconfiguredSourceWithFlags.class);
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return sourcePathTypeCoercer.hasElementClass(types) || flagsTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverseUnconfigured(
      CellNameResolver cellRoots, UnconfiguredSourceWithFlags object, Traversal traversal) {
    sourcePathTypeCoercer.traverseUnconfigured(cellRoots, object.getSourcePath(), traversal);
    flagsTypeCoercer.traverseUnconfigured(
        cellRoots, ImmutableList.copyOf(object.getFlags()), traversal);
  }

  @Override
  public void traverse(CellNameResolver cellRoots, SourceWithFlags object, Traversal traversal) {
    sourcePathTypeCoercer.traverse(cellRoots, object.getSourcePath(), traversal);
    flagsTypeCoercer.traverse(cellRoots, ImmutableList.copyOf(object.getFlags()), traversal);
  }

  @Override
  public UnconfiguredSourceWithFlags coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {

    // We're expecting one of two types here. They can be differentiated pretty easily.
    if (object instanceof String) {
      return UnconfiguredSourceWithFlags.of(
          sourcePathTypeCoercer.coerceToUnconfigured(
              cellRoots, filesystem, pathRelativeToProjectRoot, object),
          ImmutableList.of());
    }

    // If we get this far, we're dealing with a Pair of a SourcePath and a String.
    if (object instanceof Collection<?> && ((Collection<?>) object).size() == 2) {
      Pair<UnconfiguredSourcePath, ImmutableList<UnconfiguredStringWithMacros>>
          sourcePathWithFlags =
              sourcePathWithFlagsTypeCoercer.coerceToUnconfigured(
                  cellRoots, filesystem, pathRelativeToProjectRoot, object);
      return UnconfiguredSourceWithFlags.of(
          sourcePathWithFlags.getFirst(), sourcePathWithFlags.getSecond());
    }

    throw CoerceFailedException.simple(
        object,
        getOutputType(),
        "input should be either a source path or a pair of a source path and a list of flags");
  }

  @Override
  public SourceWithFlags coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      HostTargetConfigurationResolver hostConfigurationResolver,
      UnconfiguredSourceWithFlags object)
      throws CoerceFailedException {
    return object.configure(cellRoots, filesystem, targetConfiguration, hostConfigurationResolver);
  }
}
