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

package com.facebook.buck.lua;

import com.facebook.buck.cxx.AbstractCxxLibrary;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.ToolProvider;
import java.util.Optional;

public interface LuaConfig {

  ToolProvider getLua();

  Optional<BuildTarget> getNativeStarterLibrary();

  Optional<BuildTarget> getLuaCxxLibraryTarget();

  AbstractCxxLibrary getLuaCxxLibrary(BuildRuleResolver resolver);

  Optional<LuaBinaryDescription.StarterType> getStarterType();

  String getExtension();

  /** @return the {@link PackageStyle} to use for Lua executables. */
  PackageStyle getPackageStyle();

  /** @return the {@link ToolProvider} which packages standalone Lua executables. */
  ToolProvider getPackager();

  /** @return whether to cache Lua executable packages. */
  boolean shouldCacheBinaries();

  /** @return the native link strategy to use for binaries. */
  NativeLinkStrategy getNativeLinkStrategy();

  enum PackageStyle {

    /** Build Lua executables into standalone, relocatable packages. */
    STANDALONE,

    /** Build Lua executables that can only be run from their build location. */
    INPLACE,
  }
}
