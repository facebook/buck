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

package com.facebook.buck.features.rust;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider.Type;
import com.facebook.buck.cxx.toolchain.linker.impl.DefaultLinkerProvider;
import com.google.common.collect.ImmutableList;

public class RustTestUtils {

  public static final RustPlatform DEFAULT_PLATFORM =
      ImmutableRustPlatform.builder()
          .setRustCompiler(new ConstantToolProvider(new CommandTool.Builder().build()))
          .setLinker(new ConstantToolProvider(new CommandTool.Builder().build()))
          .setLinkerProvider(
              new DefaultLinkerProvider(
                  Type.GNU, new ConstantToolProvider(new CommandTool.Builder().build()), true))
          .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
          .build();

  public static final FlavorDomain<UnresolvedRustPlatform> DEFAULT_PLATFORMS =
      FlavorDomain.of("Rust Platforms");

  public static final RustToolchain DEFAULT_TOOLCHAIN =
      RustToolchain.of(
          new UnresolvedRustPlatform() {
            @Override
            public RustPlatform resolve(
                BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
              return DEFAULT_PLATFORM;
            }

            @Override
            public Flavor getFlavor() {
              return DEFAULT_PLATFORM.getFlavor();
            }

            @Override
            public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
              return ImmutableList.of();
            }
          },
          DEFAULT_PLATFORMS);
}
