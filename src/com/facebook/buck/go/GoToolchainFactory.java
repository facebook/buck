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

package com.facebook.buck.go;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.DefaultCxxPlatforms;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

public class GoToolchainFactory implements ToolchainFactory<GoToolchain> {

  @Override
  public Optional<GoToolchain> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {

    CxxPlatformsProvider cxxPlatformsProviderFactory =
        toolchainProvider.getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
    CxxPlatform defaultCxxPlatform = cxxPlatformsProviderFactory.getDefaultCxxPlatform();
    FlavorDomain<CxxPlatform> cxxPlatforms = cxxPlatformsProviderFactory.getCxxPlatforms();

    GoPlatformFactory platformFactory =
        GoPlatformFactory.of(
            context.getBuckConfig(), context.getProcessExecutor(), context.getExecutableFinder());

    FlavorDomain<GoPlatform> goPlatforms =
        FlavorDomain.from(
            "Go Platforms",
            RichStream.from(cxxPlatforms.getValues())
                .map(
                    cxxPlatform ->
                        platformFactory.getPlatform(
                            // We special case the "default" C/C++ platform to just use the "Go"
                            // section.
                            cxxPlatform.getFlavor().equals(DefaultCxxPlatforms.FLAVOR)
                                ? GoBuckConfig.SECTION
                                : GoBuckConfig.SECTION + "#" + cxxPlatform.getFlavor(),
                            cxxPlatform))
                .toImmutableList());
    GoBuckConfig goBuckConfig = new GoBuckConfig(context.getBuckConfig());
    GoPlatform defaultGoPlatform =
        goPlatforms.getValue(
            goBuckConfig
                .getDefaultPlatform()
                .<Flavor>map(InternalFlavor::of)
                .orElse(defaultCxxPlatform.getFlavor()));

    // TODO(agallagher): For backwards compatibility with older style Go platform naming
    // conventions, we also install the default platform under the `<os>_<arch>` flavor.
    if (platformFactory.getDefaultOs().equals(defaultGoPlatform.getGoOs())
        && platformFactory.getDefaultArch().equals(defaultGoPlatform.getGoArch())) {
      goPlatforms =
          FlavorDomain.from(
              goPlatforms.getName(),
              ImmutableList.<GoPlatform>builder()
                  .addAll(goPlatforms.getValues())
                  .add(
                      defaultGoPlatform.withFlavor(
                          InternalFlavor.of(
                              String.format(
                                  "%s_%s",
                                  platformFactory.getDefaultOs(),
                                  platformFactory.getDefaultArch()))))
                  .build());
    }

    return Optional.of(GoToolchain.of(goPlatforms, defaultGoPlatform));
  }
}
