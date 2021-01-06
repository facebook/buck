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

import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/**
 * An implementation of {@link NativeLinkableGroup} where the corresponding {@link NativeLinkable}
 * just refer back to this.
 */
public interface LegacyNativeLinkableGroup extends NativeLinkableGroup {

  /**
   * @return All native linkable dependencies that might be required by this linkable on any
   *     platform.
   */
  Iterable<? extends NativeLinkableGroup> getNativeLinkableDeps(BuildRuleResolver ruleResolver);

  /**
   * @return All native linkable exported dependencies that might be required by this linkable on
   *     any platform.
   */
  Iterable<? extends NativeLinkableGroup> getNativeLinkableExportedDeps(
      BuildRuleResolver ruleResolver);

  /**
   * @return All native linkable dependencies that are required by this linkable on a specific
   *     platform.
   */
  @SuppressWarnings("unused")
  @Deprecated
  default Iterable<? extends NativeLinkableGroup> getNativeLinkableDepsForPlatform(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return getNativeLinkableDeps(ruleResolver);
  }

  /**
   * @return All native linkable exported dependencies that are required by this linkable on a
   *     specific platform.
   */
  @SuppressWarnings("unused")
  @Deprecated
  default Iterable<? extends NativeLinkableGroup> getNativeLinkableExportedDepsForPlatform(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getNativeLinkableExportedDeps(graphBuilder);
  }

  /**
   * Return input that *dependents* should put on their link line when linking against this
   * linkable.
   */
  @Deprecated
  NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ActionGraphBuilder graphBuilder,
      TargetConfiguration targetConfiguration);

  /**
   * Return input that *dependents* should put on their link line when linking against this
   * linkable.
   */
  @Deprecated
  default NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      ActionGraphBuilder graphBuilder,
      TargetConfiguration targetConfiguration) {
    return getNativeLinkableInput(cxxPlatform, type, false, graphBuilder, targetConfiguration);
  }

  /**
   * Optionally returns a {@link NativeLinkTargetGroup}. Most implementations of NativeLinkableGroup
   * are themselves instances of NativeLinkTargetGroup.
   */
  @Deprecated
  @SuppressWarnings("unused")
  default Optional<NativeLinkTargetGroup> getNativeLinkTarget(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    if (this instanceof NativeLinkTargetGroup) {
      return Optional.of((NativeLinkTargetGroup) this);
    }
    return Optional.empty();
  }

  /** @return whether this {@link NativeLinkableGroup} supports omnibus linking. */
  @SuppressWarnings("unused")
  @Deprecated
  default boolean supportsOmnibusLinking(CxxPlatform cxxPlatform) {
    return true;
  }

  // TODO(agallagher): We should also use `NativeLinkable.supportsOmnibusLinking()` to
  // determine if we can include the library, but this will need likely need to be updated for
  // a multi-pass walk first.
  /** Whether the nativeLinkable should be linked shared or otherwise for haskell omnibus. */
  @Deprecated
  @SuppressWarnings("unused")
  default boolean isPrebuiltSOForHaskellOmnibus(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return false;
  }

  /** @return whether this {@link NativeLinkableGroup} supports omnibus linking for haskell. */
  // TODO(agallagher): This should probably be *any* `NativeLinkable` that supports omnibus
  // linking.
  @Deprecated
  @SuppressWarnings("unused")
  default boolean supportsOmnibusLinkingForHaskell(CxxPlatform cxxPlatform) {
    return false;
  }

  /** @return exported linker flags. These should be added to link lines of dependents. */
  @Deprecated
  @SuppressWarnings("unused")
  default Iterable<? extends Arg> getExportedLinkerFlags(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return ImmutableList.of();
  }

  /**
   * @return exported post-linker flags. This should be added to lines of dependents after other
   *     linker flags.
   */
  @Deprecated
  @SuppressWarnings("unused")
  default Iterable<? extends Arg> getExportedPostLinkerFlags(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return ImmutableList.of();
  }

  @Deprecated
  default boolean forceLinkWholeForHaskellOmnibus() {
    throw new IllegalStateException(
        String.format("Unexpected rule type in omnibus link %s(%s)", getClass(), getBuildTarget()));
  }

  /**
   * Indicates whether this linkable should be included in both the test binary and the host binary
   * for Apple tests.
   */
  @Deprecated
  default boolean shouldBeLinkedInAppleTestAndHost() {
    return false;
  }

  /** Return a string representing the type of this rule. */
  @Deprecated
  default String getRuleType() {
    if (this instanceof BuildRule) {
      return ((BuildRule) this).getType();
    }
    throw new UnsupportedOperationException();
  }

  @Deprecated
  Linkage getPreferredLinkage(CxxPlatform cxxPlatform);

  /**
   * @return a map of shared library SONAME to shared library path for the given {@link
   *     CxxPlatform}.
   */
  @Deprecated
  ImmutableMap<String, SourcePath> getSharedLibraries(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);

  @Override
  default NativeLinkable getNativeLinkable(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getNativeLinkableCompatibilityCache().get(cxxPlatform);
  }

  PlatformLockedNativeLinkableGroup.Cache getNativeLinkableCompatibilityCache();

  static PlatformLockedNativeLinkableGroup.Cache getNativeLinkableCache(
      LegacyNativeLinkableGroup group) {
    return new PlatformLockedNativeLinkableGroup.Cache(group);
  }
}
