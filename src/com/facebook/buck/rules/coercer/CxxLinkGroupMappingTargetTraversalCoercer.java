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
import com.facebook.buck.core.linkgroup.CxxLinkGroupMappingTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.reflect.TypeToken;

/**
 * {@link TypeCoercer} for {@link CxxLinkGroupMappingTarget.Traversal}.
 *
 * <p>This {@link TypeCoercer} is used to convert the traversal of a link group mapping (e.g.,
 * <code>(..., "tree")</code>) to a {@link CxxLinkGroupMappingTarget.Traversal}.
 */
public class CxxLinkGroupMappingTargetTraversalCoercer
    extends LeafTypeCoercer<CxxLinkGroupMappingTarget.Traversal> {

  @Override
  public TypeToken<CxxLinkGroupMappingTarget.Traversal> getOutputType() {
    return TypeToken.of(CxxLinkGroupMappingTarget.Traversal.class);
  }

  @Override
  public CxxLinkGroupMappingTarget.Traversal coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem alsoUnused,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object object)
      throws CoerceFailedException {
    if (!(object instanceof String)) {
      throw CoerceFailedException.simple(object, getOutputType());
    }

    String inputString = (String) object;
    for (CxxLinkGroupMappingTarget.Traversal traversal :
        CxxLinkGroupMappingTarget.Traversal.values()) {
      if (traversal.toString().equalsIgnoreCase(inputString)) {
        return traversal;
      }
    }

    throw CoerceFailedException.simple(
        object, getOutputType(), "Value is not a valid traversal type");
  }
}
