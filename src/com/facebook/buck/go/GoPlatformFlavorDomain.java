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

package com.facebook.buck.go;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Optional;

public class GoPlatformFlavorDomain {

  // GOOS/GOARCH values from
  // https://github.com/golang/go/blob/master/src/go/build/syslist.go
  private static final ImmutableMap<String, Platform> GOOS_TO_PLATFORM_LIST =
      ImmutableMap.<String, Platform>builder()
          .put("linux", Platform.LINUX)
          .put("windows", Platform.WINDOWS)
          .put("darwin", Platform.MACOS)
          .put("android", Platform.UNKNOWN)
          .put("dragonfly", Platform.UNKNOWN)
          .put("freebsd", Platform.UNKNOWN)
          .put("nacl", Platform.UNKNOWN)
          .put("netbsd", Platform.UNKNOWN)
          .put("openbsd", Platform.UNKNOWN)
          .put("plan9", Platform.UNKNOWN)
          .put("solaris", Platform.UNKNOWN)
          .build();

  private static final ImmutableMap<String, Architecture> GOARCH_TO_ARCH_LIST =
      ImmutableMap.<String, Architecture>builder()
          .put("386", Architecture.I386)
          .put("amd64", Architecture.X86_64)
          .put("amd64p32", Architecture.UNKNOWN)
          .put("arm", Architecture.ARM)
          .put("armbe", Architecture.ARMEB)
          .put("arm64", Architecture.AARCH64)
          .put("arm64be", Architecture.UNKNOWN)
          .put("ppc64", Architecture.PPC64)
          .put("ppc64le", Architecture.UNKNOWN)
          .put("mips", Architecture.MIPS)
          .put("mipsle", Architecture.MIPSEL)
          .put("mips64", Architecture.MIPS64)
          .put("mips64le", Architecture.MIPSEL64)
          .put("mips64p32", Architecture.UNKNOWN)
          .put("mips64p32le", Architecture.UNKNOWN)
          .put("ppc", Architecture.POWERPC)
          .put("s390", Architecture.UNKNOWN)
          .put("s390x", Architecture.UNKNOWN)
          .put("sparc", Architecture.UNKNOWN)
          .put("sparc64", Architecture.UNKNOWN)
          .build();

  private ImmutableMap<String, Platform> goOsValues;
  private ImmutableMap<String, Architecture> goArchValues;

  public GoPlatformFlavorDomain(
      Map<String, Platform> additionalOsValues, Map<String, Architecture> additionalArchValues) {
    this.goOsValues = MoreMaps.merge(GOOS_TO_PLATFORM_LIST, additionalOsValues);
    this.goArchValues = MoreMaps.merge(GOARCH_TO_ARCH_LIST, additionalArchValues);
  }

  public GoPlatformFlavorDomain() {
    this(ImmutableMap.of(), ImmutableMap.of());
  }

  public Optional<GoPlatform> getValue(Flavor flavor) {
    String[] components = flavor.getName().split("_");
    if (components.length != 2) {
      return Optional.empty();
    }

    Platform os = goOsValues.get(components[0]);
    Architecture arch = goArchValues.get(components[1]);
    if (os != null && arch != null) {
      return Optional.of(
          GoPlatform.builder().setGoOs(components[0]).setGoArch(components[1]).build());
    }
    return Optional.empty();
  }

  public Optional<GoPlatform> getValue(ImmutableSet<Flavor> flavors) {
    for (Flavor f : flavors) {
      Optional<GoPlatform> result = getValue(f);
      if (result.isPresent()) {
        return result;
      }
    }
    return Optional.empty();
  }

  public Optional<GoPlatform> getValue(BuildTarget target) {
    return getValue(target.getFlavors());
  }

  public boolean containsAnyOf(ImmutableSet<Flavor> flavors) {
    return getValue(flavors).isPresent();
  }

  public Optional<GoPlatform> getValue(Platform platform, Architecture architecture) {
    Preconditions.checkArgument(platform != Platform.UNKNOWN);
    Preconditions.checkArgument(architecture != Architecture.UNKNOWN);

    Optional<Map.Entry<String, Platform>> osValue =
        goOsValues.entrySet().stream().filter(input -> input.getValue() == platform).findFirst();
    Optional<Map.Entry<String, Architecture>> archValue =
        goArchValues
            .entrySet()
            .stream()
            .filter(input -> input.getValue() == architecture)
            .findFirst();

    if (!osValue.isPresent() || !archValue.isPresent()) {
      return Optional.empty();
    }

    return Optional.of(
        GoPlatform.builder()
            .setGoOs(osValue.get().getKey())
            .setGoArch(archValue.get().getKey())
            .build());
  }
}
