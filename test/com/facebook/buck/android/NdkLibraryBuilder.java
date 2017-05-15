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
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class NdkLibraryBuilder
    extends AbstractNodeBuilder<
        NdkLibraryDescriptionArg.Builder, NdkLibraryDescriptionArg, NdkLibraryDescription,
        NdkLibrary> {

  private static final NdkCxxPlatform DEFAULT_NDK_PLATFORM =
      NdkCxxPlatform.builder()
          .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
          .setCxxRuntime(NdkCxxRuntime.GNUSTL)
          .setCxxSharedRuntimePath(Paths.get("runtime"))
          .setObjdump(new CommandTool.Builder().addArg("objdump").build())
          .build();

  private static final ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> NDK_PLATFORMS =
      ImmutableMap.<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform>builder()
          .put(NdkCxxPlatforms.TargetCpuType.ARM, DEFAULT_NDK_PLATFORM)
          .put(NdkCxxPlatforms.TargetCpuType.ARMV7, DEFAULT_NDK_PLATFORM)
          .put(NdkCxxPlatforms.TargetCpuType.X86, DEFAULT_NDK_PLATFORM)
          .build();

  public NdkLibraryBuilder(BuildTarget target) {
    this(target, new FakeProjectFilesystem());
  }

  public NdkLibraryBuilder(BuildTarget target, ProjectFilesystem filesystem) {
    super(
        new NdkLibraryDescription(Optional.empty(), NDK_PLATFORMS) {
          @Override
          protected ImmutableSortedSet<SourcePath> findSources(
              ProjectFilesystem filesystem, Path buildRulePath) {
            return ImmutableSortedSet.of(
                new PathSourcePath(filesystem, buildRulePath.resolve("Android.mk")));
          }
        },
        target,
        filesystem);
  }

  public NdkLibraryBuilder addDep(BuildTarget target) {
    getArgForPopulating().addDeps(target);
    return this;
  }

  public NdkLibraryBuilder setFlags(Iterable<String> flags) {
    getArgForPopulating().setFlags(ImmutableList.copyOf(flags));
    return this;
  }

  public NdkLibraryBuilder setIsAsset(boolean isAsset) {
    getArgForPopulating().setIsAsset(isAsset);
    return this;
  }
}
