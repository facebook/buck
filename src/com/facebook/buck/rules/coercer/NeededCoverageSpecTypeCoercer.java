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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import javax.annotation.Nonnull;

/** A type coercer to handle needed coverage specification for python_test. */
public class NeededCoverageSpecTypeCoercer implements TypeCoercer<NeededCoverageSpec> {
  private final TypeCoercer<Integer> intTypeCoercer;
  private final TypeCoercer<BuildTarget> buildTargetTypeCoercer;
  private final TypeCoercer<String> pathNameTypeCoercer;

  NeededCoverageSpecTypeCoercer(
      TypeCoercer<Integer> intTypeCoercer,
      TypeCoercer<BuildTarget> buildTargetTypeCoercer,
      TypeCoercer<String> pathNameTypeCoercer) {
    this.intTypeCoercer = intTypeCoercer;
    this.buildTargetTypeCoercer = buildTargetTypeCoercer;
    this.pathNameTypeCoercer = pathNameTypeCoercer;
  }

  @Override
  public Class<NeededCoverageSpec> getOutputClass() {
    return NeededCoverageSpec.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return intTypeCoercer.hasElementClass(types)
        || buildTargetTypeCoercer.hasElementClass(types)
        || pathNameTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellPathResolver cellRoots, NeededCoverageSpec object, Traversal traversal) {
    intTypeCoercer.traverse(cellRoots, object.getNeededCoverageRatioPercentage(), traversal);
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
      TargetConfiguration targetConfiguration,
      Object object)
      throws CoerceFailedException {
    if (object instanceof NeededCoverageSpec) {
      return (NeededCoverageSpec) object;
    }

    if (object instanceof Collection<?>) {
      Collection<?> collection = (Collection<?>) object;
      if (collection.size() == 2 || collection.size() == 3) {
        Iterator<?> iter = collection.iterator();
        int neededRatioPercentage =
            coerceNeededRatio(
                cellRoots,
                filesystem,
                pathRelativeToProjectRoot,
                targetConfiguration,
                object,
                iter.next());
        BuildTarget buildTarget =
            buildTargetTypeCoercer.coerce(
                cellRoots, filesystem, pathRelativeToProjectRoot, targetConfiguration, iter.next());
        Optional<String> pathName = Optional.empty();
        if (iter.hasNext()) {
          pathName =
              Optional.of(
                  pathNameTypeCoercer.coerce(
                      cellRoots,
                      filesystem,
                      pathRelativeToProjectRoot,
                      targetConfiguration,
                      iter.next()));
        }
        return NeededCoverageSpec.of(neededRatioPercentage, buildTarget, pathName);
      }
    }

    throw CoerceFailedException.simple(
        object,
        getOutputClass(),
        "input should be a tuple of needed coverage ratio, a build target, and optionally a path");
  }

  @Nonnull
  private int coerceNeededRatio(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      Object originalObject,
      Object object)
      throws CoerceFailedException {

    // Because TypeCoercer<Integer> handles float without throwing any exception, here we want to
    // explicitly throw an exception if non-integral numbers are used for coverage ratio to avoid
    // misuse of the data types
    if (!(object instanceof Integer || object instanceof Long || object instanceof Short)) {
      throw CoerceFailedException.simple(
          originalObject,
          getOutputClass(),
          "the needed coverage ratio should be an integral number");
    }

    int intValue =
        intTypeCoercer.coerce(
            cellRoots, filesystem, pathRelativeToProjectRoot, targetConfiguration, object);

    if (intValue < 0 || intValue > 100) {
      throw CoerceFailedException.simple(
          originalObject,
          getOutputClass(),
          "the needed coverage ratio should be in range [0, 100]");
    }

    return intValue;
  }
}
