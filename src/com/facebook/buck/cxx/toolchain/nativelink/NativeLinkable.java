/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx.toolchain.nativelink;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/**
 * Interface for {@link com.facebook.buck.rules.BuildRule} objects (e.g. C++ libraries) which can
 * contribute to the top-level link of a native binary (e.g. C++ binary).
 */
public interface NativeLinkable {

  BuildTarget getBuildTarget();

  /**
   * @return All native linkable dependencies that are required by this linkable on a specific
   *     platform.
   */
  @SuppressWarnings("unused")
  default Iterable<? extends NativeLinkable> getNativeLinkableDepsForPlatform(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return getNativeLinkableDeps(ruleResolver);
  }

  /**
   * @return All native linkable exported dependencies that are required by this linkable on a
   *     specific platform.
   */
  @SuppressWarnings("unused")
  default Iterable<? extends NativeLinkable> getNativeLinkableExportedDepsForPlatform(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return getNativeLinkableExportedDeps(ruleResolver);
  }

  /**
   * @return All native linkable dependencies that might be required by this linkable on any
   *     platform.
   */
  Iterable<? extends NativeLinkable> getNativeLinkableDeps(BuildRuleResolver ruleResolver);

  /**
   * @return All native linkable exported dependencies that might be required by this linkable on
   *     any platform.
   */
  Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(BuildRuleResolver ruleResolver);

  enum LanguageExtensions {
    HS_PROFILE
  }

  /**
   * Return input that *dependents* should put on their link line when linking against this
   * linkable.
   */
  NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ImmutableSet<LanguageExtensions> languageExtensions,
      BuildRuleResolver ruleResolver);

  /**
   * Return input that *dependents* should put on their link line when linking against this
   * linkable.
   */
  default NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform, Linker.LinkableDepType type, BuildRuleResolver ruleResolver) {
    return getNativeLinkableInput(cxxPlatform, type, false, ImmutableSet.of(), ruleResolver);
  }

  Linkage getPreferredLinkage(CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver);

  /**
   * @return a map of shared library SONAME to shared library path for the given {@link
   *     CxxPlatform}.
   */
  ImmutableMap<String, SourcePath> getSharedLibraries(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver);

  /** @return whether this {@link NativeLinkable} supports omnibus linking. */
  @SuppressWarnings("unused")
  default boolean supportsOmnibusLinking(CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return true;
  }

  enum Linkage {
    ANY,
    STATIC,
    SHARED,
  }
}
