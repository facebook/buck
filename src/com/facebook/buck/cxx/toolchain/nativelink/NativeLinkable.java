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

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
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
      CxxPlatform cxxPlatform) {
    return getNativeLinkableDeps();
  }

  /**
   * @return All native linkable exported dependencies that are required by this linkable on a
   *     specific platform.
   */
  @SuppressWarnings("unused")
  default Iterable<? extends NativeLinkable> getNativeLinkableExportedDepsForPlatform(
      CxxPlatform cxxPlatform) {
    return getNativeLinkableExportedDeps();
  }

  /**
   * @return All native linkable dependencies that might be required by this linkable on any
   *     platform.
   */
  Iterable<? extends NativeLinkable> getNativeLinkableDeps();

  /**
   * @return All native linkable exported dependencies that might be required by this linkable on
   *     any platform.
   */
  Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps();

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
      ImmutableSet<LanguageExtensions> languageExtensions);

  /**
   * Return input that *dependents* should put on their link line when linking against this
   * linkable.
   */
  default NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform, Linker.LinkableDepType type) {
    return getNativeLinkableInput(cxxPlatform, type, false, ImmutableSet.of());
  }

  Linkage getPreferredLinkage(CxxPlatform cxxPlatform);

  /**
   * @return a map of shared library SONAME to shared library path for the given {@link
   *     CxxPlatform}.
   */
  ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform);

  /** @return whether this {@link NativeLinkable} supports omnibus linking. */
  default boolean supportsOmnibusLinking(@SuppressWarnings("unused") CxxPlatform cxxPlatform) {
    return true;
  }

  /**
   * @param loader the method to load missing element to cache
   * @return a LoadingCache for native linkable
   */
  public static LoadingCache<NativeLinkableCacheKey, NativeLinkableInput>
      getNativeLinkableInputCache(
          com.google.common.base.Function<NativeLinkableCacheKey, NativeLinkableInput> loader) {
    return CacheBuilder.newBuilder().build(CacheLoader.from(loader));
  }

  enum Linkage {
    ANY,
    STATIC,
    SHARED,
  }
}
