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
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformCompiler;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntimeType;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper;
import com.facebook.buck.android.toolchain.ndk.impl.NdkCxxPlatforms;
import com.facebook.buck.config.BuckConfig;
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
    NdkCompilerType compilerType = NdkCxxPlatforms.DEFAULT_COMPILER_TYPE;
    String ndkVersion = androidNdk.get().getNdkVersion();
    String gccVersion = NdkCxxPlatforms.getDefaultGccVersionForNdk(ndkVersion);
    String clangVersion = NdkCxxPlatforms.getDefaultClangVersionForNdk(ndkVersion);
    String compilerVersion = compilerType == NdkCompilerType.GCC ? gccVersion : clangVersion;
    NdkCxxPlatformCompiler compiler =
        NdkCxxPlatformCompiler.builder()
            .setType(compilerType)
            .setVersion(compilerVersion)
            .setGccVersion(gccVersion)
            .build();
    ImmutableMap<TargetCpuType, NdkCxxPlatform> ndkPlatforms =
        NdkCxxPlatforms.getPlatforms(
            cxxBuckConfig,
            new AndroidBuckConfig(buckConfig, Platform.detect()),
            filesystem,
            ndkDir,
            compiler,
            NdkCxxPlatforms.DEFAULT_CXX_RUNTIME,
            NdkCxxRuntimeType.DYNAMIC,
            NdkCxxPlatforms.DEFAULT_TARGET_APP_PLATFORM,
            NdkCxxPlatforms.DEFAULT_CPU_ABIS,
            Platform.detect());
    // Just return one of the NDK platforms, which should be enough to test shared library interface
    // functionality.
    return Optional.of(ndkPlatforms.values().iterator().next().getCxxPlatform());
  }
}
