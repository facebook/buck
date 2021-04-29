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

package com.facebook.buck.jvm.core;

import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.BuckPaths;

/** Extra params from {@link BuildTarget} needed for Kotlin. */
@BuckStyleValue
public abstract class BuildTargetValueExtraParams {

  public abstract ForwardRelPath getCellRelativeBasePath();

  public abstract boolean isFlavored();

  public abstract ForwardRelPath getBasePathForBaseName();

  public abstract String getShortNameAndFlavorPostfix();

  public abstract String getShortName();

  public abstract BuckPaths getBuckPaths();

  /** Creates {@link BuildTargetValueExtraParams} */
  public static BuildTargetValueExtraParams of(BuildTarget buildTarget, BuckPaths buckPaths) {
    return BuildTargetValueExtraParams.of(
        buildTarget.getCellRelativeBasePath().getPath(),
        buildTarget.isFlavored(),
        BuildTargetPaths.getBasePathForBaseName(
            buckPaths.shouldIncludeTargetConfigHash(buildTarget.getCellRelativeBasePath()),
            buildTarget),
        buildTarget.getShortNameAndFlavorPostfix(),
        buildTarget.getShortName(),
        buckPaths);
  }

  /** Creates {@link BuildTargetValueExtraParams} */
  public static BuildTargetValueExtraParams of(
      ForwardRelPath cellRelativeBasePath,
      boolean flavored,
      ForwardRelPath basePathForBaseName,
      String shortNameAndFlavorPostfix,
      String shortName,
      BuckPaths buckPaths) {
    return ImmutableBuildTargetValueExtraParams.ofImpl(
        cellRelativeBasePath,
        flavored,
        basePathForBaseName,
        shortNameAndFlavorPostfix,
        shortName,
        buckPaths);
  }
}
