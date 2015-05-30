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
package com.facebook.buck.android;

import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;

public class NdkLibraryBuilder extends AbstractNodeBuilder<NdkLibraryDescription.Arg> {

  private static final NdkCxxPlatform DEFAULT_NDK_PLATFORM =
      NdkCxxPlatform.builder()
          .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
          .setObjcopy(Paths.get("tool"))
          .setCxxRuntime(NdkCxxPlatforms.CxxRuntime.GNUSTL)
          .setCxxSharedRuntimePath(Paths.get("runtime"))
          .build();

  private static final ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> NDK_PLATFORMS =
      ImmutableMap.<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform>builder()
          .put(NdkCxxPlatforms.TargetCpuType.ARM, DEFAULT_NDK_PLATFORM)
          .put(NdkCxxPlatforms.TargetCpuType.ARMV7, DEFAULT_NDK_PLATFORM)
          .put(NdkCxxPlatforms.TargetCpuType.X86, DEFAULT_NDK_PLATFORM)
          .build();

  public NdkLibraryBuilder(BuildTarget target) {
    super(
        new NdkLibraryDescription(Optional.<String>absent(), NDK_PLATFORMS) {
          @Override
          protected ImmutableSortedSet<SourcePath> findSources(
              ProjectFilesystem filesystem,
              Path buildRulePath) {
            return ImmutableSortedSet.<SourcePath>of(
                new PathSourcePath(filesystem, buildRulePath.resolve("Android.mk")));
          }
        },
        target);
  }

  public NdkLibraryBuilder addDep(BuildTarget target) {
    arg.deps = amend(arg.deps, target);
    return this;
  }

  public NdkLibraryBuilder setFlags(Iterable<String> flags) {
    arg.flags = Optional.of(ImmutableList.copyOf(flags));
    return this;
  }

  public NdkLibraryBuilder setIsAsset(boolean isAsset) {
    arg.isAsset = Optional.of(isAsset);
    return this;
  }

}
