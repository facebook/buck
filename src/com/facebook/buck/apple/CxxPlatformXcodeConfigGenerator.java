/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple;

import javax.annotation.Nullable;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.log.Logger;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a set of Xcode build configurations from given cxxPlatform
 */
public class CxxPlatformXcodeConfigGenerator {

  public static final String SDKROOT = "SDKROOT";
  public static final String CLANG_CXX_LANGUAGE_STANDARD = "CLANG_CXX_LANGUAGE_STANDARD";
  public static final String CLANG_CXX_LIBRARY = "CLANG_CXX_LIBRARY";
  public static final String OTHER_CPLUSPLUSFLAGS = "OTHER_CPLUSPLUSFLAGS";
  public static final String ARCHS = "ARCHS";
  public static final String DEBUG_BUILD_CONFIGURATION_NAME = "Debug";
  public static final String PROFILE_BUILD_CONFIGURATION_NAME = "Profile";
  public static final String RELEASE_BUILD_CONFIGURATION_NAME = "Release";
  private static final Logger LOG = Logger.get(CxxPlatformXcodeConfigGenerator.class);

  private CxxPlatformXcodeConfigGenerator() {}

  public static ImmutableMap<String, ImmutableMap<String, String>>
  getDefaultXcodeBuildConfigurationsFromCxxPlatform(
      CxxPlatform cxxPlatform,
      Map<String, String> appendedConfig) {

    ArrayList<String> notProcessedCxxFlags = new ArrayList<String>(cxxPlatform.getCxxflags());
    LinkedHashMap<String, String> notProcessedAppendedConfig =
        new LinkedHashMap<String, String>(appendedConfig);

    ImmutableMap.Builder<String, String> configBuilder = ImmutableMap.<String, String>builder();
    setSdkRootAndDeploymentTargetValues(
        configBuilder,
        cxxPlatform,
        notProcessedCxxFlags,
        notProcessedAppendedConfig);
    removeArchsValue(notProcessedCxxFlags, notProcessedAppendedConfig);
    setLanguageStandardValue(configBuilder, notProcessedCxxFlags, notProcessedAppendedConfig);
    setCxxLibraryValue(notProcessedCxxFlags, notProcessedAppendedConfig, configBuilder);
    setOtherCplusplusFlagsValue(configBuilder, notProcessedCxxFlags, notProcessedAppendedConfig);
    configBuilder.putAll(notProcessedAppendedConfig);

    ImmutableMap<String, String> config = configBuilder.build();
    return new ImmutableMap.Builder<String, ImmutableMap<String, String>>()
        .put(DEBUG_BUILD_CONFIGURATION_NAME, config)
        .put(PROFILE_BUILD_CONFIGURATION_NAME, config)
        .put(RELEASE_BUILD_CONFIGURATION_NAME, config)
        .build();
  }

  private static void setOtherCplusplusFlagsValue(
      ImmutableMap.Builder<String, String> configBuilder,
      List<String> notProcessedCxxFlags,
      Map<String, String> notProcessedAppendedConfig) {
    String otherCplusplusFlagsValue = getOtherCplusplusFlags(
        notProcessedAppendedConfig,
        notProcessedCxxFlags);
    configBuilder.put(OTHER_CPLUSPLUSFLAGS, otherCplusplusFlagsValue);
    notProcessedAppendedConfig.remove(OTHER_CPLUSPLUSFLAGS);
  }

  private static void setCxxLibraryValue(
      List<String> notProcessedCxxFlags,
      Map<String, String> notProcessedAppendedConfig,
      ImmutableMap.Builder<String, String> configBuilder) {
    String clangCxxLibraryValue = getConfigValueForKey(
        CLANG_CXX_LIBRARY,
        notProcessedCxxFlags,
        "-stdlib=",
        Optional.<String>absent(),
        notProcessedAppendedConfig);
    if (clangCxxLibraryValue != null) {
      configBuilder.put(CLANG_CXX_LIBRARY, clangCxxLibraryValue);
      notProcessedAppendedConfig.remove(CLANG_CXX_LIBRARY);
    }
  }

  private static void setSdkRootAndDeploymentTargetValues(
      ImmutableMap.Builder<String, String> configBuilder,
      CxxPlatform cxxPlatform,
      List<String> notProcessedCxxFlags,
      Map<String, String> notProcessedAppendedConfig) {
    String sdkRootValue = getSdkRoot(cxxPlatform, notProcessedCxxFlags, notProcessedAppendedConfig);

    if (sdkRootValue != null) {
      configBuilder.put(SDKROOT, sdkRootValue);
      notProcessedAppendedConfig.remove(SDKROOT);
      setDeploymentTargetValue(
          configBuilder,
          sdkRootValue,
          notProcessedCxxFlags,
          notProcessedAppendedConfig);
    }
  }

  private static void removeArchsValue(
      List<String> notProcessedCxxFlags,
      Map<String, String> notProcessedAppendedConfig) {
    // we need to get rid of ARCH setting so Xcode would be able to let user switch between
    // iOS simulator and iOS device without forcing the ARCH of resulting binary
    getArchs(notProcessedAppendedConfig, notProcessedCxxFlags);
    notProcessedAppendedConfig.remove(ARCHS);
  }

  private static void setLanguageStandardValue(
      ImmutableMap.Builder<String, String> configBuilder,
      List<String> notProcessedCxxFlags,
      Map<String, String> notProcessedAppendedConfig) {
    String clangCxxLanguageStandardValue = getConfigValueForKey(
        CLANG_CXX_LANGUAGE_STANDARD,
        notProcessedCxxFlags,
        "-std=",
        Optional.<String>absent(),
        notProcessedAppendedConfig);
    if (clangCxxLanguageStandardValue != null) {
      configBuilder.put(CLANG_CXX_LANGUAGE_STANDARD, clangCxxLanguageStandardValue);
      notProcessedAppendedConfig.remove(CLANG_CXX_LANGUAGE_STANDARD);
    }
  }

  private static void setDeploymentTargetValue(
      ImmutableMap.Builder<String, String> configBuilder,
      String sdkRootValue,
      List<String> notProcessedCxxFlags,
      Map<String, String> notProcessedAppendedConfig) {
    String deploymentTargetKey = sdkRootValue.toUpperCase() + "_DEPLOYMENT_TARGET";  // magic
    String deploymentTargetValue = getConfigValueForKey(
        deploymentTargetKey,
        notProcessedCxxFlags,
        "-m",    // format is like "-mmacosx-version-min=10.9"
        Optional.<String>of("-version-min="),
        notProcessedAppendedConfig);
    if (deploymentTargetValue != null) {
      configBuilder.put(deploymentTargetKey, deploymentTargetValue);
      notProcessedAppendedConfig.remove(deploymentTargetKey);
    }
  }

  private static String getOtherCplusplusFlags(
      Map<String, String> appendedConfig,
      List<String> notProcessesCxxFlags) {
    String value = appendedConfig.get(OTHER_CPLUSPLUSFLAGS);
    ArrayList<String> cPlusPlusFlags = new ArrayList<String>();
    if (value != null) {
      cPlusPlusFlags.add(value);
    }
    for (String item : notProcessesCxxFlags) {
      cPlusPlusFlags.add(item);
    }
    value = Joiner.on(" ").join(cPlusPlusFlags);
    notProcessesCxxFlags.removeAll(cPlusPlusFlags);
    return value;
  }

  @Nullable
  private static String getArchs(
      Map<String, String> appendedConfig,
      List<String> notProcessesCxxFlags) {
    String value = appendedConfig.get(ARCHS);
    if (value == null) {
      int indexOfArch = notProcessesCxxFlags.indexOf("-arch");
      if (indexOfArch != -1) {
        value = notProcessesCxxFlags.get(indexOfArch + 1);
        notProcessesCxxFlags.remove(indexOfArch + 1);
        notProcessesCxxFlags.remove(indexOfArch);
      } else {
        LOG.debug("Can't determine ARCH value");
      }
    }
    return value;
  }

  @Nullable
  private static String getSdkRoot(
      CxxPlatform cxxPlatform,
      List<String> notProcessesCxxFlags,
      Map<String, String> appendedConfig) {
    String value = appendedConfig.get(SDKROOT);
    if (value == null) {
      if (cxxPlatform.getFlavor().getName().startsWith("iphone")) {
        value = "iphoneos";
      } else if (cxxPlatform.getFlavor().getName().startsWith("macosx")) {
        value = "macosx";
      } else {
        LOG.debug("Can't determine %s", SDKROOT);
      }

      int indexOfSysroot = notProcessesCxxFlags.indexOf("-isysroot");
      if (indexOfSysroot != -1) {
        notProcessesCxxFlags.remove(indexOfSysroot + 1);
        notProcessesCxxFlags.remove(indexOfSysroot);
      }
    }
    return value;
  }

  /**
   * If appendedConfig has value for given key, it will be used.
   * Otherwise, this method will attempt to extract value from cxxFlags.
   * */
  @Nullable
  private static String getConfigValueForKey(
      String key,
      List<String> cxxFlags,
      String prefix,
      Optional<String> containmentString,
      Map<String, String> appendedConfig) {
    String value = appendedConfig.get(key);
    if (value == null) {
      int indexOfCxxLibrarySpec = -1;
      for (String item : cxxFlags) {
        if (containmentString.isPresent() && !item.contains(containmentString.get())) {
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
    }
    return value;
  }
}
