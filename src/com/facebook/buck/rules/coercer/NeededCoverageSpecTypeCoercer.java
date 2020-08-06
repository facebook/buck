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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.reflect.TypeToken;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;

/** A type coercer to handle needed coverage specification for python_test. */
public class NeededCoverageSpecTypeCoercer implements TypeCoercer<Object, NeededCoverageSpec> {
  private final TypeCoercer<Integer, Integer> intTypeCoercer;
  private final TypeCoercer<UnconfiguredBuildTarget, BuildTarget> buildTargetTypeCoercer;
  private final TypeCoercer<String, String> pathNameTypeCoercer;

  NeededCoverageSpecTypeCoercer(
      TypeCoercer<Integer, Integer> intTypeCoercer,
      TypeCoercer<UnconfiguredBuildTarget, BuildTarget> buildTargetTypeCoercer,
      TypeCoercer<String, String> pathNameTypeCoercer) {
    this.intTypeCoercer = intTypeCoercer;
    this.buildTargetTypeCoercer = buildTargetTypeCoercer;
    this.pathNameTypeCoercer = pathNameTypeCoercer;
  }

  @Override
  public TypeToken<NeededCoverageSpec> getOutputType() {
    return TypeToken.of(NeededCoverageSpec.class);
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return intTypeCoercer.hasElementClass(types)
        || buildTargetTypeCoercer.hasElementClass(types)
        || pathNameTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellNameResolver cellRoots, NeededCoverageSpec object, Traversal traversal) {
    intTypeCoercer.traverse(cellRoots, object.getNeededCoverageRatioPercentage(), traversal);
    buildTargetTypeCoercer.traverse(cellRoots, object.getBuildTarget(), traversal);
    Optional<String> pathName = object.getPathName();
    if (pathName.isPresent()) {
      pathNameTypeCoercer.traverse(cellRoots, pathName.get(), traversal);
    }
  }

  @Override
  public TypeToken<Object> getUnconfiguredType() {
    return TypeToken.of(Object.class);
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
  public NeededCoverageSpec coerce(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
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
                cellNameResolver,
                filesystem,
                pathRelativeToProjectRoot,
                targetConfiguration,
                hostConfiguration,
                object,
                iter.next());
        BuildTarget buildTarget =
            buildTargetTypeCoercer.coerceBoth(
                cellNameResolver,
                filesystem,
                pathRelativeToProjectRoot,
                targetConfiguration,
                hostConfiguration,
                iter.next());
        Optional<String> pathName = Optional.empty();
        if (iter.hasNext()) {
          pathName =
              Optional.of(
                  pathNameTypeCoercer.coerceBoth(
                      cellNameResolver,
                      filesystem,
                      pathRelativeToProjectRoot,
                      targetConfiguration,
                      hostConfiguration,
                      iter.next()));
        }
        return NeededCoverageSpec.of(neededRatioPercentage, buildTarget, pathName);
      }
    }

    throw CoerceFailedException.simple(
        object,
        getOutputType(),
        "input should be a tuple of needed coverage ratio, a build target, and optionally a path");
  }

  private int coerceNeededRatio(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object originalObject,
      Object object)
      throws CoerceFailedException {

    // Because TypeCoercer<Integer> handles float without throwing any exception, here we want to
    // explicitly throw an exception if non-integral numbers are used for coverage ratio to avoid
    // misuse of the data types
    if (!(object instanceof Integer || object instanceof Long || object instanceof Short)) {
      throw CoerceFailedException.simple(
          originalObject,
          getOutputType(),
          "the needed coverage ratio should be an integral number");
    }

    int intValue =
        intTypeCoercer.coerceBoth(
            cellNameResolver,
            filesystem,
            pathRelativeToProjectRoot,
            targetConfiguration,
            hostConfiguration,
            object);

    if (intValue < 0 || intValue > 100) {
      throw CoerceFailedException.simple(
          originalObject, getOutputType(), "the needed coverage ratio should be in range [0, 100]");
    }

    return intValue;
  }
}
