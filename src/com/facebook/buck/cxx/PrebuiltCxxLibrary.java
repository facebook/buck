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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.LegacyNativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.PlatformLockedNativeLinkableGroup;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;

public abstract class PrebuiltCxxLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements AbstractCxxLibraryGroup {
  private final PlatformLockedNativeLinkableGroup.Cache linkableCache =
      LegacyNativeLinkableGroup.getNativeLinkableCache(this);

  PrebuiltCxxLibrary(
      BuildTarget buildTarget, ProjectFilesystem projectFilesystem, BuildRuleParams params) {
    super(buildTarget, projectFilesystem, params);
  }

  @Override
  public PlatformLockedNativeLinkableGroup.Cache getNativeLinkableCompatibilityCache() {
    return linkableCache;
  }

  @Override
  public abstract ImmutableList<Arg> getExportedLinkerFlags(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);

  @Override
  public abstract ImmutableList<Arg> getExportedPostLinkerFlags(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);

  @Override
  public boolean forceLinkWholeForHaskellOmnibus() {
    // Link prebuilt C/C++ libraries statically.
    return false;
  }

  abstract Optional<SourcePath> getStaticLibrary(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);

  abstract Optional<SourcePath> getStaticPicLibrary(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);

  @Override
  public boolean supportsOmnibusLinkingForHaskell(CxxPlatform cxxPlatform) {
    // TODO(agallagher): This should use supportsOmnibusLinking.
    return true;
  }

  @Override
  public boolean isPrebuiltSOForHaskellOmnibus(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    ImmutableMap<String, SourcePath> sharedLibraries =
        getSharedLibraries(cxxPlatform, graphBuilder);
    for (Map.Entry<String, SourcePath> ent : sharedLibraries.entrySet()) {
      if (!(ent.getValue() instanceof PathSourcePath)) {
        return false;
      }
    }
    return true;
  }
}
