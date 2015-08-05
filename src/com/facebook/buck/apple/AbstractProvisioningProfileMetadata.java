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

package com.facebook.buck.apple;

import com.dd.plist.NSDate;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;

import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;

import org.immutables.value.Value;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Metadata contained in a provisioning profile (.mobileprovision).
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractProvisioningProfileMetadata implements RuleKeyAppendable {
  private static final Pattern BUNDLE_ID_PATTERN = Pattern.compile("^([A-Z0-9]{10,10})\\.(.+)$");
  private static final Pattern PLIST_XML_PATTERN = Pattern.compile("((?s)<\\?xml.*</plist>)");

  /** Returns a (prefix, identifier) pair which the profile is valid for;
   *  e.g. (ABCDE12345, com.example.TestApp) or (ABCDE12345, *)
   */
  public abstract Pair<String, String> getAppID();

  public abstract Date getExpirationDate();

  public abstract String getUUID();

  public abstract Optional<Path> getProfilePath();

  /** Returns key/value pairs of the "Entitlements" dictionary of the embedded plist.
   */
  public abstract ImmutableMap<String, NSObject> getEntitlements();

  /** Takes a application identifier and splits it into prefix and bundle ID.
   *
   *  Prefix is always a ten-character alphanumeric sequence.
   *  Bundle ID may be a fully-qualified name or a wildcard ending in *.
   */
  public static Pair<String, String> splitAppID(String appID) throws Exception {
    Matcher matcher = BUNDLE_ID_PATTERN.matcher(appID);
    if (matcher.find()) {
      String prefix = matcher.group(1);
      String bundleID = matcher.group(2);

      return new Pair<>(prefix, bundleID);
    } else {
      throw new IllegalArgumentException("Malformed app ID: " + appID);
    }
  }

  public static ProvisioningProfileMetadata fromProvisioningProfilePath(Path profilePath)
      throws IOException {
    String fileAsString = Files.toString(profilePath.toFile(), Charsets.UTF_8);
    Matcher matcher = PLIST_XML_PATTERN.matcher(fileAsString);
    if (matcher.find()) {
      fileAsString = matcher.group();
    } else {
      throw new IllegalArgumentException(
          "Malformed .mobileprovision file (could not find embedded plist)");
    }

    try {
      NSDictionary plist = (NSDictionary) PropertyListParser.parse(fileAsString.getBytes());
      Date expirationDate = ((NSDate) plist.get("ExpirationDate")).getDate();
      String uuid = ((NSString) plist.get("UUID")).getContent();
      ImmutableMap.Builder<String, NSObject> builder =
          ImmutableMap.<String, NSObject>builder();
      NSDictionary entitlements = ((NSDictionary) plist.get("Entitlements"));
      for (String key : entitlements.keySet()) {
        builder = builder.put(key, entitlements.objectForKey(key));
      }
      String appID = entitlements.get("application-identifier").toString();

      return ProvisioningProfileMetadata.builder()
          .setAppID(ProvisioningProfileMetadata.splitAppID(appID))
          .setExpirationDate(expirationDate)
          .setUUID(uuid)
          .setProfilePath(Optional.<Path>of(profilePath))
          .setEntitlements(builder.build())
          .build();
    } catch (Exception e) {
      throw new IllegalArgumentException("Malformed embedded plist: " + e);
    }
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    return builder.setReflectively("provisioning-profile-uuid", getUUID());
  }
}
