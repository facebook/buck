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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.cxx.toolchain.impl.CxxPlatforms;
import com.google.common.collect.ImmutableList;

public class StaticUnresolvedHaskellPlatform implements UnresolvedHaskellPlatform {

  private final HaskellPlatform platform;

  public StaticUnresolvedHaskellPlatform(HaskellPlatform platform) {
    this.platform = platform;
  }

  @Override
  public HaskellPlatform resolve(
      BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
    return platform;
  }

  @Override
  public Flavor getFlavor() {
    return platform.getFlavor();
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    ImmutableList.Builder<BuildTarget> depsBuilder = ImmutableList.builder();

    // Since this description generates haskell link/compile/package rules, make sure the
    // parser includes deps for these tools.
    depsBuilder.addAll(platform.getCompiler().getParseTimeDeps(targetConfiguration));
    depsBuilder.addAll(platform.getLinker().getParseTimeDeps(targetConfiguration));
    depsBuilder.addAll(platform.getPackager().getParseTimeDeps(targetConfiguration));

    // We use the C/C++ linker's Linker object to find out how to pass in the soname, so
    // just add all C/C++ platform parse time deps.
    depsBuilder.addAll(
        CxxPlatforms.getParseTimeDeps(targetConfiguration, platform.getCxxPlatform()));

    return depsBuilder.build();
  }
}
