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

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.apple.toolchain.ProvisioningProfileMetadata;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.BuildStepResultHolder;
import com.facebook.buck.step.ConditionalStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Prepares everything that is needed to run code signing: creates Info.plist ready to be copied
 * into the bundle, entitlements ready to be used during code signing, provisioning profile file
 * ready to be copied into the bundle, selects the code sign identity to be used and writes its
 * unique ID to the output file.
 */
public class AppleCodeSignPreparation extends ModernBuildRule<AppleCodeSignPreparation.Impl> {

  public static final Flavor FLAVOR = InternalFlavor.of("apple-code-sign-preparation");

  public AppleCodeSignPreparation(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ApplePlatform platform,
      SourcePath infoPlist,
      Optional<SourcePath> entitlements,
      ProvisioningProfileStore provisioningProfileStore,
      Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier,
      boolean isDryRun) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new AppleCodeSignPreparation.Impl(
            platform,
            infoPlist,
            entitlements,
            provisioningProfileStore,
            codeSignIdentitiesSupplier,
            isDryRun));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  /**
   * File might not be created even if the action finishes successfully, but only when code sign is
   * run in dry mode.
   */
  public SourcePath getSourcePathToProvisioningProfile() {
    return getSourcePath(getBuildable().provisioningProfileOutput);
  }

  public SourcePath getSourcePathToInfoPlistOutput() {
    return getSourcePath(getBuildable().infoPlistOutput);
  }

  /**
   * File might not be created even if the action finishes successfully, but only when code sign is
   * run in dry mode.
   */
  public SourcePath getSourcePathToEntitlementsOutput() {
    return getSourcePath(getBuildable().entitlementsOutput);
  }

  public Optional<SourcePath> getSourcePathToDryRunOutput() {
    return getBuildable().dryRunOutput.map(this::getSourcePath);
  }

  /** Internal buildable implementation */
  static class Impl implements Buildable {

    @AddToRuleKey private final ApplePlatform platform;
    @AddToRuleKey private final SourcePath infoPlist;
    @AddToRuleKey private final Optional<SourcePath> entitlements;
    @AddToRuleKey private final OutputPath output;
    @AddToRuleKey private final OutputPath provisioningProfileOutput;
    @AddToRuleKey private final OutputPath infoPlistOutput;
    @AddToRuleKey private final OutputPath entitlementsOutput;
    @AddToRuleKey private final Optional<OutputPath> dryRunOutput;
    @AddToRuleKey ProvisioningProfileStore provisioningProfileStore;

    @AddToRuleKey
    private final Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier;

    Impl(
        ApplePlatform platform,
        SourcePath infoPlist,
        Optional<SourcePath> entitlements,
        ProvisioningProfileStore provisioningProfileStore,
        Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier,
        boolean isDryRun) {
      this.platform = platform;
      this.infoPlist = infoPlist;
      this.entitlements = entitlements;
      output = new OutputPath("selected_identity_fingerprint");
      infoPlistOutput = new OutputPath("Info.plist");
      dryRunOutput =
          isDryRun ? Optional.of(new OutputPath("BUCK_pp_dry_run.plist")) : Optional.empty();
      entitlementsOutput = new OutputPath("Entitlements.plist");
      provisioningProfileOutput =
          new OutputPath(
              AppleProvisioningProfileUtilities.getProvisioningProfileFileNameInBundle(platform));
      this.provisioningProfileStore = provisioningProfileStore;
      this.codeSignIdentitiesSupplier = codeSignIdentitiesSupplier;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {

      ImmutableList.Builder<Step> stepsBuilder = new ImmutableList.Builder<>();

      Path outputInfoPlistPath = outputPathResolver.resolvePath(infoPlistOutput).getPath();
      {
        Path infoPlistPath =
            buildContext.getSourcePathResolver().getCellUnsafeRelPath(infoPlist).getPath();
        stepsBuilder.add(CopyStep.forFile(filesystem, infoPlistPath, outputInfoPlistPath));
      }

      Optional<Path> maybeEntitlementsPath =
          entitlements.map(
              sourcePath ->
                  buildContext.getSourcePathResolver().getAbsolutePath(sourcePath).getPath());
      BuildStepResultHolder<ProvisioningProfileMetadata> selectedProfile =
          new BuildStepResultHolder<>();
      {
        Optional<Path> dryRunResultPath =
            dryRunOutput.map(p -> outputPathResolver.resolvePath(p).getPath());
        stepsBuilder.add(
            new ProvisioningProfileSelectStep(
                filesystem,
                outputInfoPlistPath,
                platform,
                maybeEntitlementsPath,
                codeSignIdentitiesSupplier,
                provisioningProfileStore,
                dryRunResultPath,
                selectedProfile));
      }

      Supplier<Boolean> isProvisioningProfileSelected =
          () -> selectedProfile.getValue().isPresent();

      Supplier<ProvisioningProfileMetadata> selectedProfileSupplier =
          () -> {
            Preconditions.checkState(isProvisioningProfileSelected.get());
            //noinspection OptionalGetWithoutIsPresent
            return selectedProfile.getValue().get();
          };

      {
        Path outputProvisioningProfilePath =
            outputPathResolver.resolvePath(provisioningProfileOutput).getPath();
        Path outputEntitlementsPath = outputPathResolver.resolvePath(entitlementsOutput).getPath();

        ProvisioningProfileCopyStep provisioningProfileCopyStep =
            new ProvisioningProfileCopyStep(
                filesystem,
                outputInfoPlistPath,
                maybeEntitlementsPath,
                outputProvisioningProfilePath,
                outputEntitlementsPath,
                isDryRun(),
                selectedProfileSupplier);
        stepsBuilder.add(
            new ConditionalStep(isProvisioningProfileSelected, provisioningProfileCopyStep));
      }

      BuildStepResultHolder<CodeSignIdentity> selectedCodeSignIdentity =
          new BuildStepResultHolder<>();
      {
        CodeSignIdentitySelectStep codeSignIdentitySelectStep =
            new CodeSignIdentitySelectStep(
                codeSignIdentitiesSupplier, selectedProfileSupplier, selectedCodeSignIdentity);
        stepsBuilder.add(
            new ConditionalStep(isProvisioningProfileSelected, codeSignIdentitySelectStep));
      }

      {
        Supplier<CodeSignIdentity> codeSignIdentitySupplier =
            () -> {
              if (selectedCodeSignIdentity.getValue().isPresent()) {
                return selectedCodeSignIdentity.getValue().get();
              } else if (isDryRun()) {
                // Code sign identity is allowed not to be selected when code signing is run in dry
                // mode. Still, we need to return *something*.
                return CodeSignIdentity.AD_HOC;
              } else {
                String reason =
                    isProvisioningProfileSelected.get()
                        ? "Provisioning profile should be selected"
                        : "Code sign identity should be selected";
                throw new IllegalStateException(reason);
              }
            };

        Supplier<String> fingerprintSupplier =
            () ->
                codeSignIdentitySupplier.get().getFingerprint().map(HashCode::toString).orElse("");
        Path outputPath = outputPathResolver.resolvePath(output).getPath();
        stepsBuilder.add(
            WriteFileStep.of(filesystem.getRootPath(), fingerprintSupplier, outputPath, false));
      }

      return stepsBuilder.build();
    }

    private boolean isDryRun() {
      return dryRunOutput.isPresent();
    }
  }
}
