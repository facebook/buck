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

package com.facebook.buck.cxx;

import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.google.common.base.Optional;

/**
 * Describes a target which can be linked into a shared library.
 */
public interface SharedNativeLinkTarget extends HasBuildTarget {

  /**
   * @return the {@link NativeLinkable} dependencies used to link this target.
   */
  Iterable<? extends NativeLinkable> getSharedNativeLinkTargetDeps(CxxPlatform cxxPlatform);

  /**
   * @return the shared library name used when linking this target.
   */
  Optional<String> getSharedNativeLinkTargetLibraryName(CxxPlatform cxxPlatform);

  /**
   * @return the {@link NativeLinkableInput} used to link this target.
   */
  NativeLinkableInput getSharedNativeLinkTargetInput(CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException;

}
