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

package com.facebook.buck.apple.toolchain;

import com.facebook.buck.apple.platform_type.ApplePlatformType;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.immutables.value.Value;

@BuckStyleValueWithBuilder
public abstract class ApplePlatform implements Comparable<ApplePlatform>, AddsToRuleKey {

  public static final ApplePlatform IPHONEOS =
      ImmutableApplePlatform.builder()
          .setName("iphoneos")
          .setSwiftName("ios")
          .setProvisioningProfileName("iOS")
          .setArchitectures(ImmutableList.of("armv7", "arm64"))
          .setMinVersionFlagPrefix("-mios-version-min=")
          // only used for legacy watch apps
          .setStubBinaryPath(Optional.of("Library/Application Support/WatchKit/WK"))
          .build();
  public static final ApplePlatform IPHONESIMULATOR =
      ImmutableApplePlatform.builder()
          .setName("iphonesimulator")
          .setSwiftName("ios")
          .setArchitectures(ImmutableList.of("i386", "x86_64"))
          .setMinVersionFlagPrefix("-mios-simulator-version-min=")
          // only used for legacy watch apps
          .setStubBinaryPath(Optional.of("Library/Application Support/WatchKit/WK"))
          .build();
  public static final ApplePlatform WATCHOS =
      ImmutableApplePlatform.builder()
          .setName("watchos")
          .setProvisioningProfileName("iOS") // watchOS uses iOS provisioning profiles.
          .setArchitectures(ImmutableList.of("armv7k", "arm64_32"))
          .setMinVersionFlagPrefix("-mwatchos-version-min=")
          .setStubBinaryPath(Optional.of("Library/Application Support/WatchKit/WK"))
          .build();
  public static final ApplePlatform WATCHSIMULATOR =
      ImmutableApplePlatform.builder()
          .setName("watchsimulator")
          .setArchitectures(ImmutableList.of("i386", "x86_64"))
          .setMinVersionFlagPrefix("-mwatchos-simulator-version-min=")
          .setStubBinaryPath(Optional.of("Library/Application Support/WatchKit/WK"))
          .build();
  public static final ApplePlatform APPLETVOS =
      ImmutableApplePlatform.builder()
          .setName("appletvos")
          .setProvisioningProfileName("tvOS")
          .setArchitectures(ImmutableList.of("arm64"))
          .setMinVersionFlagPrefix("-mtvos-version-min=")
          .build();
  public static final ApplePlatform APPLETVSIMULATOR =
      ImmutableApplePlatform.builder()
          .setName("appletvsimulator")
          .setArchitectures(ImmutableList.of("x86_64"))
          .setMinVersionFlagPrefix("-mtvos-simulator-version-min=")
          .setSwiftName("tvos")
          .build();
  public static final ApplePlatform MACOSX =
      ImmutableApplePlatform.builder()
          .setName("macosx")
          .setArchitectures(ImmutableList.of("i386", "x86_64"))
          .setAppIncludesFrameworks(true)
          .build();
  public static final ApplePlatform DRIVERKIT =
      ApplePlatform.builder()
          .setName("driverkit")
          .setArchitectures(ImmutableList.of("armv7", "arm64", "i386", "x86_64"))
          .build();
  public static final ImmutableList<ApplePlatform> ALL_PLATFORMS =
      ImmutableList.of(
          IPHONEOS,
          IPHONESIMULATOR,
          WATCHOS,
          WATCHSIMULATOR,
          APPLETVOS,
          APPLETVSIMULATOR,
          MACOSX,
          DRIVERKIT);
  public static final ImmutableList<Flavor> ALL_PLATFORM_FLAVORS =
      ALL_PLATFORMS.stream()
          .flatMap(
              platform ->
                  platform.getArchitectures().stream()
                      .map(arch -> InternalFlavor.of(platform.getName() + "-" + arch)))
          .collect(ImmutableList.toImmutableList());

  /** The full name of the platform. For example: {@code macosx}. */
  public abstract String getName();

  /**
   * The Swift name for the platform. For example: {@code ios}. If absent, use {@link #getName()}
   * instead.
   */
  public abstract Optional<String> getSwiftName();

  /**
   * The platform name used to match provisioning profiles. For example: {@code iOS}.
   *
   * <p>Not all platforms use provisioning profiles; these will return absent.
   */
  public abstract Optional<String> getProvisioningProfileName();

  @SuppressWarnings("immutables")
  @Value.Default
  public ImmutableList<String> getArchitectures() {
    return ImmutableList.of("armv7", "arm64", "i386", "x86_64");
  }

  @Value.Default
  public String getMinVersionFlagPrefix() {
    return "-m" + getName() + "-version-min=";
  }

  public abstract Optional<String> getStubBinaryPath();

  @Value.Default
  public boolean getAppIncludesFrameworks() {
    return false;
  }

  public ApplePlatformType getType() {
    return ApplePlatformType.of(getName());
  }

  public static boolean needsCodeSign(String name) {
    return name.startsWith(IPHONEOS.getName())
        || name.startsWith(IPHONESIMULATOR.getName())
        || name.startsWith(WATCHOS.getName())
        || name.startsWith(WATCHSIMULATOR.getName())
        || name.startsWith(APPLETVOS.getName())
        || name.startsWith(APPLETVSIMULATOR.getName())
        || name.startsWith(MACOSX.getName());
  }

  public static boolean adHocCodeSignIsSufficient(String name) {
    return name.startsWith(IPHONESIMULATOR.getName())
        || name.startsWith(WATCHSIMULATOR.getName())
        || name.startsWith(APPLETVSIMULATOR.getName())
        || name.startsWith(MACOSX.getName());
  }

  public static boolean needsInstallHelper(String name) {
    return name.startsWith(IPHONEOS.getName());
  }

  public static boolean needsEntitlementsInBinary(String name) {
    return name.startsWith(IPHONESIMULATOR.getName());
  }

  public static boolean isSimulator(String name) {
    return name.startsWith(IPHONESIMULATOR.getName())
        || name.startsWith(WATCHSIMULATOR.getName())
        || name.startsWith(APPLETVSIMULATOR.getName());
  }

  public static ApplePlatform of(String name) {
    for (ApplePlatform platform : ALL_PLATFORMS) {
      if (name.equals(platform.getName())) {
        return platform;
      }
    }
    return ImmutableApplePlatform.builder().setName(name).build();
  }

  public static boolean isPlatformFlavor(Flavor flavor) {
    for (ApplePlatform platform : ALL_PLATFORMS) {
      if (flavor.getName().startsWith(platform.getName())) {
        String[] parts = flavor.getName().split("\\-");
        if (parts.length > 1 && platform.getArchitectures().contains(parts[1])) {
          return true;
        }
      }
    }
    return false;
  }

  public static Optional<String> findAppleSdkName(Flavor flavor) {
    if (!isPlatformFlavor(flavor)) {
      return Optional.empty();
    }
    for (ApplePlatform platform : ALL_PLATFORMS) {
      if (flavor.getName().startsWith(platform.getName())) {
        return Optional.of(flavor.getName().split("\\-")[0]);
      }
    }
    return Optional.empty();
  }

  public static Optional<String> findArchitecture(Flavor flavor) {
    if (!isPlatformFlavor(flavor)) {
      return Optional.empty();
    }
    return Optional.of(flavor.getName().split("\\-")[1]);
  }

  public static ApplePlatform fromFlavor(Flavor flavor) {
    return of(
        findAppleSdkName(flavor)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Can't find ApplePlatform for flavor: " + flavor)));
  }

  @Override
  public int compareTo(ApplePlatform other) {
    if (this == other) {
      return 0;
    }

    return getName().compareTo(other.getName());
  }

  @AddToRuleKey
  public final String getPlatformName() {
    return getName();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableApplePlatform.Builder {}
}
