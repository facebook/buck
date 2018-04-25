/*
 * Copyright 2016-present Facebook, Inc.
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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;

/** A type coercer to handle needed coverage specification for python_test. */
public class NeededCoverageSpecTypeCoercer implements TypeCoercer<NeededCoverageSpec> {
  private final TypeCoercer<Float> floatTypeCoercer;
  private final TypeCoercer<BuildTarget> buildTargetTypeCoercer;
  private final TypeCoercer<String> pathNameTypeCoercer;

  NeededCoverageSpecTypeCoercer(
      TypeCoercer<Float> floatTypeCoercer,
      TypeCoercer<BuildTarget> buildTargetTypeCoercer,
      TypeCoercer<String> pathNameTypeCoercer) {
    this.floatTypeCoercer = floatTypeCoercer;
    this.buildTargetTypeCoercer = buildTargetTypeCoercer;
    this.pathNameTypeCoercer = pathNameTypeCoercer;
  }

  @Override
  public Class<NeededCoverageSpec> getOutputClass() {
    return NeededCoverageSpec.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return floatTypeCoercer.hasElementClass(types)
        || buildTargetTypeCoercer.hasElementClass(types)
        || pathNameTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellPathResolver cellRoots, NeededCoverageSpec object, Traversal traversal) {
    floatTypeCoercer.traverse(cellRoots, object.getNeededCoverageRatio(), traversal);
    buildTargetTypeCoercer.traverse(cellRoots, object.getBuildTarget(), traversal);
    Optional<String> pathName = object.getPathName();
    if (pathName.isPresent()) {
      pathNameTypeCoercer.traverse(cellRoots, pathName.get(), traversal);
    }
  }

  @Override
  public NeededCoverageSpec coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof NeededCoverageSpec) {
      return (NeededCoverageSpec) object;
    }

    if (object instanceof Collection<?>) {
      Collection<?> collection = (Collection<?>) object;
      if (collection.size() == 2 || collection.size() == 3) {
        Iterator<?> iter = collection.iterator();
        Float neededRatio =
            floatTypeCoercer.coerce(cellRoots, filesystem, pathRelativeToProjectRoot, iter.next());
        if (neededRatio < 0 || neededRatio > 1) {
          throw CoerceFailedException.simple(
              object, getOutputClass(), "the needed coverage ratio should be in range [0; 1]");
        }
        BuildTarget buildTarget =
            buildTargetTypeCoercer.coerce(
                cellRoots, filesystem, pathRelativeToProjectRoot, iter.next());
        Optional<String> pathName = Optional.empty();
        if (iter.hasNext()) {
          pathName =
              Optional.of(
                  pathNameTypeCoercer.coerce(
                      cellRoots, filesystem, pathRelativeToProjectRoot, iter.next()));
        }
        return NeededCoverageSpec.of(neededRatio, buildTarget, pathName);
      }
    }

    throw CoerceFailedException.simple(
        object,
        getOutputClass(),
        "input should be a tuple of needed coverage ratio, a build target, and optionally a path");
  }
}
