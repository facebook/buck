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

package com.facebook.buck.apple.toolchain.impl;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.core.util.log.Logger;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

/** Utility class to discover the location of toolchains contained inside an Xcode installation. */
public class AppleToolchainDiscovery {

  private static final Logger LOG = Logger.get(AppleToolchainDiscovery.class);

  // Utility class; do not instantiate.
  private AppleToolchainDiscovery() {}

  /**
   * Given a path to an Xcode developer directory, walks through the toolchains and builds a map of
   * (identifier: path) pairs of the toolchains inside.
   */
  public static ImmutableMap<String, AppleToolchain> discoverAppleToolchains(
      Optional<Path> developerDir, ImmutableList<Path> extraDirs) throws IOException {
    ImmutableMap.Builder<String, AppleToolchain> toolchainIdentifiersToToolchainsBuilder =
        ImmutableMap.builder();

    HashSet<Path> toolchainPaths = new HashSet<Path>(extraDirs);
    if (developerDir.isPresent()) {
      Path toolchainsDir = developerDir.get().resolve("Toolchains");
      LOG.debug("Searching for Xcode toolchains under %s", toolchainsDir);
      toolchainPaths.add(toolchainsDir);
    }

    for (Path toolchains : toolchainPaths) {
      if (!Files.exists(toolchains)) {
        LOG.debug("Skipping toolchain search path %s that does not exist", toolchains);
        continue;
      }

      LOG.debug("Searching for Xcode toolchains in %s", toolchains);

      try (DirectoryStream<Path> toolchainStream =
          Files.newDirectoryStream(toolchains, "*.xctoolchain")) {
        for (Path toolchainPath : ImmutableSortedSet.copyOf(toolchainStream)) {
          LOG.debug("Getting identifier for for Xcode toolchain under %s", toolchainPath);
          addIdentifierForToolchain(toolchainPath, toolchainIdentifiersToToolchainsBuilder);
        }
      }
    }

    return toolchainIdentifiersToToolchainsBuilder.build();
  }

  private static void addIdentifierForToolchain(
      Path toolchainDir, ImmutableMap.Builder<String, AppleToolchain> identifierToToolchainBuilder)
      throws IOException {

    boolean addedToolchain = false;
    String[] potentialPlistNames = {"ToolchainInfo.plist", "Info.plist"};
    for (String plistName : potentialPlistNames) {
      try {
        Optional<AppleToolchain> toolchain = toolchainFromPlist(toolchainDir, plistName);
        if (toolchain.isPresent()) {
          identifierToToolchainBuilder.put(toolchain.get().getIdentifier(), toolchain.get());
          addedToolchain = true;
          break;
        }
      } catch (FileNotFoundException | NoSuchFileException e) {
        LOG.debug("Loading toolchain from plist %s for %s failed", plistName, toolchainDir);
      }
    }

    if (!addedToolchain) {
      LOG.debug(
          "Failed to resolve info about toolchain %s from plist files %s",
          toolchainDir.toString(), Arrays.toString(potentialPlistNames));
    }
  }

  private static Optional<AppleToolchain> toolchainFromPlist(Path toolchainDir, String plistName)
      throws IOException {
    Path toolchainInfoPlistPath = toolchainDir.resolve(plistName);

    NSDictionary parsedToolchainInfoPlist;
    try (InputStream toolchainInfoPlist = Files.newInputStream(toolchainInfoPlistPath);
        BufferedInputStream bufferedToolchainInfoPlist =
            new BufferedInputStream(toolchainInfoPlist)) {
      parsedToolchainInfoPlist =
          (NSDictionary) PropertyListParser.parse(bufferedToolchainInfoPlist);
    } catch (PropertyListFormatException
        | ParseException
        | ParserConfigurationException
        | SAXException e) {
      LOG.error(e, "Failed to parse %s: %s, ignoring", plistName, toolchainInfoPlistPath);
      return Optional.empty();
    }

    NSObject identifierObject = null;
    String[] potentialIdentifierKeys = {"Identifier", "CFBundleIdentifier"};
    for (String identifierKey : potentialIdentifierKeys) {
      identifierObject = parsedToolchainInfoPlist.objectForKey(identifierKey);
      if (identifierObject != null) {
        break;
      }
    }

    if (identifierObject == null) {
      LOG.error("Identifier not found for toolchain path %s, ignoring", toolchainDir);
      return Optional.empty();
    }
    String identifier = identifierObject.toString();

    NSObject versionObject = parsedToolchainInfoPlist.objectForKey("DTSDKBuild");
    Optional<String> version =
        versionObject == null ? Optional.empty() : Optional.of(versionObject.toString());
    LOG.debug("Mapped SDK identifier %s to path %s", identifier, toolchainDir);

    AppleToolchain.Builder toolchainBuilder = AppleToolchain.builder();
    toolchainBuilder.setIdentifier(identifier);
    toolchainBuilder.setVersion(version);
    toolchainBuilder.setPath(toolchainDir);

    return Optional.of(toolchainBuilder.build());
  }
}
