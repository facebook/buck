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

import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.log.Logger;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility class to discover the location of SDKs contained inside an Xcode
 * installation.
 */
public class AppleSdkDiscovery {

  private static final Logger LOG = Logger.get(AppleSdkDiscovery.class);

  // Utility class; do not instantiate.
  private AppleSdkDiscovery() { }

  /**
   * Given a path to an Xcode developer directory, walks through the
   * platforms and builds a map of (sdk-name: {@link AppleSdkPaths}) objects
   * describing the paths to the SDKs inside.
   *
   * The sdk-name strings match the ones displayed by {@code xcodebuild -showsdks}
   * and look like {@code macosx10.9}, {@code iphoneos8.0}, {@code iphonesimulator8.0},
   * etc.
   */
  public static ImmutableMap<String, AppleSdkPaths> discoverAppleSdkPaths(Path xcodeDir)
      throws IOException {
    LOG.debug("Searching for Xcode platforms under %s", xcodeDir);

    ImmutableMap.Builder<String, AppleSdkPaths> appleSdkPathsBuilder = ImmutableMap.builder();
    try (DirectoryStream<Path> platformStream = Files.newDirectoryStream(
             xcodeDir.resolve("Platforms"),
             "*.platform")) {
      for (Path platformDir : platformStream) {
        LOG.debug("Searching for Xcode SDKs under %s", platformDir);
        try (DirectoryStream<Path> sdkStream = Files.newDirectoryStream(
                 platformDir.resolve("Developer/SDKs"),
                 "*.sdk")) {
          for (Path sdkDir : sdkStream) {
            LOG.debug("Fetching SDK name for %s", sdkDir);
            if (Files.isSymbolicLink(sdkDir)) {
              continue;
            }

            Optional<String> sdkName = getSdkNameForPath(sdkDir);
            if (sdkName.isPresent()) {
              LOG.debug("Found SDK name %s", sdkName.get());
              ImmutableAppleSdkPaths xcodePaths = ImmutableAppleSdkPaths.builder()
                  .toolchainPath(xcodeDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
                  .platformDeveloperPath(platformDir.resolve("Developer"))
                  .sdkPath(sdkDir)
                  .build();
              appleSdkPathsBuilder.put(sdkName.get(), xcodePaths);
            }
          }
        }
      }
    }
    return appleSdkPathsBuilder.build();
  }

  private static Optional<String> getSdkNameForPath(Path sdkDir) throws IOException {
    try (InputStream sdkSettingsPlist = Files.newInputStream(sdkDir.resolve("SDKSettings.plist"));
         BufferedInputStream bufferedSdkSettingsPlist = new BufferedInputStream(sdkSettingsPlist)) {
      NSDictionary sdkSettings;
      try {
        sdkSettings = (NSDictionary) PropertyListParser.parse(bufferedSdkSettingsPlist);
      } catch (Exception e) {
        throw new IOException(e);
      }
      NSString canonicalName = (NSString) sdkSettings.objectForKey("CanonicalName");
      return Optional.of(canonicalName.toString());
    } catch (FileNotFoundException e) {
      LOG.error(e, "No SDKSettings.plist found under SDK path %s", sdkDir);
      return Optional.absent();
    }
  }
}
