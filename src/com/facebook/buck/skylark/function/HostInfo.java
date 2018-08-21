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

package com.facebook.buck.skylark.function;

import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.SkylarkInfo;
import com.google.devtools.build.lib.packages.StructProvider;
import java.util.function.Supplier;

/** A factory for {@code host_info} built-in function available */
public class HostInfo {

  /** Prevent callers from instantiating this class. */
  private HostInfo() {}

  @VisibleForTesting
  static SkylarkInfo createHostInfoStruct(
      Supplier<Platform> platformSupplier, Supplier<Architecture> architectureSupplier) {
    Platform hostPlatform = platformSupplier.get();
    Architecture hostArchitecture = architectureSupplier.get();
    ImmutableMap<String, Object> os =
        ImmutableMap.of(
            "is_linux", hostPlatform == Platform.LINUX,
            "is_macos", hostPlatform == Platform.MACOS,
            "is_windows", hostPlatform == Platform.WINDOWS,
            "is_freebsd", hostPlatform == Platform.FREEBSD,
            "is_unknown", hostPlatform == Platform.UNKNOWN);
    ImmutableMap<String, Object> arch =
        ImmutableMap.<String, Object>builder()
            .put("is_aarch64", hostArchitecture == Architecture.AARCH64)
            .put("is_arm", hostArchitecture == Architecture.ARM)
            .put("is_armeb", hostArchitecture == Architecture.ARMEB)
            .put("is_i386", hostArchitecture == Architecture.I386)
            .put("is_mips", hostArchitecture == Architecture.MIPS)
            .put("is_mips64", hostArchitecture == Architecture.MIPS64)
            .put("is_mipsel", hostArchitecture == Architecture.MIPSEL)
            .put("is_mipsel64", hostArchitecture == Architecture.MIPSEL64)
            .put("is_powerpc", hostArchitecture == Architecture.POWERPC)
            .put("is_ppc64", hostArchitecture == Architecture.PPC64)
            .put("is_unknown", hostArchitecture == Architecture.UNKNOWN)
            .put("is_x86_64", hostArchitecture == Architecture.X86_64)
            .build();
    return StructProvider.STRUCT.create(
        ImmutableMap.of(
            "os",
            StructProvider.STRUCT.create(os, "no such property on os struct: '%s'"),
            "arch",
            StructProvider.STRUCT.create(arch, "no such property on arch struct: '%s'")),
        "no such property on host_info struct: '%s'");
  }

  public static final Info HOST_INFO =
      HostInfo.createHostInfoStruct(Platform::detect, Architecture::detect);
}
