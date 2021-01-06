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

import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import java.nio.file.Path;
import java.util.Optional;

/**
 * An implementation of {@link NativeLinkTargetGroup} where the corresponding {@link
 * NativeLinkTarget} just refer back to this.
 */
public interface LegacyNativeLinkTargetGroup extends NativeLinkTargetGroup {

  /**
   * @deprecated Convert to a {@link NativeLinkTarget} and use {@link
   *     NativeLinkTarget#getNativeLinkTargetMode()} instead.
   */
  @Deprecated
  NativeLinkTargetMode getNativeLinkTargetMode(CxxPlatform cxxPlatform);

  /**
   * @return the {@link NativeLinkableGroup} dependencies used to link this target.
   * @deprecated Convert to a {@link NativeLinkTarget} and use {@link
   *     NativeLinkTarget#getNativeLinkTargetDeps(ActionGraphBuilder)} instead.
   */
  @Deprecated
  Iterable<? extends NativeLinkableGroup> getNativeLinkTargetDeps(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);

  /**
   * @return an explicit {@link Path} to use for the output location.
   * @deprecated Convert to a {@link NativeLinkTarget} and use {@link
   *     NativeLinkTarget#getNativeLinkTargetMode()} instead.
   */
  @Deprecated
  Optional<Path> getNativeLinkTargetOutputPath();

  @Override
  default NativeLinkTarget getTargetForPlatform(
      CxxPlatform cxxPlatform, boolean includePrivateLinkerFlags) {
    return new PlatformLockedNativeLinkTargetGroup(this, cxxPlatform, includePrivateLinkerFlags);
  }

  /** @return the {@link NativeLinkableInput} used to link this target. */
  NativeLinkableInput getNativeLinkTargetInput(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      SourcePathResolverAdapter pathResolver,
      boolean includePrivateLinkerFlags);
}
