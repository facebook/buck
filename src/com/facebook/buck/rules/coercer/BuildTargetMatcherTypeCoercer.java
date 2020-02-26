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
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetMatcher;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetMatcherParser;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.reflect.TypeToken;

/** Coercer to {@link com.facebook.buck.core.parser.buildtargetparser.BuildTargetMatcher}. */
class BuildTargetMatcherTypeCoercer extends LeafUnconfiguredOnlyCoercer<BuildTargetMatcher> {

  @Override
  public TypeToken<BuildTargetMatcher> getUnconfiguredType() {
    return TypeToken.of(BuildTargetMatcher.class);
  }

  @Override
  public BuildTargetMatcher coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    // This is only actually used directly by ConstructorArgMarshaller, for parsing the
    // groups list. It's also queried (but not actually used) when Descriptions declare
    // deps fields.
    // TODO(csarbora): make this work for all types of BuildTargetPatterns
    // probably differentiate them by inheritance
    return BuildTargetMatcherParser.forVisibilityArgument().parse((String) object, cellRoots);
  }
}
