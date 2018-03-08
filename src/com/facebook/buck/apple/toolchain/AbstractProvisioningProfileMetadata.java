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

package com.facebook.buck.apple.toolchain;

import com.dd.plist.NSArray;
import com.dd.plist.NSObject;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.Date;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.immutables.value.Value;

/** Metadata contained in a provisioning profile (.mobileprovision). */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractProvisioningProfileMetadata implements AddsToRuleKey {
  private static final Pattern BUNDLE_ID_PATTERN = Pattern.compile("^([A-Z0-9]{10,10})\\.(.+)$");

  /**
   * Returns a (prefix, identifier) pair for which the profile is valid.
   *
   * <p>e.g. (ABCDE12345, com.example.TestApp) or (ABCDE12345, *)
   */
  public abstract Pair<String, String> getAppID();

  public abstract Date getExpirationDate();

  @AddToRuleKey
  public abstract String getUUID();

  public abstract Path getProfilePath();

  /** The set of platforms the profile is valid for. */
  @Value.Default
  public ImmutableList<String> getPlatforms() {
    return ImmutableList.of(ApplePlatform.IPHONEOS.getProvisioningProfileName().get());
  }

  /** Key/value pairs of the "Entitlements" dictionary in the embedded plist. */
  public abstract ImmutableMap<String, NSObject> getEntitlements();

  /** SHA1 hashes of the certificates in the "DeveloperCertificates" section. */
  public abstract ImmutableSet<HashCode> getDeveloperCertificateFingerprints();

  /**
   * Takes a application identifier and splits it into prefix and bundle ID.
   *
   * <p>Prefix is always a ten-character alphanumeric sequence. Bundle ID may be a fully-qualified
   * name or a wildcard ending in *.
   */
  public static Pair<String, String> splitAppID(String appID) {
    Matcher matcher = BUNDLE_ID_PATTERN.matcher(appID);
    if (matcher.find()) {
      String prefix = matcher.group(1);
      String bundleID = matcher.group(2);

      return new Pair<>(prefix, bundleID);
    } else {
      throw new IllegalArgumentException("Malformed app ID: " + appID);
    }
  }

  /**
   * Takes an ImmutableMap representing an entitlements file, returns the application prefix if it
   * can be inferred from keys in the entitlement. Otherwise, it returns empty.
   */
  public static Optional<String> prefixFromEntitlements(
      ImmutableMap<String, NSObject> entitlements) {
    try {
      NSArray keychainAccessGroups = ((NSArray) entitlements.get("keychain-access-groups"));
      Preconditions.checkNotNull(keychainAccessGroups);
      String appID = keychainAccessGroups.objectAtIndex(0).toString();
      return Optional.of(splitAppID(appID).getFirst());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  public ImmutableMap<String, NSObject> getMergeableEntitlements() {
    ImmutableSet<String> includedKeys =
        ImmutableSet.of(
            "application-identifier",
            "beta-reports-active",
            "get-task-allow",
            "com.apple.developer.aps-environment",
            "com.apple.developer.team-identifier");

    ImmutableMap<String, NSObject> allEntitlements = getEntitlements();
    ImmutableMap.Builder<String, NSObject> filteredEntitlementsBuilder = ImmutableMap.builder();
    for (String key : allEntitlements.keySet()) {
      if (includedKeys.contains(key)) {
        filteredEntitlementsBuilder.put(key, allEntitlements.get(key));
      }
    }
    return filteredEntitlementsBuilder.build();
  }
}
