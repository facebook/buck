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

import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.UnconfiguredSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.reflect.TypeToken;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;

/** Coerce to {@link com.facebook.buck.rules.coercer.FrameworkPath}. */
public class FrameworkPathTypeCoercer implements TypeCoercer<Object, FrameworkPath> {

  private final TypeCoercer<UnconfiguredSourcePath, SourcePath> sourcePathTypeCoercer;

  public FrameworkPathTypeCoercer(
      TypeCoercer<UnconfiguredSourcePath, SourcePath> sourcePathTypeCoercer) {
    this.sourcePathTypeCoercer = sourcePathTypeCoercer;
  }

  @Override
  public TypeToken<FrameworkPath> getOutputType() {
    return TypeToken.of(FrameworkPath.class);
  }

  @Override
  public TypeToken<Object> getUnconfiguredType() {
    return TypeToken.of(Object.class);
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    for (Class<?> type : types) {
      if (type.isAssignableFrom(SourceTreePath.class)) {
        return true;
      }
    }
    return sourcePathTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellNameResolver cellRoots, FrameworkPath object, Traversal traversal) {
    switch (object.getType()) {
      case SOURCE_TREE_PATH:
        traversal.traverse(object.getSourceTreePath().get());
        break;
      case SOURCE_PATH:
        sourcePathTypeCoercer.traverse(cellRoots, object.getSourcePath().get(), traversal);
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
  public FrameworkPath coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object object)
      throws CoerceFailedException {
    if (object instanceof String) {
      Path path = Paths.get((String) object);

      String firstElement =
          Objects.requireNonNull(Iterables.getFirst(path, Paths.get(""))).toString();

      if (firstElement.startsWith("$")) { // NOPMD - length() > 0 && charAt(0) == '$' is ridiculous
        Optional<PBXReference.SourceTree> sourceTree =
            PBXReference.SourceTree.fromBuildSetting(firstElement);
        if (sourceTree.isPresent()) {
          int nameCount = path.getNameCount();
          if (nameCount < 2) {
            throw new HumanReadableException(
                "Invalid source tree path: '%s'. Should have at least one path component after"
                    + "'%s'.",
                path, firstElement);
          }
          return FrameworkPath.ofSourceTreePath(
              new SourceTreePath(
                  sourceTree.get(), path.subpath(1, path.getNameCount()), Optional.empty()));
        } else {
          throw new HumanReadableException(
              "Unknown SourceTree: '%s'. Should be one of: %s",
              firstElement,
              Joiner.on(", ")
                  .join(
                      Iterables.transform(
                          ImmutableList.copyOf(PBXReference.SourceTree.values()),
                          input -> "$" + input)));
        }
      } else {
        return FrameworkPath.ofSourcePath(
            sourcePathTypeCoercer.coerceBoth(
                cellRoots,
                filesystem,
                pathRelativeToProjectRoot,
                targetConfiguration,
                hostConfiguration,
                object));
      }
    }

    throw CoerceFailedException.simple(
        object, getOutputType(), "input should be either a source tree path or a source path");
  }
}
