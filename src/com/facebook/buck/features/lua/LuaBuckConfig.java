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

package com.facebook.buck.features.lua;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.impl.DefaultCxxPlatforms;
import com.facebook.buck.io.ExecutableFinder;

public class LuaBuckConfig {

  private static final String SECTION_PREFIX = "lua";

  private final BuckConfig delegate;
  private final ExecutableFinder finder;

  public LuaBuckConfig(BuckConfig delegate, ExecutableFinder finder) {
    this.delegate = delegate;
    this.finder = finder;
  }

  /**
   * @return the {@link CxxPlatform} wrapped in a {@link LuaPlatform} defined in
   *     `lua#<cxx-platform-flavor>` config section.
   */
  public UnresolvedLuaPlatform getPlatform(UnresolvedCxxPlatform cxxPlatform) {
    // We special case the "default" C/C++ platform to just use the "lua" section,
    // otherwise we load the `LuaPlatform` from the `lua#<cxx-platform-flavor>` section.
    return new ConfigBasedUnresolvedLuaPlatform(
        cxxPlatform.getFlavor().equals(DefaultCxxPlatforms.FLAVOR)
            ? SECTION_PREFIX
            : String.format("%s#%s", SECTION_PREFIX, cxxPlatform.getFlavor()),
        delegate,
        finder,
        cxxPlatform);
  }

  /**
   * @return for each passed in {@link CxxPlatform}, build and wrap it in a {@link LuaPlatform}
   *     defined in the `lua#<cxx-platform-flavor>` config section.
   */
  FlavorDomain<UnresolvedLuaPlatform> getPlatforms(
      FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms) {
    return cxxPlatforms.convert(LuaPlatform.FLAVOR_DOMAIN_NAME, this::getPlatform);
  }
}
