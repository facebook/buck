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

import com.dd.plist.NSObject;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;
import java.util.Map.Entry;

/**
 * A collection of provisioning profiles.
 */
public class ProvisioningProfileStore implements RuleKeyAppendable {
  public static final Optional<ImmutableMap<String, NSObject>> MATCH_ANY_ENTITLEMENT =
      Optional.<ImmutableMap<String, NSObject>>absent();

  private static final Logger LOG = Logger.get(ProvisioningProfileStore.class);
  private final Supplier<ImmutableList<ProvisioningProfileMetadata>>
      provisioningProfilesSupplier;

  private ProvisioningProfileStore(
      Supplier<ImmutableList<ProvisioningProfileMetadata>> provisioningProfilesSupplier) {
    this.provisioningProfilesSupplier = provisioningProfilesSupplier;
  }

  public ImmutableList<ProvisioningProfileMetadata> getProvisioningProfiles() {
    return provisioningProfilesSupplier.get();
  }

  public Optional<ProvisioningProfileMetadata> getProvisioningProfileByUUID(
      String provisioningProfileUUID) {
    for (ProvisioningProfileMetadata profile : getProvisioningProfiles()) {
      if (profile.getUUID().equals(provisioningProfileUUID)) {
        return Optional.of(profile);
      }
    }
    return Optional.absent();
  }

  // If multiple valid ones, find the one which matches the most specifically.  I.e.,
  // XXXXXXXXXX.com.example.* will match over XXXXXXXXXX.* for com.example.TestApp
  public Optional<ProvisioningProfileMetadata> getBestProvisioningProfile(
      String bundleID,
      Optional<ImmutableMap<String, NSObject>> entitlements) {
    final Optional<String> prefix;
    if (entitlements.isPresent()) {
      prefix = Optional.of(ProvisioningProfileMetadata.prefixFromEntitlements(entitlements.get()));
    } else {
      prefix = Optional.<String>absent();
    }

    int bestMatchLength = -1;
    Optional<ProvisioningProfileMetadata> bestMatch = Optional.absent();

    for (ProvisioningProfileMetadata profile : getProvisioningProfiles()) {
      if (profile.getExpirationDate().after(new Date())) {
        Pair<String, String> appID = profile.getAppID();

        LOG.debug("Looking at provisioning profile " + profile.getUUID() + "," + appID.toString());

        if (!prefix.isPresent() || prefix.get().equals(appID.getFirst())) {
          String profileBundleID = appID.getSecond();
          boolean match;
          if (profileBundleID.endsWith("*")) {
            // Chop the ending * if wildcard.
            profileBundleID =
                profileBundleID.substring(0, profileBundleID.length() - 1);
            match = bundleID.startsWith(profileBundleID);
          } else {
            match = (bundleID.equals(profileBundleID));
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
              if (!(entry.getKey().equals("keychain-access-groups") ||
                  entry.getKey().equals("application-identifier") ||
                  entry.getValue().equals(profileEntitlements.get(entry.getKey())))) {
                match = false;
                break;
              }
            }
          }

          if (match && profileBundleID.length() > bestMatchLength) {
            bestMatchLength = profileBundleID.length();
            bestMatch = Optional.of(profile);
          }
        }
      }
    }

    LOG.debug("Found provisioning profile " + bestMatch.toString());
    return bestMatch;
  }

  // TODO(yiding): remove this once the precise provisioning profile can be determined.
  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder.setReflectively("provisioning-profile-store", getProvisioningProfiles());
  }

  public static ProvisioningProfileStore fromSearchPath(final Path searchPath) {
    return new ProvisioningProfileStore(Suppliers.memoize(
        new Supplier<ImmutableList<ProvisioningProfileMetadata>>() {
          @Override
          public ImmutableList<ProvisioningProfileMetadata> get() {
            final ImmutableList.Builder<ProvisioningProfileMetadata> profilesBuilder =
                ImmutableList.builder();
            try {
              Files.walkFileTree(
                  searchPath.toAbsolutePath(), new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                      if (file.toString().endsWith(".mobileprovision")) {
                        try {
                          ProvisioningProfileMetadata profile =
                              ProvisioningProfileMetadata.fromProvisioningProfilePath(file);
                          profilesBuilder.add(profile);
                        } catch (IOException | IllegalArgumentException e) {
                          LOG.error(e, "Ignoring invalid or malformed .mobileprovision file");
                        } catch (InterruptedException e) {
                          throw new IOException(e);
                        }
                      }

                      return FileVisitResult.CONTINUE;
                    }
                  });
            } catch (IOException e) {
              if (e.getCause() instanceof InterruptedException) {
                LOG.error(e, "Interrupted while searching for mobileprovision files");
              } else {
                LOG.error(e, "Error while searching for mobileprovision files");
              }
            }
            return profilesBuilder.build();
          }
        }
    ));
  }

  public static ProvisioningProfileStore fromProvisioningProfiles(
      Iterable<ProvisioningProfileMetadata> profiles) {
    return new ProvisioningProfileStore(Suppliers.ofInstance(ImmutableList.copyOf(profiles)));
  }
}
