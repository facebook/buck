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

package com.facebook.buck.features.go;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;

/** Factory to create {@link GoPlatform}s from a {@link BuckConfig} section. */
@BuckStyleValue
abstract class GoPlatformFactory {

  abstract BuckConfig getBuckConfig();

  abstract ProcessExecutor getProcessExecutor();

  abstract ExecutableFinder getExecutableFinder();

  abstract FlavorDomain<UnresolvedCxxPlatform> getCxxPlatforms();

  abstract UnresolvedCxxPlatform getDefaultCxxPlatform();

  public static GoOs getDefaultOs() {
    Platform platform = Platform.detect();
    if (platform == Platform.UNKNOWN) {
      throw new HumanReadableException("Unable to detect system platform");
    }
    return GoOs.fromPlatform(platform);
  }

  public static GoArch getDefaultArch() {
    Architecture arch = Architecture.detect();
    if (arch == Architecture.UNKNOWN) {
      throw new HumanReadableException("Unable to detect system architecture");
    }
    return GoArch.fromArchitecture(arch);
  }

  /** @return the {@link GoPlatform} defined in the given {@code section}. */
  public UnresolvedGoPlatform getPlatform(String section, Flavor flavor) {
    return ImmutableConfigBasedUnresolvedGoPlatform.ofImpl(
        section,
        flavor,
        getBuckConfig(),
        getBuckConfig()
            .getValue(section, "cxx_platform")
            .map(InternalFlavor::of)
            .map(getCxxPlatforms()::getValue)
            .orElse(getDefaultCxxPlatform()),
        getProcessExecutor(),
        getExecutableFinder());
  }
}
