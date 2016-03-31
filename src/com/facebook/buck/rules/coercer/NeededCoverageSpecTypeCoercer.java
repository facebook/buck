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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.python.NeededCoverageSpec;
import com.google.common.base.Function;
import com.google.common.base.Optional;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;

/**
 * A type coercer to handle needed coverage specification for python_test.
 */
public class NeededCoverageSpecTypeCoercer implements TypeCoercer<NeededCoverageSpec> {
  private final TypeCoercer<Float> floatTypeCoercer;
  private final TypeCoercer<BuildTarget> buildTargetTypeCoercer;

  NeededCoverageSpecTypeCoercer(
      TypeCoercer<Float> floatTypeCoercer,
      TypeCoercer<BuildTarget> buildTargetTypeCoercer) {
    this.floatTypeCoercer = floatTypeCoercer;
    this.buildTargetTypeCoercer = buildTargetTypeCoercer;
  }

  @Override
  public Class<NeededCoverageSpec> getOutputClass() {
    return NeededCoverageSpec.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return floatTypeCoercer.hasElementClass(types) ||
        buildTargetTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(NeededCoverageSpec object, Traversal traversal) {
    floatTypeCoercer.traverse(object.getNeededCoverageRatio(), traversal);
    buildTargetTypeCoercer.traverse(object.getBuildTarget(), traversal);
  }

  @Override
  public Optional<NeededCoverageSpec> getOptionalValue() {
    return Optional.absent();
  }

  @Override
  public NeededCoverageSpec coerce(
      Function<Optional<String>, Path> cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {
    if (object instanceof NeededCoverageSpec) {
      return (NeededCoverageSpec) object;
    }

    if (object instanceof Collection<?>) {
      Collection<?> collection = (Collection<?>) object;
      if (collection.size() == 2) {
        Iterator<?> iter = collection.iterator();
        Float neededRatio = floatTypeCoercer.coerce(
            cellRoots,
            filesystem,
            pathRelativeToProjectRoot,
            iter.next());
        if (neededRatio < 0 || neededRatio > 1) {
          throw CoerceFailedException.simple(
              object,
              getOutputClass(),
              "the needed coverage ratio should be in range [0; 1]");
        }
        BuildTarget buildTarget = buildTargetTypeCoercer.coerce(
            cellRoots,
            filesystem,
            pathRelativeToProjectRoot,
            iter.next());
        return NeededCoverageSpec.of(neededRatio, buildTarget);
      }
    }

    throw CoerceFailedException.simple(
        object,
        getOutputClass(),
        "input should be a tuple of needed coverage ratio and a build target");
  }
}
