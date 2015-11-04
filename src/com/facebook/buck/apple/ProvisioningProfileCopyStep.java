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
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.io.IOException;
import java.nio.file.Path;

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

  private final ProjectFilesystem filesystem;
  private final Optional<Path> entitlementsPlist;
  private final Optional<String> provisioningProfileUUID;
  private final Path provisioningProfileDestination;
  private final Path signingEntitlementsTempPath;
  private final ProvisioningProfileStore provisioningProfileStore;
  private final Path infoPlist;
  private final SettableFuture<ProvisioningProfileMetadata> selectedProvisioningProfileFuture =
      SettableFuture.create();

  /**
   * @param infoPlist  Bundle relative path of the bundle's {@code Info.plist} file.
   * @param provisioningProfileUUID  Optional. If specified, override the {@code .mobileprovision}
   *                                 auto-detect and attempt to use {@code UUID.mobileprovision}.
   * @param entitlementsPlist        Optional. If specified, use the metadata in this
   *                                 {@code Entitlements.plist} file to determine app prefix.
   * @param provisioningProfileStore  Known provisioning profiles to choose from.
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
      ProvisioningProfileStore provisioningProfileStore,
      Path provisioningProfileDestination,
      Path signingEntitlementsTempPath) {
    this.filesystem = filesystem;
    this.provisioningProfileDestination = provisioningProfileDestination;
    this.infoPlist = infoPlist;
    this.provisioningProfileUUID = provisioningProfileUUID;
    this.entitlementsPlist = entitlementsPlist;
    this.provisioningProfileStore = provisioningProfileStore;
    this.signingEntitlementsTempPath = signingEntitlementsTempPath;
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

      } catch (IOException e) {
        throw new HumanReadableException("Unable to find entitlement .plist: " +
            entitlementsPlist.get());
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

    Optional<ProvisioningProfileMetadata> bestProfile =
        provisioningProfileUUID.isPresent()
            ? provisioningProfileStore.getProvisioningProfileByUUID(provisioningProfileUUID.get())
            : provisioningProfileStore.getBestProvisioningProfile(bundleID, prefix);

    if (!bestProfile.isPresent()) {
      throw new HumanReadableException("No valid non-expired provisioning profiles match for " +
        prefix.or("*") + "." + bundleID);
    }

    selectedProvisioningProfileFuture.set(bestProfile.get());
    Path provisioningProfileSource = bestProfile.get().getProfilePath();

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

  /**
   * Returns a future that's populated once the rule is executed.
   */
  public ListenableFuture<ProvisioningProfileMetadata> getSelectedProvisioningProfileFuture() {
    return selectedProvisioningProfileFuture;
  }
}
