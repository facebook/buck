/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.Optional;
import java.util.Set;

public class CxxBinaryFlavored implements Flavored {

  private final ToolchainProvider toolchainProvider;
  private final CxxBuckConfig cxxBuckConfig;

  public CxxBinaryFlavored(ToolchainProvider toolchainProvider, CxxBuckConfig cxxBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.cxxBuckConfig = cxxBuckConfig;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> inputFlavors) {
    Set<Flavor> flavors = inputFlavors;

    Set<Flavor> platformFlavors =
        Sets.intersection(
            flavors,
            Sets.union(
                getCxxPlatformsProvider().getUnresolvedCxxPlatforms().getFlavors(),
                cxxBuckConfig.getDeclaredPlatforms()));
    if (platformFlavors.size() > 1) {
      return false;
    }
    flavors = Sets.difference(flavors, platformFlavors);

    flavors =
        Sets.difference(
            flavors,
            ImmutableSet.of(
                CxxDescriptionEnhancer.CXX_LINK_MAP_FLAVOR,
                CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR,
                CxxDescriptionEnhancer.INCREMENTAL_THINLTO,
                CxxCompilationDatabase.COMPILATION_DATABASE,
                CxxCompilationDatabase.UBER_COMPILATION_DATABASE,
                CxxInferEnhancer.InferFlavors.INFER.getFlavor(),
                CxxInferEnhancer.InferFlavors.INFER_ANALYZE.getFlavor(),
                CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.getFlavor(),
                StripStyle.ALL_SYMBOLS.getFlavor(),
                StripStyle.DEBUGGING_SYMBOLS.getFlavor(),
                StripStyle.NON_GLOBAL_SYMBOLS.getFlavor(),
                LinkerMapMode.NO_LINKER_MAP.getFlavor()));

    return flavors.isEmpty();
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(
        ImmutableSet.of(
            // Missing: CXX Compilation Database
            // Missing: CXX Description Enhancer
            // Missing: CXX Infer Enhancer
            getCxxPlatformsProvider().getUnresolvedCxxPlatforms(),
            LinkerMapMode.FLAVOR_DOMAIN,
            StripStyle.FLAVOR_DOMAIN));
  }

  private CxxPlatformsProvider getCxxPlatformsProvider() {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
  }
}
