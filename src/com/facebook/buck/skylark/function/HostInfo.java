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
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.SkylarkSignatureProcessor;
import java.util.function.Supplier;

/** A factory for {@code host_info} built-in function available */
public class HostInfo {

  private static final String HOST_INFO_FUNCTION_NAME = "host_info";

  /** Prevent callers from instantiating this class. */
  private HostInfo() {}

  static Info createHostInfoStruct(
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

  @SkylarkSignature(
      name = HOST_INFO_FUNCTION_NAME,
      objectType = Object.class,
      returnType = Info.class,
      doc =
          "The host_info() function is used to get processor and OS information about the host machine\n"
              + "The <code>host_info()</code> function is used to get the current OS and processor "
              + "architecture on the host. This will likely change as better cross compilation tooling "
              + "comes to Buck.\n"
              + "    <pre class=\"prettyprint lang-py\">\n"
              + "  struct(\n"
              + "      os=struct(\n"
              + "          is_linux=True|False,\n"
              + "          is_macos=True|False,\n"
              + "          is_windows=True|False,\n"
              + "          is_freebsd=True|False,\n"
              + "          is_unknown=True|False,\n"
              + "      ),\n"
              + "      arch=struct(\n"
              + "          is_aarch64=True|False,\n"
              + "          is_arm=True|False,\n"
              + "          is_armeb=True|False,\n"
              + "          is_i386=True|False,\n"
              + "          is_mips=True|False,\n"
              + "          is_mips64=True|False,\n"
              + "          is_mipsel=True|False,\n"
              + "          is_mipsel64=True|False,\n"
              + "          is_powerpc=True|False,\n"
              + "          is_ppc64=True|False,\n"
              + "          is_unknown=True|False,\n"
              + "          is_x86_64=True|False,\n"
              + "      ),\n"
              + "  )</pre>\n",
      documented = true)
  private static final BuiltinFunction hostInfo =
      new BuiltinFunction(HOST_INFO_FUNCTION_NAME) {
        private final Info currentHostInfo =
            createHostInfoStruct(Platform::detect, Architecture::detect);

        @SuppressWarnings("unused")
        public Info invoke() {
          return currentHostInfo;
        }
      };

  /**
   * Creates a built-in {@code hostInfo} function that can resolve hostInfo patterns under {@code
   * basePath}.
   */
  public static BuiltinFunction create() {
    return hostInfo;
  }

  static {
    SkylarkSignatureProcessor.configureSkylarkFunctions(HostInfo.class);
  }
}
