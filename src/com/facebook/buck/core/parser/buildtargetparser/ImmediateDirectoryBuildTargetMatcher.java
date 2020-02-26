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

import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** A pattern matches build targets that are all in the same directory. */
@BuckStyleValue
abstract class ImmediateDirectoryBuildTargetMatcher implements BuildTargetMatcher {

  protected abstract CellRelativePath getCellRelativeBasePath();

  /**
   * @return true if the given target not null and has the same basePathWithSlash, otherwise return
   *     false.
   */
  @Override
  public boolean matches(UnconfiguredBuildTarget target) {
    return getCellRelativeBasePath().equals(target.getCellRelativeBasePath());
  }

  @Override
  public String getCellFreeRepresentation() {
    return "//" + getCellRelativeBasePath().getPath() + ":";
  }

  @Override
  public String toString() {
    return getCellRelativeBasePath() + ":";
  }
}
