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

package com.facebook.buck.cxx;

import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.SourcePath;
import com.google.common.collect.ImmutableMap;

/**
 * Interface for {@link com.facebook.buck.rules.BuildRule} objects (e.g. C++ libraries) which can
 * contribute to the top-level link of a native binary (e.g. C++ binary).
 */
public interface NativeLinkable extends HasBuildTarget {

  Iterable<? extends NativeLinkable> getNativeLinkableDeps(CxxPlatform cxxPlatform);

  Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(CxxPlatform cxxPlatform);

  /**
   * Return input that *dependents* should put on their link line
   * when linking against this linkable.
   */
  NativeLinkableInput getNativeLinkableInput(CxxPlatform cxxPlatform, Linker.LinkableDepType type)
      throws NoSuchBuildTargetException;

  Linkage getPreferredLinkage(CxxPlatform cxxPlatform);

  /**
   * @return a map of shared library SONAME to shared library path for the given
   *     {@link CxxPlatform}.
   */
  ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException;

  enum Linkage {
    ANY,
    STATIC,
    SHARED,
  }

}
