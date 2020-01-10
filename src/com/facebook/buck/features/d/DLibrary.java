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

package com.facebook.buck.features.d;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInfo;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.PlatformMappedCache;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/** A {@link NativeLinkableGroup} for d libraries. */
public class DLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps implements NativeLinkableGroup {

  private final ActionGraphBuilder graphBuilder;
  private final DIncludes includes;
  private final PlatformMappedCache<NativeLinkableInfo> linkableCache = new PlatformMappedCache<>();

  public DLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      DIncludes includes) {
    super(buildTarget, projectFilesystem, params);
    this.graphBuilder = graphBuilder;
    this.includes = includes;
  }

  @Override
  public NativeLinkableInfo getNativeLinkable(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return linkableCache.get(
        cxxPlatform,
        () -> {
          ImmutableList<NativeLinkable> exportedDeps =
              FluentIterable.from(getDeclaredDeps())
                  .filter(NativeLinkableGroup.class)
                  .transform(g -> g.getNativeLinkable(cxxPlatform, graphBuilder))
                  .toList();
          Archive archive =
              (Archive)
                  DLibrary.this.graphBuilder.requireRule(
                      getBuildTarget()
                          .withAppendedFlavors(
                              cxxPlatform.getFlavor(), CxxDescriptionEnhancer.STATIC_FLAVOR));
          NativeLinkableInput linkableInput =
              NativeLinkableInput.of(
                  ImmutableList.of(archive.toArg()), ImmutableSet.of(), ImmutableSet.of());
          return new NativeLinkableInfo(
              getBuildTarget(),
              getType(),
              ImmutableList.of(),
              exportedDeps,
              Linkage.STATIC,
              NativeLinkableInfo.fixedDelegate(linkableInput, ImmutableMap.of()),
              NativeLinkableInfo.defaults());
        });
  }

  public DIncludes getIncludes() {
    graphBuilder.requireRule(
        getBuildTarget().withAppendedFlavors(DDescriptionUtils.SOURCE_LINK_TREE));
    return includes;
  }
}
