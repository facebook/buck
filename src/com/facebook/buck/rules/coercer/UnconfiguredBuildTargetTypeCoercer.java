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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;

/** {@link TypeCoercer} for {@link UnconfiguredBuildTargetView} */
public class UnconfiguredBuildTargetTypeCoercer
    extends LeafTypeCoercer<UnconfiguredBuildTargetView> {

  private final UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory;

  public UnconfiguredBuildTargetTypeCoercer(
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory) {
    this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
  }

  @Override
  public Class<UnconfiguredBuildTargetView> getOutputClass() {
    return UnconfiguredBuildTargetView.class;
  }

  @Override
  public UnconfiguredBuildTargetView coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem alsoUnused,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object object)
      throws CoerceFailedException {
    if (!(object instanceof String)) {
      throw CoerceFailedException.simple(object, getOutputClass());
    }
    String param = (String) object;

    try {
      return unconfiguredBuildTargetFactory.createForPathRelativeToProjectRoot(
          pathRelativeToProjectRoot, param, cellRoots.getCellNameResolver());
    } catch (BuildTargetParseException e) {
      throw new CoerceFailedException(
          String.format(
              "Unable to find the target %s.\n%s", object, e.getHumanReadableErrorMessage()),
          e);
    }
  }
}
