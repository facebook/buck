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
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.cxx.toolchain.CxxPlatform;

/**
 * Interface for {@link BuildRule} objects (e.g. C++ libraries) which can contribute to the
 * top-level link of a native binary (e.g. C++ binary). This represents the linkable object for all
 * platforms.
 */
public interface NativeLinkableGroup {
  BuildTarget getBuildTarget();

  NativeLinkable getNativeLinkable(CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);

  enum Linkage {
    ANY,
    STATIC,
    SHARED,
  }
}
