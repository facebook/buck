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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.cxx.toolchain.impl.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.google.common.collect.ImmutableList;

public class StaticUnresolvedLuaPlatform implements UnresolvedLuaPlatform {

  private final LuaPlatform luaPlatform;

  public StaticUnresolvedLuaPlatform(LuaPlatform luaPlatform) {
    this.luaPlatform = luaPlatform;
  }

  @Override
  public LuaPlatform resolve(BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
    return luaPlatform;
  }

  @Override
  public Flavor getFlavor() {
    return luaPlatform.getFlavor();
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {

    ImmutableList.Builder<BuildTarget> deps = ImmutableList.builder();
    if (luaPlatform.getPackageStyle() == LuaPlatform.PackageStyle.STANDALONE) {
      deps.addAll(luaPlatform.getPackager().getParseTimeDeps(targetConfiguration));
    }
    luaPlatform.getLuaCxxLibraryTarget().ifPresent(deps::add);
    luaPlatform.getNativeStarterLibrary().ifPresent(deps::add);
    deps.addAll(CxxPlatforms.getParseTimeDeps(targetConfiguration, luaPlatform.getCxxPlatform()));
    return deps.build();
  }

  @Override
  public NativeLinkStrategy getNativeLinkStrategy() {
    return luaPlatform.getNativeLinkStrategy();
  }
}
