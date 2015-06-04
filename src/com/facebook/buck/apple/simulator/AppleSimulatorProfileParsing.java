/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.apple.simulator;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSNumber;
import com.dd.plist.PropertyListParser;

import com.facebook.buck.log.Logger;

import com.google.common.base.Optional;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Utility class to parse profile.plist from an iPhone simulator platform.
 */
public class AppleSimulatorProfileParsing {

  private static final Logger LOG = Logger.get(AppleSimulatorProfileParsing.class);

  // Utility class, do not instantiate.
  private AppleSimulatorProfileParsing() { }

  public static Optional<AppleSimulatorProfile> parseProfilePlistStream(InputStream inputStream)
    throws IOException {
    NSDictionary profile;
    try (BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream)) {
      try {
        profile = (NSDictionary) PropertyListParser.parse(bufferedInputStream);
      } catch (Exception e) {
        throw new IOException(e);
      }
    }

    NSObject supportedProductFamilyIDsObject = profile.objectForKey("supportedProductFamilyIDs");
    if (!(supportedProductFamilyIDsObject instanceof NSArray)) {
      LOG.warn(
          "Invalid simulator profile.plist (supportedProductFamilyIDs missing or not an array)");
      return Optional.absent();
    }
    NSArray supportedProductFamilyIDs = (NSArray) supportedProductFamilyIDsObject;

    AppleSimulatorProfile.Builder profileBuilder = AppleSimulatorProfile.builder();
    for (NSObject supportedProductFamilyID : supportedProductFamilyIDs.getArray()) {
      if (supportedProductFamilyID instanceof NSNumber) {
        profileBuilder.addSupportedProductFamilyIDs(
            ((NSNumber) supportedProductFamilyID).intValue());
      } else {
        LOG.warn(
            "Invalid simulator profile.plist (supportedProductFamilyIDs contains non-number %s)",
            supportedProductFamilyID);
        return Optional.absent();
      }
    }

    NSObject supportedArchsObject = profile.objectForKey("supportedArchs");
    if (!(supportedArchsObject instanceof NSArray)) {
      LOG.warn("Invalid simulator profile.plist (supportedArchs missing or not an array)");
      return Optional.absent();
    }
    NSArray supportedArchs = (NSArray) supportedArchsObject;
    for (NSObject supportedArch : supportedArchs.getArray()) {
      profileBuilder.addSupportedArchitectures(supportedArch.toString());
    }

    return Optional.of(profileBuilder.build());
  }
}
