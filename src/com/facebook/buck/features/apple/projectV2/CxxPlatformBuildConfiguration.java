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

package com.facebook.buck.features.apple.projectV2;

import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.rules.args.Arg;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/** Utility methods for handling Cxx Platform build configuration and settings. */
class CxxPlatformBuildConfiguration {

  public static final String SDKROOT = "SDKROOT";
  public static final String CLANG_CXX_LANGUAGE_STANDARD = "CLANG_CXX_LANGUAGE_STANDARD";
  public static final String CLANG_CXX_LIBRARY = "CLANG_CXX_LIBRARY";
  public static final String OTHER_CPLUSPLUSFLAGS = "OTHER_CPLUSPLUSFLAGS";
  public static final String ARCHS = "ARCHS";
  private static final Logger LOG = Logger.get(CxxPlatformBuildConfiguration.class);

  /**
   * Get generic build settings to set on a target with the given {@code cxxPlatform} and settings
   * to append {@code appendSettings}.
   */
  public static ImmutableMap<String, String> getGenericTargetBuildSettings(
      CxxPlatform cxxPlatform,
      Map<String, String> appendedSettings,
      SourcePathResolverAdapter pathResolver) {
    ArrayList<String> cxxFlags =
        new ArrayList<>(Arg.stringify(cxxPlatform.getCxxflags(), pathResolver));
    LinkedHashMap<String, String> mutableAppendedSettings = new LinkedHashMap<>(appendedSettings);

    ImmutableMap.Builder<String, String> settingsBuilder = ImmutableMap.builder();
    getAndSetSdkRootAndDeploymentTargetValues(
        settingsBuilder, cxxPlatform, cxxFlags, mutableAppendedSettings);

    return settingsBuilder.build();
  }

  /**
   * Get build settings that apply specificall for CxxLibraries with the given {@code cxxPlatform}
   * and settings to append {@code appendSettings}.
   */
  public static ImmutableMap<String, String> getCxxLibraryBuildSettings(
      CxxPlatform cxxPlatform,
      Map<String, String> appendedSettings,
      SourcePathResolverAdapter pathResolver) {

    ArrayList<String> cxxFlags =
        new ArrayList<>(Arg.stringify(cxxPlatform.getCxxflags(), pathResolver));
    LinkedHashMap<String, String> mutableAppendedSettings = new LinkedHashMap<>(appendedSettings);

    ImmutableMap.Builder<String, String> settingsBuilder = ImmutableMap.builder();
    getAndSetSdkRootAndDeploymentTargetValues(
        settingsBuilder, cxxPlatform, cxxFlags, mutableAppendedSettings);
    removeArchsValue(cxxFlags, mutableAppendedSettings);
    getAndSetLanguageStandardValue(settingsBuilder, cxxFlags, mutableAppendedSettings);
    getAndSetCxxLibraryValue(cxxFlags, mutableAppendedSettings, settingsBuilder);
    getAndSetOtherCplusplusFlags(settingsBuilder, cxxFlags, mutableAppendedSettings);
    setFlagsFromAppendedSettings(settingsBuilder, mutableAppendedSettings);

    return settingsBuilder.build();
  }

  /**
   * Creates default build configurations (for Debug, Profile & Release) with the given {@code
   * cxxPlatform} and settings to append {@code appendSettings}.
   */
  public static ImmutableMap<String, ImmutableMap<String, String>> getDefaultBuildConfigurations(
      CxxPlatform cxxPlatform,
      Map<String, String> appendedSettings,
      SourcePathResolverAdapter pathResolver) {
    ImmutableMap<String, String> config =
        getCxxLibraryBuildSettings(cxxPlatform, appendedSettings, pathResolver);
    return new ImmutableMap.Builder<String, ImmutableMap<String, String>>()
        .put(BuildConfiguration.DEBUG_BUILD_CONFIGURATION_NAME, config)
        .put(BuildConfiguration.PROFILE_BUILD_CONFIGURATION_NAME, config)
        .put(BuildConfiguration.RELEASE_BUILD_CONFIGURATION_NAME, config)
        .build();
  }

  /** Get the SDK Root, and associated deployment target and set them in the settings. */
  private static void getAndSetSdkRootAndDeploymentTargetValues(
      ImmutableMap.Builder<String, String> settingsBuilder,
      CxxPlatform cxxPlatform,
      List<String> cxxFlags,
      Map<String, String> appendedSettings) {
    String sdkRootValue = appendedSettings.get(SDKROOT);
    if (sdkRootValue == null) {
      String cxxFlavor = cxxPlatform.getFlavor().getName();
      if (cxxFlavor.startsWith("iphone")) {
        sdkRootValue = "iphoneos";
      } else if (cxxFlavor.startsWith("macosx")) {
        sdkRootValue = "macosx";
      } else {
        LOG.debug("Can't determine %s", SDKROOT);
      }

      // If "isysroot" is part of notProcessesCxxFlags, remove the flag key and its following value
      int indexOfSysroot = cxxFlags.indexOf("-isysroot");
      if (indexOfSysroot != -1) {
        cxxFlags.remove(indexOfSysroot + 1);
        cxxFlags.remove(indexOfSysroot);
      }
    }

    if (sdkRootValue != null) {
      settingsBuilder.put(SDKROOT, sdkRootValue);
      appendedSettings.remove(SDKROOT);
      getAndSetDeploymentTargetValue(settingsBuilder, sdkRootValue, cxxFlags, appendedSettings);
    }
  }

  /** For the specific SDK, get the deployment target and set it in the settings. */
  private static void getAndSetDeploymentTargetValue(
      ImmutableMap.Builder<String, String> settingsBuilder,
      String sdkRootValue,
      List<String> cxxFlags,
      Map<String, String> appendedSettings) {
    String deploymentTargetKey = sdkRootValue.toUpperCase() + "_DEPLOYMENT_TARGET"; // magic
    String deploymentTargetValue =
        getConfigValueForKey(
            deploymentTargetKey, appendedSettings, "-m", Optional.of("-version-min="), cxxFlags
            // format is like "-mmacosx-version-min=10.9"
            );
    if (deploymentTargetValue != null) {
      settingsBuilder.put(deploymentTargetKey, deploymentTargetValue);
    }
  }

  /**
   * Remove the {@code -arch} flag from all flags, to allow Xcode to set the architecture of the
   * resulting binary in order to allow users to switch between iOS simulator and device.
   */
  private static void removeArchsValue(
      List<String> cxxFlags, Map<String, String> appendedSettings) {
    String value = appendedSettings.get(ARCHS);
    if (value == null) {
      int indexOfArch = cxxFlags.indexOf("-arch");
      if (indexOfArch != -1) {
        value = cxxFlags.get(indexOfArch + 1);
        cxxFlags.remove(indexOfArch + 1);
        cxxFlags.remove(indexOfArch);
      } else {
        LOG.debug("Can't determine ARCH value");
      }
    }
    appendedSettings.remove(ARCHS);
  }

  /**
   * Gets the Cxx standard language value from either {@code cxxFlags} or {@code appendedSettings}
   * if it exists and sets it in the settings. Removes the value from both these sources.
   */
  private static void getAndSetLanguageStandardValue(
      ImmutableMap.Builder<String, String> settingsBuilder,
      List<String> cxxFlags,
      Map<String, String> appendedSettings) {
    String clangCxxLanguageStandardValue =
        getConfigValueForKey(
            CLANG_CXX_LANGUAGE_STANDARD, appendedSettings, "-std=", Optional.empty(), cxxFlags);
    if (clangCxxLanguageStandardValue != null) {
      settingsBuilder.put(CLANG_CXX_LANGUAGE_STANDARD, clangCxxLanguageStandardValue);
    }
  }

  /**
   * Gets the Cxx Library language value from either {@code cxxFlags} or {@code appendedSettings} if
   * it exists and sets it in the settings. Removes the value from both these sources.
   */
  private static void getAndSetCxxLibraryValue(
      List<String> cxxFlags,
      Map<String, String> appendedSettings,
      ImmutableMap.Builder<String, String> settingsBuilder) {
    String clangCxxLibraryValue =
        getConfigValueForKey(
            CLANG_CXX_LIBRARY, appendedSettings, "-stdlib=", Optional.empty(), cxxFlags);
    if (clangCxxLibraryValue != null) {
      settingsBuilder.put(CLANG_CXX_LIBRARY, clangCxxLibraryValue);
    }
  }

  /**
   * Get all the {@code OTHER_CPLUSPLUSFLAGS} values in {@code cxxFlags} and {@code
   * appendedSettings} and add them to the settings. Removes this key from both these sources.
   */
  private static void getAndSetOtherCplusplusFlags(
      ImmutableMap.Builder<String, String> settingsBuilder,
      List<String> cxxFlags,
      Map<String, String> appendedSettings) {
    if (cxxFlags.isEmpty()) {
      return;
    }

    String otherCplusplusFlags = appendedSettings.get(OTHER_CPLUSPLUSFLAGS);
    appendedSettings.remove(OTHER_CPLUSPLUSFLAGS);

    ArrayList<String> cPlusPlusFlags = new ArrayList<String>();
    if (otherCplusplusFlags != null) {
      cPlusPlusFlags.add(otherCplusplusFlags);
    }
    cPlusPlusFlags.addAll(cxxFlags);
    otherCplusplusFlags = Joiner.on(" ").join(cPlusPlusFlags);
    cxxFlags.clear();

    settingsBuilder.put(OTHER_CPLUSPLUSFLAGS, otherCplusplusFlags);
  }

  /** Set all the flags from appendedSettings to settings. */
  private static void setFlagsFromAppendedSettings(
      ImmutableMap.Builder<String, String> settingsBuilder, Map<String, String> appendedSettings) {
    for (Map.Entry<String, String> entry : ImmutableSet.copyOf(appendedSettings.entrySet())) {
      if (entry.getValue().length() > 0) {
        settingsBuilder.put(entry);
      }
      appendedSettings.remove(entry.getKey());
    }
  }

  /**
   * Gets the value for the given {@code key} from {@code appendedConfig} if it exists, else it will
   * look in {@code cxxFlags} for the entry with {@code prefix} and {@code containingString}. If the
   * value is found it is removed from the source in which it exists.
   *
   * @param key Key for the value to get
   * @param appendedSettings Key/value map to get the value if the key is present
   * @param prefix Prefix the key must begin with
   * @param containingString Optional string the key must contain
   * @param cxxFlags CxxFlags to get the key/value if the key does not exist in {@code
   *     appendedConfig}
   * @return The value corresponding to the key passed, or null.
   */
  @Nullable
  private static String getConfigValueForKey(
      String key,
      Map<String, String> appendedSettings,
      String prefix,
      Optional<String> containingString,
      List<String> cxxFlags) {
    // If the value exists in appendSettings, get it, remove it from appendSettings and return.
    String value = appendedSettings.get(key);
    if (value != null) {
      appendedSettings.remove(key);
      return value;
    }

    // If the value doesn't exist, look within cxxFlags
    int indexOfCxxLibrarySpec = -1;
    for (String item : cxxFlags) {
      if (containingString.isPresent() && !item.contains(containingString.get())) {
        continue;
      }
      if (item.startsWith(prefix)) {
        indexOfCxxLibrarySpec = cxxFlags.indexOf(item);
        value = item.substring(item.indexOf('=') + 1);
        break;
      }
    }
    if (indexOfCxxLibrarySpec != -1) {
      cxxFlags.remove(indexOfCxxLibrarySpec);
    } else {
      LOG.debug("Cannot determine value of %s", key);
    }
    return value;
  }
}
