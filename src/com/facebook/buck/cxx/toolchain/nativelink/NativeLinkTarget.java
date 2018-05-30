/*
 * Copyright 2016-present Facebook, Inc.
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
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import java.nio.file.Path;
import java.util.Optional;

public interface NativeLinkTarget {

  BuildTarget getBuildTarget();

  NativeLinkTargetMode getNativeLinkTargetMode(CxxPlatform cxxPlatform);

  /** @return the {@link NativeLinkable} dependencies used to link this target. */
  Iterable<? extends NativeLinkable> getNativeLinkTargetDeps(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);

  /** @return the {@link NativeLinkableInput} used to link this target. */
  NativeLinkableInput getNativeLinkTargetInput(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder);

  /** @return an explicit {@link Path} to use for the output location. */
  Optional<Path> getNativeLinkTargetOutputPath(CxxPlatform cxxPlatform);
}
