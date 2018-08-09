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
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.facebook.buck.core.rulekey.RuleKeyAppendable;
import com.facebook.buck.core.rulekey.RuleKeyObjectSink;
import com.facebook.buck.core.toolchain.Toolchain;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Joiner;
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

  private String getStringFromNSObject(@Nullable NSObject obj) {
    if (obj == null) {
      return "(not set)" + System.lineSeparator();
    } else if (obj instanceof NSArray) {
      return ((NSArray) obj).toASCIIPropertyList();
    } else if (obj instanceof NSDictionary) {
      return ((NSDictionary) obj).toASCIIPropertyList();
    } else {
      return obj.toString() + System.lineSeparator();
    }
  }

  // If multiple valid ones, find the one which matches the most specifically.  I.e.,
  // XXXXXXXXXX.com.example.* will match over XXXXXXXXXX.* for com.example.TestApp
  public Optional<ProvisioningProfileMetadata> getBestProvisioningProfile(
      String bundleID,
      ApplePlatform platform,
      Optional<ImmutableMap<String, NSObject>> entitlements,
      Optional<? extends Iterable<CodeSignIdentity>> identities,
      StringBuffer diagnosticsBuffer) {
    Optional<String> prefix;
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    if (entitlements.isPresent()) {
      prefix = ProvisioningProfileMetadata.prefixFromEntitlements(entitlements.get());
    } else {
      prefix = Optional.empty();
    }

    int bestMatchLength = -1;
    Optional<ProvisioningProfileMetadata> bestMatch = Optional.empty();

    lines.add(String.format("Looking for a provisioning profile for bundle ID %s", bundleID));

    boolean atLeastOneMatch = false;
    for (ProvisioningProfileMetadata profile : getProvisioningProfiles()) {
      Pair<String, String> appID = profile.getAppID();

      LOG.debug("Looking at provisioning profile " + profile.getUUID() + "," + appID);

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

        atLeastOneMatch = true;
        if (!profile.getExpirationDate().after(new Date())) {
          String message =
              "Ignoring expired profile " + profile.getUUID() + ": " + profile.getExpirationDate();
          LOG.debug(message);
          lines.add(message);
          continue;
        }

        Optional<String> platformName = platform.getProvisioningProfileName();
        if (platformName.isPresent() && !profile.getPlatforms().contains(platformName.get())) {
          String message =
              "Ignoring incompatible platform "
                  + platformName.get()
                  + " for profile "
                  + profile.getUUID();
          LOG.debug(message);
          lines.add(message);
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
              String profileEntitlementString = getStringFromNSObject(profileEntitlement);
              String entryValueString = getStringFromNSObject(entry.getValue());
              String message =
                  "Profile "
                      + profile.getProfilePath().getFileName()
                      + " ("
                      + profile.getUUID()
                      + ") with bundleID "
                      + profile.getAppID().getSecond()
                      + " correctly matches. However there is a mismatched entitlement "
                      + entry.getKey()
                      + ";"
                      + System.lineSeparator()
                      + "value is: "
                      + profileEntitlementString
                      + "but expected: "
                      + entryValueString;
              LOG.debug(message);
              lines.add(message);
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
            String message =
                "Ignoring profile "
                    + profile.getUUID()
                    + " because it can't be signed with any valid identity in the current keychain.";
            LOG.debug(message);
            lines.add(message);
            continue;
          }
        }

        if (match && profileBundleID.length() > bestMatchLength) {
          bestMatchLength = profileBundleID.length();
          bestMatch = Optional.of(profile);
        }
      }
    }

    if (!atLeastOneMatch) {
      lines.add(
          String.format("No provisioning profile matching the bundle ID %s was found", bundleID));
    }

    LOG.debug("Found provisioning profile " + bestMatch);
    ImmutableList<String> diagnostics = lines.build();
    diagnosticsBuffer.append(Joiner.on("\n").join(diagnostics));
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
