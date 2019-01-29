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

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.apple.toolchain.ProvisioningProfileMetadata;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Optional;
import java.util.function.Supplier;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

/**
 * Class to handle: 1. Identifying the best {@code .mobileprovision} file to use based on the bundle
 * ID and expiration date. 2. Copying that file to the bundle root. 3. Merging the entitlements
 * specified by the app in its {@code Entitlements.plist} with those provided in the {@code
 * .mobileprovision} file and writing out a new temporary file used for code-signing.
 */
class ProvisioningProfileCopyStep implements Step {
  private static final String KEYCHAIN_ACCESS_GROUPS = "keychain-access-groups";
  private static final String APPLICATION_IDENTIFIER = "application-identifier";

  private static final String BUNDLE_ID = "bundle-id";
  private static final String PROFILE_UUID = "provisioning-profile-uuid";
  private static final String PROFILE_FILENAME = "provisioning-profile-file";
  private static final String TEAM_IDENTIFIER = "team-identifier";
  private static final String ENTITLEMENTS = "entitlements";

  private final ProjectFilesystem filesystem;
  private final ApplePlatform platform;
  private final Optional<Path> entitlementsPlist;
  private final Optional<String> provisioningProfileUUID;
  private final Path provisioningProfileDestination;
  private final Path signingEntitlementsTempPath;
  private final ProvisioningProfileStore provisioningProfileStore;
  private final Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier;
  private final Path infoPlist;
  private final SettableFuture<Optional<ProvisioningProfileMetadata>>
      selectedProvisioningProfileFuture = SettableFuture.create();
  private Optional<Path> dryRunResultsPath;

  private static final Logger LOG = Logger.get(ProvisioningProfileCopyStep.class);

  /**
   * @param infoPlist Bundle relative path of the bundle's {@code Info.plist} file.
   * @param provisioningProfileUUID Optional. If specified, override the {@code .mobileprovision}
   *     auto-detect and attempt to use {@code UUID.mobileprovision}.
   * @param entitlementsPlist Optional. If specified, use the metadata in this {@code
   *     Entitlements.plist} file to determine app prefix.
   * @param provisioningProfileStore Known provisioning profiles to choose from.
   * @param provisioningProfileDestination Where to copy the {@code .mobileprovision} file, normally
   *     the bundle root.
   * @param signingEntitlementsTempPath Where to copy the code signing entitlements file, normally a
   *     scratch directory.
   * @param dryRunResultsPath If set, will output a plist into this path with the results of this
   *     step.
   *     <p>If a suitable profile was found, this will contain metadata on the provisioning profile
   *     selected.
   *     <p>If no suitable profile was found, this will contain the bundle ID and entitlements
   *     needed in a profile (in lieu of throwing an exception.)
   */
  public ProvisioningProfileCopyStep(
      ProjectFilesystem filesystem,
      Path infoPlist,
      ApplePlatform platform,
      Optional<String> provisioningProfileUUID,
      Optional<Path> entitlementsPlist,
      ProvisioningProfileStore provisioningProfileStore,
      Path provisioningProfileDestination,
      Path signingEntitlementsTempPath,
      Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier,
      Optional<Path> dryRunResultsPath) {
    this.filesystem = filesystem;
    this.provisioningProfileDestination = provisioningProfileDestination;
    this.infoPlist = infoPlist;
    this.platform = platform;
    this.provisioningProfileUUID = provisioningProfileUUID;
    this.entitlementsPlist = entitlementsPlist;
    this.provisioningProfileStore = provisioningProfileStore;
    this.codeSignIdentitiesSupplier = codeSignIdentitiesSupplier;
    this.signingEntitlementsTempPath = signingEntitlementsTempPath;
    this.dryRunResultsPath = dryRunResultsPath;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    Optional<ImmutableMap<String, NSObject>> entitlements;
    String prefix;
    if (entitlementsPlist.isPresent()) {
      try {
        NSDictionary entitlementsPlistDict =
            (NSDictionary) PropertyListParser.parse(entitlementsPlist.get().toFile());
        entitlements = Optional.of(ImmutableMap.copyOf(entitlementsPlistDict.getHashMap()));
        prefix = ProvisioningProfileMetadata.prefixFromEntitlements(entitlements.get()).orElse("*");
      } catch (PropertyListFormatException
          | ParseException
          | ParserConfigurationException
          | SAXException
          | UnsupportedOperationException e) {
        throw new HumanReadableException(
            "Malformed entitlement .plist: " + entitlementsPlist.get());
      }
    } else {
      entitlements = ProvisioningProfileStore.MATCH_ANY_ENTITLEMENT;
      prefix = "*";
    }

    Optional<ImmutableList<CodeSignIdentity>> identities;
    if (!codeSignIdentitiesSupplier.get().isEmpty()) {
      identities = Optional.of(codeSignIdentitiesSupplier.get());
    } else {
      identities = ProvisioningProfileStore.MATCH_ANY_IDENTITY;
    }

    String bundleID =
        AppleInfoPlistParsing.getBundleIdFromPlistStream(
                infoPlist, filesystem.getInputStreamForRelativePath(infoPlist))
            .get();
    Optional<ProvisioningProfileMetadata> bestProfile;
    String diagnostics = "";
    if (provisioningProfileUUID.isPresent()) {
      bestProfile =
          provisioningProfileStore.getProvisioningProfileByUUID(provisioningProfileUUID.get());
      if (!bestProfile.isPresent()) {
        diagnostics =
            String.format(
                "A provisioning profile matching UUID %s was not found",
                provisioningProfileUUID.get());
      }
    } else {
      StringBuffer diagnosticsBuffer = new StringBuffer();
      bestProfile =
          provisioningProfileStore.getBestProvisioningProfile(
              bundleID, platform, entitlements, identities, diagnosticsBuffer);
      diagnostics = diagnosticsBuffer.toString();
    }

    if (dryRunResultsPath.isPresent()) {
      NSDictionary dryRunResult = new NSDictionary();
      dryRunResult.put(BUNDLE_ID, bundleID);
      dryRunResult.put(ENTITLEMENTS, entitlements.orElse(ImmutableMap.of()));
      if (bestProfile.isPresent()) {
        dryRunResult.put(PROFILE_UUID, bestProfile.get().getUUID());
        dryRunResult.put(
            PROFILE_FILENAME, bestProfile.get().getProfilePath().getFileName().toString());
        dryRunResult.put(
            TEAM_IDENTIFIER,
            bestProfile.get().getEntitlements().get("com.apple.developer.team-identifier"));
      }

      filesystem.writeContentsToPath(dryRunResult.toXMLPropertyList(), dryRunResultsPath.get());
    }

    selectedProvisioningProfileFuture.set(bestProfile);

    if (!bestProfile.isPresent()) {
      context.getBuckEventBus().post(ConsoleEvent.warning(diagnostics));

      String message =
          "No valid non-expired provisioning profiles match for " + prefix + "." + bundleID;
      if (dryRunResultsPath.isPresent()) {
        LOG.warn(message + "\n" + diagnostics);
        return StepExecutionResults.SUCCESS;
      } else {
        throw new HumanReadableException(message + "\n" + diagnostics);
      }
    }

    Path provisioningProfileSource = bestProfile.get().getProfilePath();

    // Copy the actual .mobileprovision.
    filesystem.copy(provisioningProfileSource, provisioningProfileDestination, CopySourceMode.FILE);

    // Merge the entitlements with the profile, and write out.
    if (entitlementsPlist.isPresent()) {
      return (new PlistProcessStep(
              filesystem,
              entitlementsPlist.get(),
              Optional.empty(),
              signingEntitlementsTempPath,
              bestProfile.get().getMergeableEntitlements(),
              ImmutableMap.of(),
              PlistProcessStep.OutputFormat.XML))
          .execute(context);
    } else {
      // No entitlements.plist explicitly specified; write out the minimal entitlements needed.
      String appID = bestProfile.get().getAppID().getFirst() + "." + bundleID;
      NSDictionary entitlementsPlist = new NSDictionary();
      entitlementsPlist.putAll(bestProfile.get().getMergeableEntitlements());
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
  public String getDescription(ExecutionContext context) {
    return String.format("provisioning-profile-copy %s", provisioningProfileDestination);
  }

  /** Returns a future that's populated once the rule is executed. */
  public ListenableFuture<Optional<ProvisioningProfileMetadata>>
      getSelectedProvisioningProfileFuture() {
    return selectedProvisioningProfileFuture;
  }
}
