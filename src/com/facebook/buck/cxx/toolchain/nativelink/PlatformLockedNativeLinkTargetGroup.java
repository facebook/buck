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
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Implementation of {@link NativeLinkTarget} in terms of just a fixed {@link CxxPlatform} and
 * {@link NativeLinkTargetGroup}.
 */
public class PlatformLockedNativeLinkTargetGroup implements NativeLinkTarget {
  private final LegacyNativeLinkTargetGroup underlyingGroup;
  private final CxxPlatform cxxPlatform;
  boolean includePrivateLinkerFlags;

  public PlatformLockedNativeLinkTargetGroup(
      LegacyNativeLinkTargetGroup underlyingGroup,
      CxxPlatform cxxPlatform,
      boolean includePrivateLinkerFlags) {
    this.underlyingGroup = underlyingGroup;
    this.cxxPlatform = cxxPlatform;
    this.includePrivateLinkerFlags = includePrivateLinkerFlags;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return underlyingGroup.getBuildTarget();
  }

  @Override
  public NativeLinkTargetMode getNativeLinkTargetMode() {
    return underlyingGroup.getNativeLinkTargetMode(cxxPlatform);
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkTargetDeps(
      ActionGraphBuilder graphBuilder) {
    return Iterables.transform(
        underlyingGroup.getNativeLinkTargetDeps(cxxPlatform, graphBuilder),
        g -> g.getNativeLinkable(cxxPlatform, graphBuilder));
  }

  @Override
  public NativeLinkableInput getNativeLinkTargetInput(
      ActionGraphBuilder graphBuilder, SourcePathResolverAdapter pathResolver) {
    return underlyingGroup.getNativeLinkTargetInput(
        cxxPlatform, graphBuilder, pathResolver, includePrivateLinkerFlags);
  }

  @Override
  public Optional<Path> getNativeLinkTargetOutputPath() {
    return underlyingGroup.getNativeLinkTargetOutputPath();
  }
}
