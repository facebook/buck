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
package com.facebook.buck.cxx;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

class OmnibusNode implements NativeLinkable {

  private final BuildTarget target;
  private final Iterable<? extends NativeLinkable> deps;
  private final Iterable<? extends NativeLinkable> exportedDeps;
  private final Linkage linkage;

  public OmnibusNode(
      String target,
      Iterable<? extends NativeLinkable> deps,
      Iterable<? extends NativeLinkable> exportedDeps,
      NativeLinkable.Linkage linkage) {
    this.target = BuildTargetFactory.newInstance(target);
    this.deps = deps;
    this.exportedDeps = exportedDeps;
    this.linkage = linkage;
  }

  public OmnibusNode(
      String target,
      Iterable<? extends NativeLinkable> deps,
      Iterable<? extends NativeLinkable> exportedDeps) {
    this(target, deps, exportedDeps, Linkage.ANY);
  }

  public OmnibusNode(String target, Iterable<? extends NativeLinkable> deps) {
    this(target, deps, ImmutableList.of());
  }

  public OmnibusNode(String target) {
    this(target, ImmutableList.of(), ImmutableList.of());
  }

  @Override
  public BuildTarget getBuildTarget() {
    return target;
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableDeps(BuildRuleResolver ruleResolver) {
    return deps;
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(
      BuildRuleResolver ruleResolver) {
    return exportedDeps;
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ImmutableSet<NativeLinkable.LanguageExtensions> languageExtensions,
      BuildRuleResolver ruleResolver) {
    return NativeLinkableInput.builder().addArgs(StringArg.of(getBuildTarget().toString())).build();
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return linkage;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return ImmutableMap.of(
        getBuildTarget().toString(), FakeSourcePath.of(getBuildTarget().toString()));
  }
}
