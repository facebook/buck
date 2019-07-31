/*
 * Copyright 2013-present Facebook, Inc.
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
import com.facebook.buck.core.linkgroup.CxxLinkGroupMappingTarget;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.types.Pair;
import java.nio.file.Path;
import java.util.Collection;

/**
 * {@link TypeCoercer} for {@link CxxLinkGroupMappingTarget}.
 *
 * <p>This {@link TypeCoercer} is used to convert a single link group mapping (e.g., <code>
 * ("//Some:Target", "tree")</code>) to a {@link CxxLinkGroupMappingTarget}.
 */
public class CxxLinkGroupMappingTargetCoercer implements TypeCoercer<CxxLinkGroupMappingTarget> {
  private final TypeCoercer<BuildTarget> buildTargetTypeCoercer;
  private final TypeCoercer<CxxLinkGroupMappingTarget.Traversal> traversalCoercer;
  private final TypeCoercer<Pair<BuildTarget, CxxLinkGroupMappingTarget.Traversal>>
      buildTargetWithTraversalTypeCoercer;

  public CxxLinkGroupMappingTargetCoercer(
      TypeCoercer<BuildTarget> buildTargetTypeCoercer,
      TypeCoercer<CxxLinkGroupMappingTarget.Traversal> traversalCoercer) {
    this.buildTargetTypeCoercer = buildTargetTypeCoercer;
    this.traversalCoercer = traversalCoercer;
    this.buildTargetWithTraversalTypeCoercer =
        new PairTypeCoercer<>(this.buildTargetTypeCoercer, this.traversalCoercer);
  }

  @Override
  public Class<CxxLinkGroupMappingTarget> getOutputClass() {
    return CxxLinkGroupMappingTarget.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return buildTargetTypeCoercer.hasElementClass(types) || traversalCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(
      CellPathResolver cellRoots, CxxLinkGroupMappingTarget object, Traversal traversal) {
    buildTargetTypeCoercer.traverse(cellRoots, object.getBuildTarget(), traversal);
    traversalCoercer.traverse(cellRoots, object.getTraversal(), traversal);
  }

  @Override
  public CxxLinkGroupMappingTarget coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      Object object)
      throws CoerceFailedException {

    if (object instanceof CxxLinkGroupMappingTarget) {
      return (CxxLinkGroupMappingTarget) object;
    }

    if (object instanceof Collection<?> && ((Collection<?>) object).size() == 2) {
      Pair<BuildTarget, CxxLinkGroupMappingTarget.Traversal> buildTargetWithTraversal =
          buildTargetWithTraversalTypeCoercer.coerce(
              cellRoots, filesystem, pathRelativeToProjectRoot, targetConfiguration, object);
      return CxxLinkGroupMappingTarget.of(
          buildTargetWithTraversal.getFirst(), buildTargetWithTraversal.getSecond());
    }

    throw CoerceFailedException.simple(
        object, getOutputClass(), "input should be pair of a build target and traversal");
  }
}
