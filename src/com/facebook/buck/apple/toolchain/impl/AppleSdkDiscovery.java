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

package com.facebook.buck.apple.toolchain.impl;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.VersionStringComparator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import com.google.common.collect.TreeMultimap;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

/** Utility class to discover the location of SDKs contained inside an Xcode installation. */
public class AppleSdkDiscovery {

  private static final Logger LOG = Logger.get(AppleSdkDiscovery.class);

  private static final Ordering<AppleSdk> APPLE_SDK_VERSION_ORDERING =
      new Ordering<AppleSdk>() {
        VersionStringComparator versionComparator = new VersionStringComparator();

        @Override
        public int compare(AppleSdk thisSdk, AppleSdk thatSdk) {
          int result = versionComparator.compare(thisSdk.getVersion(), thatSdk.getVersion());
          return result == 0 ? thisSdk.getName().compareTo(thatSdk.getName()) : result;
        }
      };

  private static final String DEFAULT_TOOLCHAIN_ID = "com.apple.dt.toolchain.XcodeDefault";

  // Utility class; do not instantiate.
  private AppleSdkDiscovery() {}

  /**
   * Given a path to an Xcode developer directory and a map of (xctoolchain ID: path) pairs as
   * returned by {@link AppleToolchainDiscovery}, walks through the platforms and builds a map of
   * ({@link AppleSdk}: {@link AppleSdkPaths}) objects describing the paths to the SDKs inside.
   *
   * <p>The {@link AppleSdk#getName()} strings match the ones displayed by {@code xcodebuild
   * -showsdks} and look like {@code macosx10.9}, {@code iphoneos8.0}, {@code iphonesimulator8.0},
   * etc.
   */
  public static ImmutableMap<AppleSdk, AppleSdkPaths> discoverAppleSdkPaths(
      Optional<Path> developerDir,
      ImmutableList<Path> extraDirs,
      ImmutableMap<String, AppleToolchain> xcodeToolchains,
      AppleConfig appleConfig,
      ProjectFilesystem projectFilesystem)
      throws IOException {
    Optional<AppleToolchain> defaultToolchain =
        Optional.ofNullable(xcodeToolchains.get(DEFAULT_TOOLCHAIN_ID));

    ImmutableMap.Builder<AppleSdk, AppleSdkPaths> appleSdkPathsBuilder = ImmutableMap.builder();

    HashSet<Path> platformPaths = new HashSet<Path>(extraDirs);
    if (developerDir.isPresent()) {
      Path platformsDir = developerDir.get().resolve("Platforms");
      LOG.debug("Searching for Xcode platforms under %s", platformsDir);
      platformPaths.add(platformsDir);
    }

    // We need to find the most recent SDK for each platform so we can
    // make the fall-back SDKs with no version number in their name
    // ("macosx", "iphonesimulator", "iphoneos").
    //
    // To do this, we store a map of (platform: [sdk1, sdk2, ...])
    // pairs where the SDKs for each platform are ordered by version.
    TreeMultimap<ApplePlatform, AppleSdk> orderedSdksForPlatform =
        TreeMultimap.create(Ordering.natural(), APPLE_SDK_VERSION_ORDERING);

    for (Path platforms : platformPaths) {
      if (!Files.exists(platforms)) {
        LOG.debug("Skipping platform search path %s that does not exist", platforms);
        continue;
      }
      LOG.debug("Searching for Xcode SDKs in %s", platforms);

      try (DirectoryStream<Path> platformStream =
          Files.newDirectoryStream(platforms, "*.platform")) {
        for (Path platformDir : platformStream) {
          Path developerSdksPath = platformDir.resolve("Developer/SDKs");
          try (DirectoryStream<Path> sdkStream =
              Files.newDirectoryStream(developerSdksPath, "*.sdk")) {
            Set<Path> scannedSdkDirs = new HashSet<>();
            for (Path sdkDir : sdkStream) {
              LOG.debug("Fetching SDK name for %s", sdkDir);

              try {
                sdkDir = sdkDir.toRealPath();
              } catch (NoSuchFileException e) {
                LOG.warn(e, "SDK at path %s is a dangling link, ignoring", sdkDir);
                continue;
              }
              if (scannedSdkDirs.contains(sdkDir)) {
                LOG.debug("Skipping already scanned SDK directory %s", sdkDir);
                continue;
              }

              ImmutableList<AppleSdk> discoveredSdks =
                  buildSdksFromPath(sdkDir, xcodeToolchains, defaultToolchain, appleConfig);
              for (AppleSdk sdk : discoveredSdks) {
                LOG.debug("Found SDK %s", sdk);

                AppleSdkPaths.Builder xcodePathsBuilder = AppleSdkPaths.builder();
                for (AppleToolchain toolchain : sdk.getToolchains()) {
                  xcodePathsBuilder.addToolchainPaths(toolchain.getPath());
                }
                AppleSdkPaths xcodePaths =
                    xcodePathsBuilder
                        .setDeveloperPath(developerDir)
                        .setPlatformPath(platformDir)
                        .setPlatformSourcePath(PathSourcePath.of(projectFilesystem, platformDir))
                        .setSdkPath(sdkDir)
                        .setSdkSourcePath(PathSourcePath.of(projectFilesystem, sdkDir))
                        .build();
                appleSdkPathsBuilder.put(sdk, xcodePaths);
                orderedSdksForPlatform.put(sdk.getApplePlatform(), sdk);
              }
              scannedSdkDirs.add(sdkDir);
            }
          } catch (NoSuchFileException e) {
            LOG.warn(
                e,
                "Couldn't discover SDKs at path %s, ignoring platform %s",
                developerSdksPath,
                platformDir);
          }
        }
      }
    }

    // Get a snapshot of what's in appleSdkPathsBuilder, then for each
    // ApplePlatform, add to appleSdkPathsBuilder the most recent
    // SDK with an unversioned name.
    ImmutableMap<AppleSdk, AppleSdkPaths> discoveredSdkPaths = appleSdkPathsBuilder.build();

    for (ApplePlatform platform : orderedSdksForPlatform.keySet()) {
      Set<AppleSdk> platformSdks = orderedSdksForPlatform.get(platform);
      boolean shouldCreateUnversionedSdk = true;
      for (AppleSdk sdk : platformSdks) {
        shouldCreateUnversionedSdk &= !sdk.getName().equals(platform.getName());
      }

      if (shouldCreateUnversionedSdk) {
        AppleSdk mostRecentSdkForPlatform = orderedSdksForPlatform.get(platform).last();
        appleSdkPathsBuilder.put(
            mostRecentSdkForPlatform.withName(platform.getName()),
            discoveredSdkPaths.get(mostRecentSdkForPlatform));
      }
    }

    // This includes both the discovered SDKs with versions in their names, as well as
    // the unversioned aliases added just above.
    return appleSdkPathsBuilder.build();
  }

  private static ImmutableList<AppleSdk> buildSdksFromPath(
      Path sdkDir,
      ImmutableMap<String, AppleToolchain> xcodeToolchains,
      Optional<AppleToolchain> defaultToolchain,
      AppleConfig appleConfig)
      throws IOException {
    try (InputStream sdkSettingsPlist = Files.newInputStream(sdkDir.resolve("SDKSettings.plist"));
        BufferedInputStream bufferedSdkSettingsPlist = new BufferedInputStream(sdkSettingsPlist)) {
      NSDictionary sdkSettings;
      try {
        sdkSettings = (NSDictionary) PropertyListParser.parse(bufferedSdkSettingsPlist);
      } catch (PropertyListFormatException | ParseException | SAXException e) {
        LOG.error(e, "Malformatted SDKSettings.plist. Skipping SDK path %s.", sdkDir);
        return ImmutableList.of();
      } catch (ParserConfigurationException e) {
        throw new IOException(e);
      }
      String name = sdkSettings.objectForKey("CanonicalName").toString();
      String version = sdkSettings.objectForKey("Version").toString();
      NSDictionary defaultProperties = (NSDictionary) sdkSettings.objectForKey("DefaultProperties");
      NSString platformName = (NSString) defaultProperties.objectForKey("PLATFORM_NAME");
      if (name.startsWith("driverkit")) {
        platformName = new NSString("driverkit");
      }

      Optional<ImmutableList<String>> toolchains =
          appleConfig.getToolchainsOverrideForSDKName(platformName.toString());
      boolean foundToolchain = false;
      if (!toolchains.isPresent()) {
        NSArray settingsToolchains = (NSArray) sdkSettings.objectForKey("Toolchains");
        if (settingsToolchains != null) {
          toolchains =
              Optional.of(
                  Arrays.stream(settingsToolchains.getArray())
                      .map(Object::toString)
                      .collect(ImmutableList.toImmutableList()));
        }
      }

      ImmutableList.Builder<AppleToolchain> sdkToolchains = ImmutableList.builder();

      if (toolchains.isPresent()) {
        for (String toolchainId : toolchains.get()) {
          AppleToolchain toolchain = xcodeToolchains.get(toolchainId);
          if (toolchain != null) {
            foundToolchain = true;
            sdkToolchains.add(toolchain);
          } else {
            LOG.debug("Specified toolchain %s not found for SDK path %s", toolchainId, sdkDir);
          }
        }
      }
      if (!foundToolchain && defaultToolchain.isPresent()) {
        foundToolchain = true;
        sdkToolchains.add(defaultToolchain.get());
      }
      if (!foundToolchain) {
        LOG.warn("No toolchains found and no default toolchain. Skipping SDK path %s.", sdkDir);
        return ImmutableList.of();
      } else {
        return extractSdksWithToolchains(
            sdkDir,
            name,
            version,
            platformName,
            sdkToolchains.build(),
            sdkSettings,
            defaultProperties,
            defaultToolchain);
      }
    } catch (NoSuchFileException e) {
      LOG.warn(e, "Skipping SDK at path %s, no SDKSettings.plist found", sdkDir);
      return ImmutableList.of();
    }
  }

  private static ImmutableList<AppleSdk> extractSdksWithToolchains(
      Path sdkDir,
      String name,
      String version,
      NSString platformName,
      ImmutableList<AppleToolchain> sdkToolchains,
      NSDictionary sdkSettings,
      NSDictionary defaultProperties,
      Optional<AppleToolchain> defaultToolchain)
      throws IOException {
    AppleSdk.Builder sdkBuilder = AppleSdk.builder();
    sdkBuilder.addAllToolchains(sdkToolchains);
    ApplePlatform applePlatform = ApplePlatform.of(platformName.toString());
    sdkBuilder.setName(name).setVersion(version).setApplePlatform(applePlatform);
    ImmutableList<String> architectures = validArchitecturesForPlatform(applePlatform, sdkDir);
    sdkBuilder.addAllArchitectures(architectures);
    AppleSdk sdk = sdkBuilder.build();

    ImmutableList.Builder<AppleSdk> sdkList = ImmutableList.builder();

    sdkList.add(sdk);

    tryExtractingCatalystSdk(
        sdkDir,
        name,
        version,
        sdkToolchains,
        sdkSettings,
        defaultProperties,
        defaultToolchain,
        architectures,
        sdkList);

    return sdkList.build();
  }

  private static ImmutableList<String> validArchitecturesForPlatform(
      ApplePlatform platform, Path sdkDir) throws IOException {
    ImmutableList<String> architectures = platform.getArchitectures();
    try (DirectoryStream<Path> sdkFiles = Files.newDirectoryStream(sdkDir)) {
      ImmutableList.Builder<String> architectureSubdirsBuilder = ImmutableList.builder();
      for (Path path : sdkFiles) {
        if (Files.isDirectory(path)) {
          String directoryName = path.getFileName().toString();
          // Default Apple SDKs contain fat binaries and have no architecture subdirectories,
          // but custom SDKs might.
          if (architectures.contains(directoryName)) {
            architectureSubdirsBuilder.add(directoryName);
          }
        }
      }

      ImmutableList<String> architectureSubdirs = architectureSubdirsBuilder.build();
      if (!architectureSubdirs.isEmpty()) {
        architectures = architectureSubdirs;
      }
    }
    return architectures;
  }

  private static void tryExtractingCatalystSdk(
      Path sdkDir,
      String name,
      String version,
      ImmutableList<AppleToolchain> sdkToolchains,
      NSDictionary sdkSettings,
      NSDictionary defaultProperties,
      Optional<AppleToolchain> defaultToolchain,
      ImmutableList<String> architectures,
      ImmutableList.Builder<AppleSdk> sdkList) {
    Optional<NSDictionary> maybeCatalystBuildSettings = extractCatalystBuildSettings(sdkSettings);
    if (maybeCatalystBuildSettings.isPresent() && name.indexOf("macosx") == 0) {
      NSDictionary catalystBuildSettings = maybeCatalystBuildSettings.get();

      AppleSdk.Builder catalystSdkBuilder = AppleSdk.builder();
      String catalystSdkName = name.replace("macosx", "maccatalyst");
      catalystSdkBuilder
          .setName(catalystSdkName)
          .setVersion(version)
          .setApplePlatform(ApplePlatform.MACOSXCATALYST)
          .addAllToolchains(sdkToolchains)
          .addAllArchitectures(architectures);

      updateCatalystSDKTargetTripleProperties(catalystSdkBuilder, sdkSettings);
      updateCatalystSDKAdditionalSearchPaths(
          catalystSdkBuilder, catalystBuildSettings, sdkDir, defaultProperties, defaultToolchain);
      updateCatalystSDKResourceFamilies(catalystSdkBuilder, catalystBuildSettings);

      sdkList.add(catalystSdkBuilder.build());
    }
  }

  private static Optional<String> extractStringFromNSDictionary(
      NSDictionary dictionary, String key) {
    NSObject untypedValue = dictionary.get(key);
    if (untypedValue instanceof NSString) {
      NSString stringValue = (NSString) untypedValue;
      return Optional.of(stringValue.getContent());
    }

    return Optional.empty();
  }

  private static void updateCatalystSDKResourceFamilies(
      AppleSdk.Builder sdkBuilder, NSDictionary buildSettings) {
    sdkBuilder
        .setResourcesDeviceFamily(
            extractStringFromNSDictionary(buildSettings, "RESOURCES_TARGETED_DEVICE_FAMILY"))
        .setResourcesUIFrameworkFamily(
            extractStringFromNSDictionary(buildSettings, "RESOURCES_UI_FRAMEWORK_FAMILY"));
  }

  private static void updateCatalystSDKTargetTripleProperties(
      AppleSdk.Builder sdkBuilder, NSDictionary sdkSettings) {
    NSObject untypedSupportedTargets = sdkSettings.get("SupportedTargets");
    if (!(untypedSupportedTargets instanceof NSDictionary)) {
      return;
    }

    NSDictionary supportedTargets = (NSDictionary) untypedSupportedTargets;
    NSObject untypedCatalystTargetDict = supportedTargets.get("iosmac");
    if (!(untypedCatalystTargetDict instanceof NSDictionary)) {
      return;
    }

    NSDictionary catalystTargetDict = (NSDictionary) untypedCatalystTargetDict;
    sdkBuilder
        .setTargetTripleVendor(
            extractStringFromNSDictionary(catalystTargetDict, "LLVMTargetTripleVendor"))
        .setTargetTriplePlatformName(
            extractStringFromNSDictionary(catalystTargetDict, "LLVMTargetTripleSys"))
        .setTargetTripleABI(
            extractStringFromNSDictionary(catalystTargetDict, "LLVMTargetTripleEnvironment"));
  }

  private static void updateCatalystSDKAdditionalSearchPaths(
      AppleSdk.Builder sdkBuilder,
      NSDictionary buildSettings,
      Path sdkDir,
      NSDictionary defaultProperties,
      Optional<AppleToolchain> defaultToolchain) {
    ImmutableMap<String, String> variableMap =
        generateCatalystVariableMap(sdkDir, defaultProperties, defaultToolchain);
    ImmutableList<String> libSearchPaths =
        expandAdditionalSearchPaths(buildSettings, "LIBRARY_SEARCH_PATHS", variableMap);
    ImmutableList<String> systemFrameworkSearchPaths =
        expandAdditionalSearchPaths(buildSettings, "SYSTEM_FRAMEWORK_SEARCH_PATHS", variableMap);
    ImmutableList<String> systemHeaderSearchPaths =
        expandAdditionalSearchPaths(buildSettings, "SYSTEM_HEADER_SEARCH_PATHS", variableMap);

    if (variableMap.containsKey("$(IOS_UNZIPPERED_TWIN_PREFIX_PATH)")) {
      Path prefixPath = Paths.get(variableMap.get("$(IOS_UNZIPPERED_TWIN_PREFIX_PATH)"));
      sdkBuilder.setMobileTwinPrefixPath(prefixPath);
    }

    sdkBuilder
        .addAllAdditionalSystemFrameworkSearchPaths(systemFrameworkSearchPaths)
        .addAllAdditionalLibrarySearchPaths(libSearchPaths)
        .addAllAdditionalSystemHeaderSearchPaths(systemHeaderSearchPaths);
  }

  private static ImmutableMap<String, String> generateCatalystVariableMap(
      Path sdkDir, NSDictionary defaultProperties, Optional<AppleToolchain> maybeToolchain) {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    builder.put("$(SDKROOT)", sdkDir.toString());

    NSObject maybeMobileSupportDir = defaultProperties.get("IOS_UNZIPPERED_TWIN_PREFIX_PATH");
    if (maybeMobileSupportDir instanceof NSString) {
      NSString mobileSupportDir = (NSString) maybeMobileSupportDir;
      builder.put("$(IOS_UNZIPPERED_TWIN_PREFIX_PATH)", mobileSupportDir.getContent());
    }

    maybeToolchain.ifPresent(
        toolchain -> builder.put("$(TOOLCHAIN_DIR)", toolchain.getPath().toString()));

    return builder.build();
  }

  private static ImmutableList<String> expandAdditionalSearchPaths(
      NSDictionary buildSettings, String key, ImmutableMap<String, String> variableMap) {
    NSObject untypedPathString = buildSettings.get(key);
    if (!(untypedPathString instanceof NSString)) {
      return ImmutableList.of();
    }

    String pathString = ((NSString) untypedPathString).getContent();
    ImmutableList<String> stringParts = ImmutableList.copyOf(pathString.split("\\s+"));
    if (!stringParts.contains("$(inherited)")) {
      // The additional search paths returned are _additive_, so if there's no $(inherited),
      // it cannot be represented.
      LOG.warn(
          "Additional search path for key '%s' does not contain $(inherited): '%s'",
          key, stringParts);
      return ImmutableList.of();
    }

    ImmutableList<String> expandedPaths =
        stringParts.stream()
            .filter(part -> !part.equals("$(inherited)"))
            .map(
                part -> {
                  String currentPart = part;
                  for (Map.Entry<String, String> entry : variableMap.entrySet()) {
                    String variable = entry.getKey();
                    String value = entry.getValue();
                    currentPart = currentPart.replace(variable, value);
                  }
                  return currentPart;
                })
            .filter(
                expandedPath -> {
                  boolean containsUnexpandedVariables = expandedPath.contains("$");
                  if (containsUnexpandedVariables) {
                    LOG.warn(
                        "Found search path for key '%s' containing unexpanded variable, skipping: '%s'",
                        key, expandedPath);
                  }
                  return !containsUnexpandedVariables;
                })
            .filter(
                expandedPath -> {
                  Path path = Paths.get(expandedPath);
                  if (path.isAbsolute() && !Files.isDirectory(path)) {
                    // The SDK plist for certain versions of Xcode contain non-existent/invalid
                    // absolute paths, passing those to the linker will trigger warnings and
                    // possibly failure if -fatal_warnings is passed.
                    LOG.warn(
                        "Found search path for key '%s' which does not exist/not a directory, skipping: '%s'",
                        key, expandedPath);
                    return false;
                  }

                  return true;
                })
            .collect(ImmutableList.toImmutableList());

    return expandedPaths;
  }

  private static Optional<NSDictionary> extractCatalystBuildSettings(NSDictionary sdkSettings) {
    NSObject variantsObject = sdkSettings.get("Variants");
    if (!(variantsObject instanceof NSArray)) {
      return Optional.empty();
    }

    NSArray variants = (NSArray) variantsObject;
    for (NSObject variantObject : variants.getArray()) {
      if (!(variantObject instanceof NSDictionary)) {
        continue;
      }

      NSDictionary variant = (NSDictionary) variantObject;
      NSString catalystName = new NSString("iosmac");
      if (!catalystName.equals(variant.get("Name"))) {
        continue;
      }

      NSObject settingsObject = variant.get("BuildSettings");
      if (settingsObject instanceof NSDictionary) {
        return Optional.of((NSDictionary) settingsObject);
      }
    }

    return Optional.empty();
  }
}
