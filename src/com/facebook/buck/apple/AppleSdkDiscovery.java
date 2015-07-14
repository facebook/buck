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

package com.facebook.buck.apple;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.VersionStringComparator;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.TreeMultimap;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Utility class to discover the location of SDKs contained inside an Xcode
 * installation.
 */
public class AppleSdkDiscovery {

  private static final Logger LOG = Logger.get(AppleSdkDiscovery.class);

  private static final Ordering<AppleSdk> APPLE_SDK_VERSION_ORDERING =
    Ordering
        .from(new VersionStringComparator())
        .onResultOf(new Function<AppleSdk, String>() {
            @Override
            public String apply(AppleSdk appleSdk) {
                return appleSdk.getVersion();
            }
        });

  private static final String DEFAULT_TOOLCHAIN_ID = "com.apple.dt.toolchain.XcodeDefault";

  // Utility class; do not instantiate.
  private AppleSdkDiscovery() { }

  /**
   * Given a path to an Xcode developer directory and a map of
   * (xctoolchain ID: path) pairs as returned by
   * {@link AppleToolchainDiscovery}, walks through the platforms
   * and builds a map of ({@link AppleSdk}: {@link AppleSdkPaths})
   * objects describing the paths to the SDKs inside.
   *
   * The {@link AppleSdk#getName()} strings match the ones displayed by {@code xcodebuild -showsdks}
   * and look like {@code macosx10.9}, {@code iphoneos8.0}, {@code iphonesimulator8.0},
   * etc.
   */
  public static ImmutableMap<AppleSdk, AppleSdkPaths> discoverAppleSdkPaths(
      Optional<Path> developerDir,
      ImmutableList<Path> extraDirs,
      ImmutableMap<String, AppleToolchain> xcodeToolchains)
      throws IOException {
    Optional<AppleToolchain> defaultToolchain =
        Optional.fromNullable(xcodeToolchains.get(DEFAULT_TOOLCHAIN_ID));

    ImmutableMap.Builder<AppleSdk, AppleSdkPaths> appleSdkPathsBuilder = ImmutableMap.builder();

    Iterable<Path> platformPaths = extraDirs;
    if (developerDir.isPresent()) {
      Path platformsDir = developerDir.get().resolve("Platforms");
      LOG.debug("Searching for Xcode platforms under %s", platformsDir);
      platformPaths = Iterables.concat(
        ImmutableSet.of(platformsDir), platformPaths);
    }

    // We need to find the most recent SDK for each platform so we can
    // make the fall-back SDKs with no version number in their name
    // ("macosx", "iphonesimulator", "iphoneos").
    //
    // To do this, we store a map of (platform: [sdk1, sdk2, ...])
    // pairs where the SDKs for each platform are ordered by version.
    TreeMultimap<ApplePlatform, AppleSdk> orderedSdksForPlatform =
        TreeMultimap.create(
            Ordering.natural(),
            APPLE_SDK_VERSION_ORDERING);

    for (Path platforms : platformPaths) {
      if (!Files.exists(platforms)) {
        LOG.debug("Skipping platform search path %s that does not exist", platforms);
        continue;
      }
      LOG.debug("Searching for Xcode SDKs in %s", platforms);

      try (DirectoryStream<Path> platformStream = Files.newDirectoryStream(
          platforms,
               "*.platform")) {
        for (Path platformDir : platformStream) {
          Path developerSdksPath = platformDir.resolve("Developer/SDKs");
          try (DirectoryStream<Path> sdkStream = Files.newDirectoryStream(
                   developerSdksPath,
                   "*.sdk")) {
            for (Path sdkDir : sdkStream) {
              LOG.debug("Fetching SDK name for %s", sdkDir);
              if (Files.isSymbolicLink(sdkDir)) {
                continue;
              }

              AppleSdk.Builder sdkBuilder = AppleSdk.builder();
              if (buildSdkFromPath(sdkDir, sdkBuilder, xcodeToolchains, defaultToolchain)) {
                AppleSdk sdk = sdkBuilder.build();
                LOG.debug("Found SDK %s", sdk);

                AppleSdkPaths.Builder xcodePathsBuilder = AppleSdkPaths.builder();
                for (AppleToolchain toolchain : sdk.getToolchains()) {
                  xcodePathsBuilder.addToolchainPaths(toolchain.getPath());
                }
                AppleSdkPaths xcodePaths = xcodePathsBuilder
                    .setDeveloperPath(developerDir)
                    .setPlatformPath(platformDir)
                    .setSdkPath(sdkDir)
                    .build();
                appleSdkPathsBuilder.put(sdk, xcodePaths);
                orderedSdksForPlatform.put(sdk.getApplePlatform(), sdk);
              }
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
      AppleSdk mostRecentSdkForPlatform = orderedSdksForPlatform.get(platform).last();
      if (!mostRecentSdkForPlatform.getName().equals(platform.getName())) {
        appleSdkPathsBuilder.put(
            mostRecentSdkForPlatform.withName(platform.getName()),
            discoveredSdkPaths.get(mostRecentSdkForPlatform));
      }
    }

    // This includes both the discovered SDKs with versions in their names, as well as
    // the unversioned aliases added just above.
    return appleSdkPathsBuilder.build();
  }

  private static void addArchitecturesForPlatform(
      AppleSdk.Builder sdkBuilder,
      ApplePlatform applePlatform) {
    // TODO(user): These need to be read from the SDK, not hard-coded.
    switch (applePlatform.getName()) {
      case ApplePlatform.Name.MACOSX:
        // Fall through.
      case ApplePlatform.Name.IPHONESIMULATOR:
        sdkBuilder.addArchitectures("i386", "x86_64");
        break;
      case ApplePlatform.Name.IPHONEOS:
        sdkBuilder.addArchitectures("armv7", "arm64");
        break;
      case ApplePlatform.Name.WATCHSIMULATOR:
        sdkBuilder.addArchitectures("i386");
        break;
      case ApplePlatform.Name.WATCHOS:
        sdkBuilder.addArchitectures("armv7k");
        break;
      default:
        sdkBuilder.addArchitectures("armv7", "arm64", "i386", "x86_64");
        break;
    }
  }

  private static boolean buildSdkFromPath(
        Path sdkDir,
        AppleSdk.Builder sdkBuilder,
        ImmutableMap<String, AppleToolchain> xcodeToolchains,
        Optional<AppleToolchain> defaultToolchain) throws IOException {
    try (InputStream sdkSettingsPlist = Files.newInputStream(sdkDir.resolve("SDKSettings.plist"));
         BufferedInputStream bufferedSdkSettingsPlist = new BufferedInputStream(sdkSettingsPlist)) {
      NSDictionary sdkSettings;
      try {
        sdkSettings = (NSDictionary) PropertyListParser.parse(bufferedSdkSettingsPlist);
      } catch (Exception e) {
        throw new IOException(e);
      }
      String name = sdkSettings.objectForKey("CanonicalName").toString();
      String version = sdkSettings.objectForKey("Version").toString();
      NSDictionary defaultProperties = (NSDictionary) sdkSettings.objectForKey("DefaultProperties");

      boolean foundToolchain = false;
      NSArray toolchains = (NSArray) sdkSettings.objectForKey("Toolchains");
      if (toolchains != null) {
        for (NSObject toolchainIdObject : toolchains.getArray()) {
          String toolchainId = toolchainIdObject.toString();
          AppleToolchain toolchain = xcodeToolchains.get(toolchainId);
          if (toolchain != null) {
            foundToolchain = true;
            sdkBuilder.addToolchains(toolchain);
          } else {
            LOG.debug("Specified toolchain %s not found for SDK path %s", toolchainId, sdkDir);
          }
        }
      }
      if (!foundToolchain && defaultToolchain.isPresent()) {
        foundToolchain = true;
        sdkBuilder.addToolchains(defaultToolchain.get());
      }
      if (!foundToolchain) {
        LOG.warn("No toolchains found and no default toolchain. Skipping SDK path %s.", sdkDir);
        return false;
      } else {
        NSString platformName = (NSString) defaultProperties.objectForKey("PLATFORM_NAME");
        ApplePlatform applePlatform =
            ApplePlatform.builder().setName(platformName.toString()).build();
        sdkBuilder.setName(name).setVersion(version).setApplePlatform(applePlatform);
        addArchitecturesForPlatform(sdkBuilder, applePlatform);
        return true;
      }
    } catch (FileNotFoundException e) {
      LOG.error(e, "No SDKSettings.plist found under SDK path %s", sdkDir);
      return false;
    }
  }
}
