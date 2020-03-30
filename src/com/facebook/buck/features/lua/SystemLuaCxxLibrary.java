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

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.cxx.AbstractCxxLibraryGroup;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInfo;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/** Represents the system lua c++ library. */
public class SystemLuaCxxLibrary implements AbstractCxxLibraryGroup, NativeLinkableGroup {

  private final BuildTarget target;
  // This has the same NativeLinkableInfo for all platforms.
  private final NativeLinkableInfo linkableInfo;

  public SystemLuaCxxLibrary(BuildTarget target) {
    this.target = target;
    NativeLinkableInput linkableInput =
        NativeLinkableInput.builder().addAllArgs(StringArg.from("-llua")).build();
    this.linkableInfo =
        new NativeLinkableInfo(
            getBuildTarget(),
            "system_lua_cxx_library",
            ImmutableList.of(),
            ImmutableList.of(),
            Linkage.SHARED,
            NativeLinkableInfo.fixedDelegate(linkableInput, ImmutableMap.of()),
            NativeLinkableInfo.defaults().setSupportsOmnibusLinking(false));
  }

  @Override
  public BuildTarget getBuildTarget() {
    return target;
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables(BuildRuleResolver ruleResolver) {
    return ImmutableList.of();
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {}

  @Override
  public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return ImmutableList.of();
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return CxxPreprocessorInput.of();
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return ImmutableMap.of();
  }

  @Override
  public NativeLinkable getNativeLinkable(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return linkableInfo;
  }
}
