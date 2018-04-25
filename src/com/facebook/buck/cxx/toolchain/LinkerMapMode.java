/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import java.util.Optional;

/** Defines if linker map should be generated or not. */
public enum LinkerMapMode implements FlavorConvertible {
  NO_LINKER_MAP(InternalFlavor.of("no-linkermap")),
  ;

  private final Flavor flavor;

  LinkerMapMode(Flavor flavor) {
    this.flavor = flavor;
  }

  @Override
  public Flavor getFlavor() {
    return flavor;
  }

  public static final FlavorDomain<LinkerMapMode> FLAVOR_DOMAIN =
      FlavorDomain.from("Linker Map Mode", LinkerMapMode.class);

  public static boolean isLinkerMapEnabledForBuildTarget(BuildTarget buildTarget) {
    return !buildTarget.getFlavors().contains(NO_LINKER_MAP.getFlavor());
  }

  public static BuildTarget removeLinkerMapModeFlavorInTarget(
      BuildTarget buildTarget, Optional<LinkerMapMode> flavoredLinkerMapMode) {
    if (flavoredLinkerMapMode.isPresent()) {
      return buildTarget.withoutFlavors(flavoredLinkerMapMode.get().getFlavor());
    }
    return buildTarget;
  }

  public static BuildTarget restoreLinkerMapModeFlavorInTarget(
      BuildTarget buildTarget, Optional<LinkerMapMode> flavoredLinkerMapMode) {
    if (flavoredLinkerMapMode.isPresent()) {
      return buildTarget.withAppendedFlavors(flavoredLinkerMapMode.get().getFlavor());
    }
    return buildTarget;
  }
}
