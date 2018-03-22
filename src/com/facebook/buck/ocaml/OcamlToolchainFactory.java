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

package com.facebook.buck.ocaml;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

public class OcamlToolchainFactory implements ToolchainFactory<OcamlToolchain> {

  @Override
  public Optional<OcamlToolchain> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
    CxxPlatform cxxPlatform =
        toolchainProvider
            .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
            .getDefaultCxxPlatform();
    return Optional.of(
        OcamlToolchain.of(
            OcamlPlatform.builder()
                .setCCompiler(cxxPlatform.getCc())
                .setCxxCompiler(cxxPlatform.getCxx())
                .setCPreprocessor(cxxPlatform.getCpp())
                .setCFlags(
                    ImmutableList.<String>builder()
                        .addAll(cxxPlatform.getCppflags())
                        .addAll(cxxPlatform.getCflags())
                        .addAll(cxxPlatform.getAsflags())
                        .build())
                .setLdFlags(cxxPlatform.getLdflags())
                .build()));
  }
}
