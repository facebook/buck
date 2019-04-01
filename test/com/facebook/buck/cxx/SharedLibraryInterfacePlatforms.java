/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.android.toolchain.ndk.NdkCompilerType;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformCompiler;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntimeType;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.android.toolchain.ndk.UnresolvedNdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper;
import com.facebook.buck.android.toolchain.ndk.impl.NdkCxxPlatforms;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;

/** Creates a test C++ platform using the NDK for testing shared library interfaces. */
public class SharedLibraryInterfacePlatforms {

  public static Optional<CxxPlatform> getTestPlatform(
      ProjectFilesystem filesystem, BuckConfig buckConfig, CxxBuckConfig cxxBuckConfig) {
    Optional<AndroidNdk> androidNdk = AndroidNdkHelper.detectAndroidNdk(filesystem);
    if (!androidNdk.isPresent()) {
      return Optional.empty();
    }

    Path ndkDir = androidNdk.get().getNdkRootPath();
    String ndkVersion = androidNdk.get().getNdkVersion();
    NdkCompilerType compilerType = NdkCxxPlatforms.getDefaultCompilerTypeForNdk(ndkVersion);
    String gccVersion = NdkCxxPlatforms.getDefaultGccVersionForNdk(ndkVersion);
    String clangVersion = NdkCxxPlatforms.getDefaultClangVersionForNdk(ndkVersion);
    String compilerVersion = compilerType == NdkCompilerType.GCC ? gccVersion : clangVersion;
    NdkCxxPlatformCompiler compiler =
        NdkCxxPlatformCompiler.builder()
            .setType(compilerType)
            .setVersion(compilerVersion)
            .setGccVersion(gccVersion)
            .build();
    ImmutableMap<TargetCpuType, UnresolvedNdkCxxPlatform> ndkPlatforms =
        NdkCxxPlatforms.getPlatforms(
            cxxBuckConfig,
            new AndroidBuckConfig(buckConfig, Platform.detect()),
            filesystem,
            ndkDir,
            EmptyTargetConfiguration.INSTANCE,
            compiler,
            NdkCxxPlatforms.getDefaultCxxRuntimeForNdk(ndkVersion),
            NdkCxxRuntimeType.DYNAMIC,
            AndroidNdkHelper.getDefaultCpuAbis(ndkVersion),
            Platform.detect());
    // Just return one of the NDK platforms, which should be enough to test shared library interface
    // functionality.
    return Optional.of(
        ndkPlatforms
            .values()
            .iterator()
            .next()
            .getCxxPlatform()
            .resolve(new TestActionGraphBuilder()));
  }
}
