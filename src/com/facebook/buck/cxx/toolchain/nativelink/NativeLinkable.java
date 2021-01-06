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
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/**
 * Interface for objects (e.g. C++ libraries) which can contribute to the top-level link of a native
 * binary (e.g. C++ binary). This represents the linkable object for a specific platform.
 */
public interface NativeLinkable {

  /** @return The {@link BuildTarget} for this linkable. */
  BuildTarget getBuildTarget();

  /** @return All native linkable dependencies that might be required by this linkable. */
  Iterable<? extends NativeLinkable> getNativeLinkableDeps(ActionGraphBuilder graphBuilder);

  /** @return All native linkable exported dependencies that might be required by this linkable. */
  Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(ActionGraphBuilder graphBuilder);

  /**
   * Return input that *dependents* should put on their link line when linking against this
   * linkable.
   */
  NativeLinkableInput getNativeLinkableInput(
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ActionGraphBuilder graphBuilder,
      TargetConfiguration targetConfiguration);

  /**
   * Return input that *dependents* should put on their link line when linking against this
   * linkable.
   */
  default NativeLinkableInput getNativeLinkableInput(
      Linker.LinkableDepType type,
      ActionGraphBuilder graphBuilder,
      TargetConfiguration targetConfiguration) {
    return getNativeLinkableInput(type, false, graphBuilder, targetConfiguration);
  }

  /**
   * Optionally returns a {@link NativeLinkTarget}. Most implementations of NativeLinkable are
   * themselves instances of NativeLinkTarget.
   *
   * @param includePrivateLinkerFlags whether to include rule-specific non-exported linker flags.
   */
  Optional<NativeLinkTarget> getNativeLinkTarget(
      ActionGraphBuilder graphBuilder, boolean includePrivateLinkerFlags);

  /** @return The preferred {@link NativeLinkableGroup.Linkage} for this linkable. */
  NativeLinkableGroup.Linkage getPreferredLinkage();

  /**
   * @return a map of shared library SONAME to shared library path for the given {@link
   *     CxxPlatform}.
   */
  ImmutableMap<String, SourcePath> getSharedLibraries(ActionGraphBuilder graphBuilder);

  /** @return whether this {@link NativeLinkable} supports omnibus linking. */
  default boolean supportsOmnibusLinking() {
    return true;
  }

  /** @return exported linker flags. These should be added to link lines of dependents. */
  @SuppressWarnings("unused")
  default Iterable<? extends Arg> getExportedLinkerFlags(ActionGraphBuilder graphBuilder) {
    return ImmutableList.of();
  }

  /**
   * @return exported post-linker flags. This should be added to lines of dependents after other
   *     linker flags.
   */
  @SuppressWarnings("unused")
  default Iterable<? extends Arg> getExportedPostLinkerFlags(ActionGraphBuilder graphBuilder) {
    return ImmutableList.of();
  }

  default String getRuleType() {
    throw new UnsupportedOperationException();
  }

  boolean shouldBeLinkedInAppleTestAndHost();

  @SuppressWarnings("unused")
  default boolean isPrebuiltSOForHaskellOmnibus(ActionGraphBuilder graphBuilder) {
    return false;
  }

  default boolean supportsOmnibusLinkingForHaskell() {
    return false;
  }

  default boolean forceLinkWholeForHaskellOmnibus() {
    throw new IllegalStateException(
        String.format("Unexpected rule type in omnibus link %s(%s)", getClass(), getBuildTarget()));
  }
}
