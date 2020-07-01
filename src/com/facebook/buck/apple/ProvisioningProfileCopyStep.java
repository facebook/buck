/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.facebook.buck.apple.toolchain.ProvisioningProfileMetadata;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Class to handle: 1. Copying selected provisioning profile file to the bundle root. 2. Merging the
 * entitlements specified by the app in its {@code Entitlements.plist} with those provided in the
 * {@code .mobileprovision} file and writing out a new temporary file used for code-signing.
 */
class ProvisioningProfileCopyStep implements Step {
  private static final String KEYCHAIN_ACCESS_GROUPS = "keychain-access-groups";
  private static final String APPLICATION_IDENTIFIER = "application-identifier";

  private final ProjectFilesystem filesystem;
  private final Optional<Path> entitlementsPlist;
  private final Path provisioningProfileDestination;
  private final Path signingEntitlementsTempPath;
  private final Path infoPlist;
  private final boolean isDryRun;
  private final Supplier<ProvisioningProfileMetadata> selectedProfile;

  private static final Logger LOG = Logger.get(ProvisioningProfileCopyStep.class);

  /**
   * @param infoPlist Bundle relative path of the bundle's {@code Info.plist} file.
   * @param entitlementsPlist Optional. If specified, use the metadata in this {@code
   *     Entitlements.plist} file to determine app prefix.
   * @param provisioningProfileDestination Where to copy the {@code .mobileprovision} file, normally
   *     the bundle root.
   * @param signingEntitlementsTempPath Where to copy the code signing entitlements file, normally a
   *     scratch directory.
   * @param isDryRun If code signing is run in dry mode.
   */
  public ProvisioningProfileCopyStep(
      ProjectFilesystem filesystem,
      Path infoPlist,
      Optional<Path> entitlementsPlist,
      Path provisioningProfileDestination,
      Path signingEntitlementsTempPath,
      boolean isDryRun,
      Supplier<ProvisioningProfileMetadata> selectedProfile) {
    this.filesystem = filesystem;
    this.provisioningProfileDestination = provisioningProfileDestination;
    this.infoPlist = infoPlist;
    this.entitlementsPlist = entitlementsPlist;
    this.signingEntitlementsTempPath = signingEntitlementsTempPath;
    this.isDryRun = isDryRun;
    this.selectedProfile = selectedProfile;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context) throws IOException {
    String bundleID =
        AppleInfoPlistParsing.getBundleIdFromPlistStream(
                infoPlist, filesystem.getInputStreamForRelativePath(infoPlist))
            .get();

    Path provisioningProfileSource = selectedProfile.get().getProfilePath();

    // Copy the actual .mobileprovision.
    filesystem.copy(provisioningProfileSource, provisioningProfileDestination, CopySourceMode.FILE);

    // Add additional keys to Info.plist file
    StepExecutionResult updateInfoPlistResult =
        (new PlistProcessStep(
                filesystem,
                infoPlist,
                Optional.empty(),
                infoPlist,
                getInfoPlistAdditionalKeys(bundleID, selectedProfile.get()),
                ImmutableMap.of(),
                PlistProcessStep.OutputFormat.BINARY))
            .execute(context);
    if (updateInfoPlistResult != StepExecutionResults.SUCCESS) {
      String message = "An error ocurred when tried to add additional keys to Info.plist";
      if (isDryRun) {
        LOG.warn(message);
      } else {
        throw new HumanReadableException(message);
      }
    }

    // Merge the entitlements with the profile, and write out.
    if (entitlementsPlist.isPresent()) {
      return (new PlistProcessStep(
              filesystem,
              entitlementsPlist.get(),
              Optional.empty(),
              signingEntitlementsTempPath,
              selectedProfile.get().getMergeableEntitlements(),
              ImmutableMap.of(),
              PlistProcessStep.OutputFormat.XML))
          .execute(context);
    } else {
      // No entitlements.plist explicitly specified; write out the minimal entitlements needed.
      String appID = selectedProfile.get().getAppID().getFirst() + "." + bundleID;
      NSDictionary entitlementsPlist = new NSDictionary();
      entitlementsPlist.putAll(selectedProfile.get().getMergeableEntitlements());
      entitlementsPlist.put(APPLICATION_IDENTIFIER, appID);
      entitlementsPlist.put(KEYCHAIN_ACCESS_GROUPS, new String[] {appID});
      return (new WriteFileStep(
              filesystem,
              entitlementsPlist.toXMLPropertyList(),
              signingEntitlementsTempPath,
              /* executable */ false))
          .execute(context);
    }
  }

  @Override
  public String getShortName() {
    return "provisioning-profile-copy";
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return String.format("provisioning-profile-copy %s", provisioningProfileDestination);
  }

  private ImmutableMap<String, NSObject> getInfoPlistAdditionalKeys(
      String bundleID, ProvisioningProfileMetadata bestProfile) throws IOException {
    ImmutableMap.Builder<String, NSObject> keys = ImmutableMap.builder();

    // Get bundle type and restrict additional keys based on it
    String bundleType =
        AppleInfoPlistParsing.getBundleTypeFromPlistStream(
                infoPlist, filesystem.getInputStreamForRelativePath(infoPlist))
            .get();
    if ("APPL".equalsIgnoreCase(bundleType)) {
      // Skip additional keys for watchOS bundles (property keys whitelist)
      Optional<Boolean> isWatchOSApp =
          AppleInfoPlistParsing.isWatchOSAppFromPlistStream(
              infoPlist, filesystem.getInputStreamForRelativePath(infoPlist));
      if (!isWatchOSApp.isPresent() || !isWatchOSApp.get()) {
        // Construct AppID using the Provisioning Profile info (app prefix)
        String appID = bestProfile.getAppID().getFirst() + "." + bundleID;
        keys.put("ApplicationIdentifier", new NSString(appID));
      }
    }

    return keys.build();
  }
}
