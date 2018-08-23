/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

public class DLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps implements NativeLinkable {

  private final ActionGraphBuilder graphBuilder;
  private final DIncludes includes;

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
  public Iterable<NativeLinkable> getNativeLinkableDeps(BuildRuleResolver ruleResolver) {
    return ImmutableList.of();
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableExportedDeps(BuildRuleResolver ruleResolver) {
    return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkable.class);
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ActionGraphBuilder graphBuilder) {
    Archive archive =
        (Archive)
            this.graphBuilder.requireRule(
                getBuildTarget()
                    .withAppendedFlavors(
                        cxxPlatform.getFlavor(), CxxDescriptionEnhancer.STATIC_FLAVOR));
    return NativeLinkableInput.of(
        ImmutableList.of(archive.toArg()), ImmutableSet.of(), ImmutableSet.of());
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return Linkage.STATIC;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return ImmutableMap.of();
  }

  public DIncludes getIncludes() {
    graphBuilder.requireRule(
        getBuildTarget().withAppendedFlavors(DDescriptionUtils.SOURCE_LINK_TREE));
    return includes;
  }
}
