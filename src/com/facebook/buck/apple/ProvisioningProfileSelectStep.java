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
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.apple.toolchain.ProvisioningProfileMetadata;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.BuildStepResultHolder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Optional;
import java.util.function.Supplier;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

/**
 * Class which helps to identify the best {@code .mobileprovision} file to use based on the bundle
 * ID and expiration date.
 */
public class ProvisioningProfileSelectStep implements Step {

  private static final String BUNDLE_ID = "bundle-id";
  private static final String PROFILE_UUID = "provisioning-profile-uuid";
  private static final String PROFILE_FILENAME = "provisioning-profile-file";
  private static final String TEAM_IDENTIFIER = "team-identifier";
  private static final String ENTITLEMENTS = "entitlements";

  private final ProjectFilesystem filesystem;
  private final Path infoPlist;
  private final ApplePlatform platform;
  private final Optional<Path> entitlementsPlist;
  private final Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier;
  private final ProvisioningProfileStore provisioningProfileStore;
  private final Optional<Path> dryRunResultsPath;
  private final BuildStepResultHolder<ProvisioningProfileMetadata> profileSelectionResult;

  private static final Logger LOG = Logger.get(ProvisioningProfileSelectStep.class);

  /**
   * @param infoPlist Bundle relative path to {@code Info.plist} file.
   * @param platform Apple platform the bundle is built for.
   * @param entitlementsPlist If specified, use the metadata in this {@code Entitlements.plist} file
   *     to determine app prefix.
   * @param provisioningProfileStore Known provisioning profiles to choose from.
   * @param dryRunResultsPath If set, flags that code signing is run in a dry mode, in that case the
   *     step will output a plist into this path.
   *     <p>If a suitable profile was found, contents of this file will contain metadata on the
   *     provisioning profile selected.
   *     <p>If no suitable profile was found, contents of this file this will contain the bundle ID
   *     and entitlements needed in a profile (in lieu of throwing an exception).
   * @param profileSelectionResult Output parameter which will contain metadata of the suitable
   *     profile if found during this step.
   */
  public ProvisioningProfileSelectStep(
      ProjectFilesystem filesystem,
      Path infoPlist,
      ApplePlatform platform,
      Optional<Path> entitlementsPlist,
      Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier,
      ProvisioningProfileStore provisioningProfileStore,
      Optional<Path> dryRunResultsPath,
      BuildStepResultHolder<ProvisioningProfileMetadata> profileSelectionResult) {
    this.filesystem = filesystem;
    this.infoPlist = infoPlist;
    this.platform = platform;
    this.entitlementsPlist = entitlementsPlist;
    this.codeSignIdentitiesSupplier = codeSignIdentitiesSupplier;
    this.provisioningProfileStore = provisioningProfileStore;
    this.dryRunResultsPath = dryRunResultsPath;
    this.profileSelectionResult = profileSelectionResult;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
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
    StringBuffer diagnosticsBuffer = new StringBuffer();
    Optional<ProvisioningProfileMetadata> bestProfile =
        provisioningProfileStore.getBestProvisioningProfile(
            bundleID, platform, entitlements, identities, diagnosticsBuffer);

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

    bestProfile.ifPresent(profileSelectionResult::setValue);

    if (!bestProfile.isPresent()) {
      String diagnostics = diagnosticsBuffer.toString();
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

    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "provisioning-profile-select";
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return "provisioning-profile-select";
  }
}
