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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.impl.DefaultCxxPlatforms;
import com.facebook.buck.io.ExecutableFinder;

public class HaskellPlatformsFactory {

  private final BuckConfig buckConfig;
  private final HaskellBuckConfig haskellBuckConfig;
  private final ExecutableFinder executableFinder;

  public HaskellPlatformsFactory(BuckConfig buckConfig, ExecutableFinder executableFinder) {
    this.buckConfig = buckConfig;
    this.haskellBuckConfig = new HaskellBuckConfig(buckConfig);
    this.executableFinder = executableFinder;
  }

  private UnresolvedHaskellPlatform getPlatform(
      String section, UnresolvedCxxPlatform unresolvedCxxPlatform) {
    return new ConfigBasedUnresolvedHaskellPlatform(
        section, haskellBuckConfig, unresolvedCxxPlatform, buckConfig, executableFinder);
  }

  /** Maps the cxxPlatforms to corresponding HaskellPlatform. */
  public FlavorDomain<UnresolvedHaskellPlatform> getPlatforms(
      FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms) {
    // Use convert (instead of map) so that if we ever have the haskell platform flavor different
    // from the underlying c++ platform's flavor this will continue to work correctly.
    return cxxPlatforms.convert(
        "Haskell platform",
        cxxPlatform ->
            // We special case the "default" C/C++ platform to just use the "haskell" section.
            cxxPlatform.getFlavor().equals(DefaultCxxPlatforms.FLAVOR)
                ? getPlatform(haskellBuckConfig.getDefaultSection(), cxxPlatform)
                : getPlatform(haskellBuckConfig.getSectionForPlatform(cxxPlatform), cxxPlatform));
  }
}
