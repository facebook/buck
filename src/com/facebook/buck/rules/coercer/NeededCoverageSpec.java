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
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.facebook.buck.versions.TargetTranslatable;
import java.util.Optional;

@BuckStyleValue
public abstract class NeededCoverageSpec implements TargetTranslatable<NeededCoverageSpec> {

  /**
   * Gets the coverage ratio that is required for a test
   *
   * @return The coverage ratio required for the build target, represented in a percentage integer
   *     (e.g., return value 42 means 42% coverage ratio)
   */
  public abstract int getNeededCoverageRatioPercentage();

  public abstract BuildTarget getBuildTarget();

  public abstract Optional<String> getPathName();

  public static NeededCoverageSpec of(
      int neededCoverageRatioPercentage, BuildTarget buildTarget, Optional<String> pathName) {
    return ImmutableNeededCoverageSpec.of(neededCoverageRatioPercentage, buildTarget, pathName);
  }

  @Override
  public Optional<NeededCoverageSpec> translateTargets(
      CellNameResolver cellPathResolver, BaseName targetBaseName, TargetNodeTranslator translator) {
    Optional<BuildTarget> newBuildTarget =
        translator.translate(cellPathResolver, targetBaseName, getBuildTarget());
    return newBuildTarget.map(
        buildTarget -> of(getNeededCoverageRatioPercentage(), buildTarget, getPathName()));
  }

  public NeededCoverageSpec withBuildTarget(BuildTarget target) {
    return of(getNeededCoverageRatioPercentage(), target, getPathName());
  }
}
