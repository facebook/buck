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

package com.facebook.buck.features.d;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.google.common.collect.ImmutableMap;

public class DTestBuilder
    extends AbstractNodeBuilder<
        DTestDescriptionArg.Builder, DTestDescriptionArg, DTestDescription, DTest> {

  public DTestBuilder(BuildTarget target, DBuckConfig dBuckConfig, CxxPlatform defaultCxxPlatform) {
    super(
        new DTestDescription(
            createToolchain(defaultCxxPlatform), dBuckConfig, CxxPlatformUtils.DEFAULT_CONFIG),
        target);
    getArgForPopulating().setSrcs(SourceList.EMPTY);
  }

  private static ToolchainProvider createToolchain(CxxPlatform cxxPlatform) {
    return new ToolchainProviderBuilder()
        .withToolchain(
            CxxPlatformsProvider.DEFAULT_NAME,
            CxxPlatformsProvider.of(
                cxxPlatform,
                new FlavorDomain<>(
                    "C/C++ platform", ImmutableMap.of(cxxPlatform.getFlavor(), cxxPlatform))))
        .build();
  }
}
