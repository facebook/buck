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

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.PropertyListParser;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.ProjectFilesystem.CopySourceMode;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;

/**
 * Class to handle:
 * 1. Identifying the best {@code .mobileprovision} file to use based on the bundle ID and
 *    expiration date.
 * 2. Copying that file to the bundle root.
 * 3. Merging the entitlements specified by the app in its {@code Entitlements.plist}
 *    with those provided in the {@code .mobileprovision} file and writing out a new temporary
 *    file used for code-signing.
 */
public class ProvisioningProfileCopyStep implements Step {
  private static final String KEYCHAIN_ACCESS_GROUPS = "keychain-access-groups";
  private static final String APPLICATION_IDENTIFIER = "application-identifier";
  private static final Logger LOG = Logger.get(ProvisioningProfileCopyStep.class);

  private final ProjectFilesystem filesystem;
  private final Optional<Path> entitlementsPlist;
  private final Optional<String> provisioningProfileUUID;
  private final Path provisioningProfileDestination;
  private final Path signingEntitlementsTempPath;
  private final ImmutableSet<ProvisioningProfileMetadata> profiles;
  private final Path infoPlist;

  /**
   * @param infoPlist  Bundle relative path of the bundle's {@code Info.plist} file.
   * @param provisioningProfileUUID  Optional. If specified, override the {@code .mobileprovision}
   *                                 auto-detect and attempt to use {@code UUID.mobileprovision}.
   * @param entitlementsPlist        Optional. If specified, use the metadata in this
   *                                 {@code Entitlements.plist} file to determine app prefix.
   * @param profiles                 Set of {@link ProvisioningProfileMetadata} that are candidates.
   * @param provisioningProfileDestination  Where to copy the {@code .mobileprovision} file,
   *                                        normally the bundle root.
   * @param signingEntitlementsTempPath     Where to copy the code signing entitlements file,
   *                                        normally a scratch directory.
   */
  public ProvisioningProfileCopyStep(
      ProjectFilesystem filesystem,
      Path infoPlist,
      Optional<String> provisioningProfileUUID,
      Optional<Path> entitlementsPlist,
      ImmutableSet<ProvisioningProfileMetadata> profiles,
      Path provisioningProfileDestination,
      Path signingEntitlementsTempPath) {
    this.filesystem = filesystem;
    this.provisioningProfileDestination = provisioningProfileDestination;
    this.infoPlist = infoPlist;
    this.provisioningProfileUUID = provisioningProfileUUID;
    this.entitlementsPlist = entitlementsPlist;
    this.profiles = profiles;
    this.signingEntitlementsTempPath = signingEntitlementsTempPath;
  }

  public static ImmutableSet<ProvisioningProfileMetadata> findProfilesInPath(Path searchPath)
   throws InterruptedException {
    final ImmutableSet.Builder<ProvisioningProfileMetadata> profilesBuilder =
        ImmutableSet.builder();
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
        throw ((InterruptedException) e.getCause());
      }
      LOG.error(e, "Error while searching for mobileprovision files");
    }

    return profilesBuilder.build();
  }


  // If multiple valid ones, find the one which matches the most specifically.  I.e.,
  // XXXXXXXXXX.com.example.* will match over XXXXXXXXXX.* for com.example.TestApp
  // TODO(user): Account for differences between development and distribution certificates.
  public static Optional<ProvisioningProfileMetadata> getBestProvisioningProfile(
      ImmutableSet<ProvisioningProfileMetadata> profiles,
      String bundleID,
      Optional<String> provisioningProfileUUID,
      Optional<String> prefix) {
    int bestMatchLength = -1;
    Optional<ProvisioningProfileMetadata> bestMatch =
        Optional.<ProvisioningProfileMetadata>absent();

    for (ProvisioningProfileMetadata profile : profiles) {
      if (provisioningProfileUUID.isPresent() &&
          profile.getUUID().equals(provisioningProfileUUID.get())) {
        return Optional.<ProvisioningProfileMetadata>of(profile);
      }

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

          if (match && profileBundleID.length() > bestMatchLength) {
            bestMatchLength = profileBundleID.length();
            bestMatch = Optional.<ProvisioningProfileMetadata>of(profile);
          }
        }
      }
    }

    LOG.debug("Found provisioning profile " + bestMatch.toString());
    return bestMatch;
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {

    final String bundleID;
    try {
      bundleID = AppleInfoPlistParsing.getBundleIdFromPlistStream(
          filesystem.getInputStreamForRelativePath(infoPlist)
      ).get();
    } catch (IOException e) {
      throw new HumanReadableException("Unable to get bundle ID from info.plist: " + infoPlist);
    }

    // What to look for in the provisioning profile.
    final Optional<String> prefix;          // e.g. ABCDE12345
    if (entitlementsPlist.isPresent()) {
      NSDictionary entitlementsPlistDict;
      try {
        entitlementsPlistDict =
            (NSDictionary) PropertyListParser.parse(entitlementsPlist.get().toFile());

      } catch (Exception e) {
        throw new HumanReadableException("Malformed entitlement .plist: " +
            entitlementsPlist.get());
      }

      try {
        String appID = ((NSArray) entitlementsPlistDict.get("keychain-access-groups"))
            .objectAtIndex(0).toString();
        prefix = Optional.of(ProvisioningProfileMetadata.splitAppID(appID).getFirst());
      } catch (Exception e) {
        throw new HumanReadableException(
            "Malformed entitlement .plist (missing keychain-access-groups): " +
                entitlementsPlist.get());
      }
    } else {
      prefix = Optional.<String>absent();
    }

    Optional<ProvisioningProfileMetadata> bestProfile = getBestProvisioningProfile(profiles,
        bundleID, provisioningProfileUUID, prefix);

    if (!bestProfile.isPresent()) {
      throw new HumanReadableException("No valid non-expired provisioning profiles match for " +
        prefix + "." + bundleID);
    }

    Path provisioningProfileSource = bestProfile.get().getProfilePath().get();

    // Copy the actual .mobileprovision.
    try {
      filesystem.copy(
          provisioningProfileSource,
          provisioningProfileDestination,
          CopySourceMode.FILE);
    } catch (IOException e) {
      context.logError(e, "Failed when trying to copy: %s", getDescription(context));
      return 1;
    }

    // Merge tne entitlements with the profile, and write out.
    if (entitlementsPlist.isPresent()) {
      return (new PlistProcessStep(
          filesystem,
          entitlementsPlist.get(),
          signingEntitlementsTempPath,
          bestProfile.get().getEntitlements(),
          ImmutableMap.<String, NSObject>of(),
          PlistProcessStep.OutputFormat.XML)).execute(context);
    } else {
      // No entitlements.plist explicitly specified; write out the minimal entitlements needed.
      String appID = bestProfile.get().getAppID().getFirst() + "." + bundleID;
      NSDictionary entitlements = new NSDictionary();
      entitlements.putAll(bestProfile.get().getEntitlements());
      entitlements.put(APPLICATION_IDENTIFIER, appID);
      entitlements.put(KEYCHAIN_ACCESS_GROUPS, new String[]{appID});
      return (new WriteFileStep(
          filesystem,
          entitlements.toXMLPropertyList(),
          signingEntitlementsTempPath,
          /* executable */ false)).execute(context);
    }
  }

  @Override
  public String getShortName() {
    return "provisioning-profile-copy";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("provisioning-profile-copy %s",
        provisioningProfileDestination);
  }
}
