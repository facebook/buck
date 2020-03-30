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

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInfo;
import com.facebook.buck.cxx.toolchain.nativelink.PlatformMappedCache;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.util.Optional;

public abstract class PrebuiltCxxLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements AbstractCxxLibraryGroup {
  private final PlatformMappedCache<NativeLinkable> linkableCache = new PlatformMappedCache<>();

  PrebuiltCxxLibrary(
      BuildTarget buildTarget, ProjectFilesystem projectFilesystem, BuildRuleParams params) {
    super(buildTarget, projectFilesystem, params);
  }

  @Override
  public NativeLinkable getNativeLinkable(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return linkableCache.get(cxxPlatform, () -> createNativeLinkable(cxxPlatform, graphBuilder));
  }

  protected abstract NativeLinkableInfo createNativeLinkable(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);

  abstract Optional<SourcePath> getStaticLibrary(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);

  abstract Optional<SourcePath> getStaticPicLibrary(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);
}
