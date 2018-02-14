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
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.toolchain.Toolchain;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** A collection of provisioning profiles. */
@Value.Immutable(builder = false, copy = false)
@BuckStyleImmutable
public abstract class AbstractProvisioningProfileStore implements RuleKeyAppendable, Toolchain {
  public static final Optional<ImmutableMap<String, NSObject>> MATCH_ANY_ENTITLEMENT =
      Optional.empty();
  public static final Optional<ImmutableList<CodeSignIdentity>> MATCH_ANY_IDENTITY =
      Optional.empty();

  public static final String DEFAULT_NAME = "apple-provisioning-profiles";

  private static final Logger LOG = Logger.get(ProvisioningProfileStore.class);

  private static final ImmutableSet<String> FORCE_INCLUDE_ENTITLEMENTS =
      ImmutableSet.of(
          "keychain-access-groups",
          "application-identifier",
          "com.apple.developer.associated-domains",
          "com.apple.developer.icloud-container-development-container-identifiers",
          "com.apple.developer.icloud-container-environment",
          "com.apple.developer.icloud-container-identifiers",
          "com.apple.developer.icloud-services",
          "com.apple.developer.ubiquity-container-identifiers",
          "com.apple.developer.ubiquity-kvstore-identifier");

  @Value.Parameter
  public abstract Supplier<ImmutableList<ProvisioningProfileMetadata>>
      getProvisioningProfilesSupplier();

  public ImmutableList<ProvisioningProfileMetadata> getProvisioningProfiles() {
    return getProvisioningProfilesSupplier().get();
  }

  public Optional<ProvisioningProfileMetadata> getProvisioningProfileByUUID(
      String provisioningProfileUUID) {
    for (ProvisioningProfileMetadata profile : getProvisioningProfiles()) {
      if (profile.getUUID().equals(provisioningProfileUUID)) {
        return Optional.of(profile);
      }
    }
    return Optional.empty();
  }

  private static boolean matchesOrArrayIsSubsetOf(@Nullable NSObject lhs, @Nullable NSObject rhs) {
    if (lhs == null) {
      return (rhs == null);
    }

    if (lhs instanceof NSArray && rhs instanceof NSArray) {
      List<NSObject> lhsList = Arrays.asList(((NSArray) lhs).getArray());
      List<NSObject> rhsList = Arrays.asList(((NSArray) rhs).getArray());
      return rhsList.containsAll(lhsList);
    }

    return lhs.equals(rhs);
  }

  // If multiple valid ones, find the one which matches the most specifically.  I.e.,
  // XXXXXXXXXX.com.example.* will match over XXXXXXXXXX.* for com.example.TestApp
  public Optional<ProvisioningProfileMetadata> getBestProvisioningProfile(
      String bundleID,
      ApplePlatform platform,
      Optional<ImmutableMap<String, NSObject>> entitlements,
      Optional<? extends Iterable<CodeSignIdentity>> identities) {
    final Optional<String> prefix;
    if (entitlements.isPresent()) {
      prefix = ProvisioningProfileMetadata.prefixFromEntitlements(entitlements.get());
    } else {
      prefix = Optional.empty();
    }

    int bestMatchLength = -1;
    Optional<ProvisioningProfileMetadata> bestMatch = Optional.empty();

    for (ProvisioningProfileMetadata profile : getProvisioningProfiles()) {
      if (profile.getExpirationDate().after(new Date())) {
        Pair<String, String> appID = profile.getAppID();

        LOG.debug("Looking at provisioning profile " + profile.getUUID() + "," + appID.toString());

        if (!prefix.isPresent() || prefix.get().equals(appID.getFirst())) {
          String profileBundleID = appID.getSecond();
          boolean match;
          if (profileBundleID.endsWith("*")) {
            // Chop the ending * if wildcard.
            profileBundleID = profileBundleID.substring(0, profileBundleID.length() - 1);
            match = bundleID.startsWith(profileBundleID);
          } else {
            match = (bundleID.equals(profileBundleID));
          }

          if (!match) {
            LOG.debug(
                "Ignoring non-matching ID for profile "
                    + profile.getUUID()
                    + ".  Expected: "
                    + profileBundleID
                    + ", actual: "
                    + bundleID);
            continue;
          }

          Optional<String> platformName = platform.getProvisioningProfileName();
          if (platformName.isPresent() && !profile.getPlatforms().contains(platformName.get())) {
            LOG.debug(
                "Ignoring incompatible platform "
                    + platformName.get()
                    + " for profile "
                    + profile.getUUID());
            continue;
          }

          // Match against other keys of the entitlements.  Otherwise, we could potentially select
          // a profile that doesn't have all the needed entitlements, causing a error when
          // installing to device.
          //
          // For example: get-task-allow, aps-environment, etc.
          if (entitlements.isPresent()) {
            ImmutableMap<String, NSObject> entitlementsDict = entitlements.get();
            ImmutableMap<String, NSObject> profileEntitlements = profile.getEntitlements();
            for (Entry<String, NSObject> entry : entitlementsDict.entrySet()) {
              NSObject profileEntitlement = profileEntitlements.get(entry.getKey());
              if (!(FORCE_INCLUDE_ENTITLEMENTS.contains(entry.getKey())
                  || matchesOrArrayIsSubsetOf(entry.getValue(), profileEntitlement))) {
                match = false;
                LOG.debug(
                    "Ignoring profile "
                        + profile.getUUID()
                        + " with mismatched entitlement "
                        + entry.getKey()
                        + "; value is "
                        + profileEntitlement
                        + " but expected "
                        + entry.getValue());
                break;
              }
            }
          }

          // Reject any certificate which we know we can't sign with the supplied identities.
          ImmutableSet<HashCode> validFingerprints = profile.getDeveloperCertificateFingerprints();
          if (match && identities.isPresent() && !validFingerprints.isEmpty()) {
            match = false;
            for (CodeSignIdentity identity : identities.get()) {
              Optional<HashCode> fingerprint = identity.getFingerprint();
              if (fingerprint.isPresent() && validFingerprints.contains(fingerprint.get())) {
                match = true;
                break;
              }
            }

            if (!match) {
              LOG.debug(
                  "Ignoring profile "
                      + profile.getUUID()
                      + " because it can't be signed with any valid identity in the current keychain.");
              continue;
            }
          }

          if (match && profileBundleID.length() > bestMatchLength) {
            bestMatchLength = profileBundleID.length();
            bestMatch = Optional.of(profile);
          }
        }
      } else {
        LOG.debug("Ignoring expired profile " + profile.getUUID());
      }
    }

    LOG.debug("Found provisioning profile " + bestMatch.toString());
    return bestMatch;
  }

  // TODO(yiding): remove this once the precise provisioning profile can be determined.
  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("provisioning-profile-store", getProvisioningProfiles());
  }

  public static ProvisioningProfileStore empty() {
    return ProvisioningProfileStore.of(Suppliers.ofInstance(ImmutableList.of()));
  }
}
