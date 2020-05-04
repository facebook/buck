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

package com.facebook.buck.core.parser.buildtargetparser;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.util.types.Either;

/** A factory that parses a given build target name using the provided {@link BuildTargetParser}. */
public class ParsingUnconfiguredBuildTargetViewFactory
    implements UnconfiguredBuildTargetViewFactory {

  private final BuildTargetParser buildTargetParser = BuildTargetParser.INSTANCE;

  @Override
  public UnconfiguredBuildTarget create(String buildTargetName, CellNameResolver cellNameResolver) {
    return buildTargetParser.parseTarget(buildTargetName, null, cellNameResolver);
  }

  @Override
  public UnconfiguredBuildTarget createForBaseName(
      BaseName baseName, String buildTargetName, CellNameResolver cellNameResolver) {
    return buildTargetParser.parseTarget(buildTargetName, baseName, cellNameResolver);
  }

  @Override
  public UnconfiguredBuildTarget createForPathRelativeToProjectRoot(
      ForwardRelativePath pathRelativeToProjectRoot,
      String buildTargetName,
      CellNameResolver cellNameResolver) {
    return createForBaseName(
        BaseName.ofPath(pathRelativeToProjectRoot), buildTargetName, cellNameResolver);
  }

  @Override
  public UnflavoredBuildTarget createUnflavoredForPathRelativeToProjectRoot(
      ForwardRelativePath pathRelativeToProjectRoot,
      String buildTargetName,
      CellNameResolver cellNameResolver) {
    UnconfiguredBuildTarget unconfiguredBuildTarget =
        createForPathRelativeToProjectRoot(
            pathRelativeToProjectRoot, buildTargetName, cellNameResolver);
    if (!unconfiguredBuildTarget.getFlavors().isEmpty()) {
      throw new BuildTargetParseException(
          String.format("expecting unflavored target: %s", buildTargetName));
    }
    return unconfiguredBuildTarget.getUnflavoredBuildTarget();
  }

  @Override
  public Either<UnconfiguredBuildTarget, CellRelativePath> createWithWildcard(
      String buildTargetName, CellNameResolver cellNameResolver) {
    return buildTargetParser.parseTargetOrPackageWildcard(buildTargetName, null, cellNameResolver);
  }
}
