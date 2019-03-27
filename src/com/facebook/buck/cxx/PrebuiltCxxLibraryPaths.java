/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/** Class abstracting extracting paths to prebuilt C/C++ library components via the user API. */
interface PrebuiltCxxLibraryPaths {

  Optional<SourcePath> getSharedLibrary(
      ProjectFilesystem filesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions);

  Optional<SourcePath> getStaticLibrary(
      ProjectFilesystem filesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions);

  Optional<SourcePath> getStaticPicLibrary(
      ProjectFilesystem filesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions);

  ImmutableList<SourcePath> getIncludeDirs(
      ProjectFilesystem filesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions);

  @SuppressWarnings("unused")
  default Optional<NativeLinkable.Linkage> getLinkage(
      ProjectFilesystem filesystem, CellPathResolver cellRoots, CxxPlatform cxxPlatform) {
    return Optional.empty();
  }

  @SuppressWarnings("unused")
  default void findParseTimeDeps(
      CellPathResolver cellRoots,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {}
}
