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

package com.facebook.buck.cxx.toolchain.nativelink;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of {@link NativeLinkable} in terms of just a fixed {@link CxxPlatform} and {@link
 * NativeLinkableGroup}.
 */
public class PlatformLockedNativeLinkableGroup implements NativeLinkable {
  private final LegacyNativeLinkableGroup underlyingGroup;
  private final CxxPlatform cxxPlatform;

  /**
   * A simple cache for a group's {@link NativeLinkable} objects so that we don't recreate them a
   * ton of times.
   */
  public static class Cache {
    private final LegacyNativeLinkableGroup underlyingGroup;
    private final Map<Flavor, NativeLinkable> cache;

    Cache(LegacyNativeLinkableGroup group) {
      this.underlyingGroup = group;
      this.cache = new ConcurrentHashMap<>();
    }

    public NativeLinkable get(CxxPlatform platform) {
      return cache.computeIfAbsent(
          platform.getFlavor(),
          ignored -> new PlatformLockedNativeLinkableGroup(underlyingGroup, platform));
    }
  }

  public PlatformLockedNativeLinkableGroup(
      LegacyNativeLinkableGroup underlyingGroup, CxxPlatform cxxPlatform) {
    this.underlyingGroup = underlyingGroup;
    this.cxxPlatform = cxxPlatform;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return underlyingGroup.getBuildTarget();
  }

  @Override
  public Optional<NativeLinkTarget> getNativeLinkTarget(
      ActionGraphBuilder graphBuilder, boolean includePrivateLinkerFlags) {
    return underlyingGroup
        .getNativeLinkTarget(cxxPlatform, graphBuilder)
        .map(g -> g.getTargetForPlatform(cxxPlatform, includePrivateLinkerFlags));
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableDeps(ActionGraphBuilder graphBuilder) {
    return Iterables.transform(
        underlyingGroup.getNativeLinkableDepsForPlatform(cxxPlatform, graphBuilder),
        g -> g.getNativeLinkable(cxxPlatform, graphBuilder));
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(
      ActionGraphBuilder graphBuilder) {
    return Iterables.transform(
        underlyingGroup.getNativeLinkableExportedDepsForPlatform(cxxPlatform, graphBuilder),
        g -> g.getNativeLinkable(cxxPlatform, graphBuilder));
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ActionGraphBuilder graphBuilder,
      TargetConfiguration targetConfiguration) {
    return underlyingGroup.getNativeLinkableInput(
        cxxPlatform, type, forceLinkWhole, graphBuilder, targetConfiguration);
  }

  @Override
  public NativeLinkableGroup.Linkage getPreferredLinkage() {
    return underlyingGroup.getPreferredLinkage(cxxPlatform);
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(ActionGraphBuilder graphBuilder) {
    return underlyingGroup.getSharedLibraries(cxxPlatform, graphBuilder);
  }

  @Override
  public boolean supportsOmnibusLinking() {
    return underlyingGroup.supportsOmnibusLinking(cxxPlatform);
  }

  @Override
  public Iterable<? extends Arg> getExportedLinkerFlags(ActionGraphBuilder graphBuilder) {
    return underlyingGroup.getExportedLinkerFlags(cxxPlatform, graphBuilder);
  }

  @Override
  public Iterable<? extends Arg> getExportedPostLinkerFlags(ActionGraphBuilder graphBuilder) {
    return underlyingGroup.getExportedPostLinkerFlags(cxxPlatform, graphBuilder);
  }

  @Override
  public String getRuleType() {
    return underlyingGroup.getRuleType();
  }

  @Override
  public boolean shouldBeLinkedInAppleTestAndHost() {
    return underlyingGroup.shouldBeLinkedInAppleTestAndHost();
  }

  @Override
  public boolean isPrebuiltSOForHaskellOmnibus(ActionGraphBuilder graphBuilder) {
    return underlyingGroup.isPrebuiltSOForHaskellOmnibus(cxxPlatform, graphBuilder);
  }

  @Override
  public boolean supportsOmnibusLinkingForHaskell() {
    return underlyingGroup.supportsOmnibusLinkingForHaskell(cxxPlatform);
  }

  @Override
  public boolean forceLinkWholeForHaskellOmnibus() {
    return underlyingGroup.forceLinkWholeForHaskellOmnibus();
  }

  @Override
  public String toString() {
    return String.format("%s[%s]", underlyingGroup, cxxPlatform.getFlavor());
  }
}
