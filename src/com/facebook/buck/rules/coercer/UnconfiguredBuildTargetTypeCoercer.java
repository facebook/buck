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
import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.UnflavoredBuildTargetView;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;

/** {@link TypeCoercer} for {@link UnconfiguredBuildTargetView} */
public class UnconfiguredBuildTargetTypeCoercer
    extends LeafTypeCoercer<UnconfiguredBuildTargetView> {

  private final UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory;

  public UnconfiguredBuildTargetTypeCoercer(
      UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory) {
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
      Path pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      Object object)
      throws CoerceFailedException {
    if (!(object instanceof String)) {
      throw CoerceFailedException.simple(object, getOutputClass());
    }
    String param = (String) object;

    try {
      String baseName =
          UnflavoredBuildTargetView.BUILD_TARGET_PREFIX
              + MorePaths.pathWithUnixSeparators(pathRelativeToProjectRoot);

      return unconfiguredBuildTargetFactory.createForBaseName(cellRoots, baseName, param);
    } catch (BuildTargetParseException e) {
      throw new CoerceFailedException(
          String.format(
              "Unable to find the target %s.\n%s", object, e.getHumanReadableErrorMessage()),
          e);
    }
  }
}
