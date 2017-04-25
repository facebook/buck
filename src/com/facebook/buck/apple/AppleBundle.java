/*
 * Copyright 2014-present Facebook, Inc.
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
import com.dd.plist.NSNumber;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.facebook.buck.cxx.BuildRuleWithBinary;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.NativeTestable;
import com.facebook.buck.cxx.ProvidesLinkedBinaryDeps;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Either;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.FindAndReplaceStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.MoveStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.swift.SwiftPlatform;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import com.google.common.io.Files;
import com.google.common.util.concurrent.Futures;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Creates a bundle: a directory containing files and subdirectories, described by an Info.plist.
 */
public class AppleBundle extends AbstractBuildRule
    implements NativeTestable, BuildRuleWithBinary, HasRuntimeDeps, BinaryBuildRule {

  private static final Logger LOG = Logger.get(AppleBundle.class);
  public static final String CODE_SIGN_ENTITLEMENTS = "CODE_SIGN_ENTITLEMENTS";
  private static final String FRAMEWORK_EXTENSION =
      AppleBundleExtension.FRAMEWORK.toFileExtension();
  private static final String PP_DRY_RUN_RESULT_FILE = "BUCK_pp_dry_run.plist";
  private static final String CODE_SIGN_DRY_RUN_ARGS_FILE = "BUCK_code_sign_args.plist";
  private static final String CODE_SIGN_DRY_RUN_ENTITLEMENTS_FILE =
      "BUCK_code_sign_entitlements.plist";

  @AddToRuleKey private final String extension;

  @AddToRuleKey private final Optional<String> productName;

  @AddToRuleKey private final SourcePath infoPlist;

  @AddToRuleKey private final ImmutableMap<String, String> infoPlistSubstitutions;

  @AddToRuleKey private final Optional<BuildRule> binary;

  @AddToRuleKey private final Optional<AppleDsym> appleDsym;

  @AddToRuleKey private final AppleBundleDestinations destinations;

  @AddToRuleKey private final AppleBundleResources resources;

  @AddToRuleKey private final Set<SourcePath> frameworks;

  @AddToRuleKey private final Tool ibtool;

  @AddToRuleKey private final ImmutableSortedSet<BuildTarget> tests;

  @AddToRuleKey private final ApplePlatform platform;

  @AddToRuleKey private final String sdkName;

  @AddToRuleKey private final String sdkVersion;

  @AddToRuleKey private final ProvisioningProfileStore provisioningProfileStore;

  @AddToRuleKey private final CodeSignIdentityStore codeSignIdentityStore;

  @AddToRuleKey private final Optional<Tool> codesignAllocatePath;

  @AddToRuleKey private final Tool codesign;

  @AddToRuleKey private final Optional<Tool> swiftStdlibTool;

  @AddToRuleKey private final boolean dryRunCodeSigning;

  // Need to use String here as RuleKeyBuilder requires that paths exist to compute hashes.
  @AddToRuleKey private final ImmutableMap<SourcePath, String> extensionBundlePaths;

  private final Optional<AppleAssetCatalog> assetCatalog;
  private final Optional<CoreDataModel> coreDataModel;
  private final Optional<SceneKitAssets> sceneKitAssets;
  private final Optional<String> platformBuildVersion;
  private final Optional<String> xcodeVersion;
  private final Optional<String> xcodeBuildVersion;
  private final Path sdkPath;

  private final String minOSVersion;
  private final String binaryName;
  private final Path bundleRoot;
  private final Path binaryPath;
  private final Path bundleBinaryPath;

  private final boolean hasBinary;
  private final boolean cacheable;

  AppleBundle(
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      Either<AppleBundleExtension, String> extension,
      Optional<String> productName,
      SourcePath infoPlist,
      Map<String, String> infoPlistSubstitutions,
      Optional<BuildRule> binary,
      Optional<AppleDsym> appleDsym,
      AppleBundleDestinations destinations,
      AppleBundleResources resources,
      ImmutableMap<SourcePath, String> extensionBundlePaths,
      Set<SourcePath> frameworks,
      AppleCxxPlatform appleCxxPlatform,
      Optional<AppleAssetCatalog> assetCatalog,
      Optional<CoreDataModel> coreDataModel,
      Optional<SceneKitAssets> sceneKitAssets,
      Set<BuildTarget> tests,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      boolean dryRunCodeSigning,
      boolean cacheable) {
    super(params);
    this.extension =
        extension.isLeft() ? extension.getLeft().toFileExtension() : extension.getRight();
    this.productName = productName;
    this.infoPlist = infoPlist;
    this.infoPlistSubstitutions = ImmutableMap.copyOf(infoPlistSubstitutions);
    this.binary = binary;
    this.appleDsym = appleDsym;
    this.destinations = destinations;
    this.resources = resources;
    this.extensionBundlePaths = extensionBundlePaths;
    this.frameworks = frameworks;
    this.ibtool = appleCxxPlatform.getIbtool();
    this.assetCatalog = assetCatalog;
    this.coreDataModel = coreDataModel;
    this.sceneKitAssets = sceneKitAssets;
    this.binaryName = getBinaryName(getBuildTarget(), this.productName);
    this.bundleRoot =
        getBundleRoot(getProjectFilesystem(), getBuildTarget(), this.binaryName, this.extension);
    this.binaryPath = this.destinations.getExecutablesPath().resolve(this.binaryName);
    this.tests = ImmutableSortedSet.copyOf(tests);
    AppleSdk sdk = appleCxxPlatform.getAppleSdk();
    this.platform = sdk.getApplePlatform();
    this.sdkName = sdk.getName();
    this.sdkPath = appleCxxPlatform.getAppleSdkPaths().getSdkPath();
    this.sdkVersion = sdk.getVersion();
    this.minOSVersion = appleCxxPlatform.getMinVersion();
    this.platformBuildVersion = appleCxxPlatform.getBuildVersion();
    this.xcodeBuildVersion = appleCxxPlatform.getXcodeBuildVersion();
    this.xcodeVersion = appleCxxPlatform.getXcodeVersion();
    this.dryRunCodeSigning = dryRunCodeSigning;
    this.cacheable = cacheable;

    bundleBinaryPath = bundleRoot.resolve(binaryPath);
    hasBinary = binary.isPresent() && binary.get().getSourcePathToOutput() != null;

    if (needCodeSign() && !adHocCodeSignIsSufficient()) {
      this.provisioningProfileStore = provisioningProfileStore;
      this.codeSignIdentityStore = codeSignIdentityStore;
    } else {
      this.provisioningProfileStore =
          ProvisioningProfileStore.fromProvisioningProfiles(ImmutableList.of());
      this.codeSignIdentityStore = CodeSignIdentityStore.fromIdentities(ImmutableList.of());
    }
    this.codesignAllocatePath = appleCxxPlatform.getCodesignAllocate();
    this.codesign = appleCxxPlatform.getCodesignProvider().resolve(buildRuleResolver);
    this.swiftStdlibTool =
        appleCxxPlatform.getSwiftPlatform().map(SwiftPlatform::getSwiftStdlibTool);
  }

  public static String getBinaryName(BuildTarget buildTarget, Optional<String> productName) {
    if (productName.isPresent()) {
      return productName.get();
    } else {
      return buildTarget.getShortName();
    }
  }

  public static Path getBundleRoot(
      ProjectFilesystem filesystem, BuildTarget buildTarget, String binaryName, String extension) {
    return BuildTargets.getGenPath(filesystem, buildTarget, "%s")
        .resolve(binaryName + "." + extension);
  }

  public String getExtension() {
    return extension;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), bundleRoot);
  }

  public Path getInfoPlistPath() {
    return getMetadataPath().resolve("Info.plist");
  }

  public Path getUnzippedOutputFilePathToBinary() {
    return this.binaryPath;
  }

  private Path getMetadataPath() {
    return bundleRoot.resolve(destinations.getMetadataPath());
  }

  public String getPlatformName() {
    return platform.getName();
  }

  public Optional<BuildRule> getBinary() {
    return binary;
  }

  public boolean isLegacyWatchApp() {
    return extension.equals(AppleBundleExtension.APP.toFileExtension())
        && binary.isPresent()
        && binary
            .get()
            .getBuildTarget()
            .getFlavors()
            .contains(AppleBinaryDescription.LEGACY_WATCH_FLAVOR);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();

    stepsBuilder.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), bundleRoot));

    Path resourcesDestinationPath = bundleRoot.resolve(this.destinations.getResourcesPath());
    if (assetCatalog.isPresent()) {
      stepsBuilder.add(MkdirStep.of(getProjectFilesystem(), resourcesDestinationPath));
      Path bundleDir = assetCatalog.get().getOutputDir();
      stepsBuilder.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              bundleDir,
              resourcesDestinationPath,
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }

    if (coreDataModel.isPresent()) {
      stepsBuilder.add(MkdirStep.of(getProjectFilesystem(), resourcesDestinationPath));
      stepsBuilder.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              context
                  .getSourcePathResolver()
                  .getRelativePath(coreDataModel.get().getSourcePathToOutput()),
              resourcesDestinationPath,
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }

    if (sceneKitAssets.isPresent()) {
      stepsBuilder.add(MkdirStep.of(getProjectFilesystem(), resourcesDestinationPath));
      stepsBuilder.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              context
                  .getSourcePathResolver()
                  .getRelativePath(sceneKitAssets.get().getSourcePathToOutput()),
              resourcesDestinationPath,
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }

    Path metadataPath = getMetadataPath();

    Path infoPlistInputPath = context.getSourcePathResolver().getAbsolutePath(infoPlist);
    Path infoPlistSubstitutionTempPath =
        BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s.plist");
    Path infoPlistOutputPath = metadataPath.resolve("Info.plist");

    stepsBuilder.add(
        MkdirStep.of(getProjectFilesystem(), metadataPath),
        // TODO(bhamiltoncx): This is only appropriate for .app bundles.
        new WriteFileStep(
            getProjectFilesystem(),
            "APPLWRUN",
            metadataPath.resolve("PkgInfo"),
            /* executable */ false),
        MkdirStep.of(getProjectFilesystem(), infoPlistSubstitutionTempPath.getParent()),
        new FindAndReplaceStep(
            getProjectFilesystem(),
            infoPlistInputPath,
            infoPlistSubstitutionTempPath,
            InfoPlistSubstitution.createVariableExpansionFunction(
                withDefaults(
                    infoPlistSubstitutions,
                    ImmutableMap.of(
                        "EXECUTABLE_NAME", binaryName,
                        "PRODUCT_NAME", binaryName)))),
        new PlistProcessStep(
            getProjectFilesystem(),
            infoPlistSubstitutionTempPath,
            assetCatalog.isPresent()
                ? Optional.of(assetCatalog.get().getOutputPlist())
                : Optional.empty(),
            infoPlistOutputPath,
            getInfoPlistAdditionalKeys(),
            getInfoPlistOverrideKeys(),
            PlistProcessStep.OutputFormat.BINARY));

    if (hasBinary) {
      appendCopyBinarySteps(stepsBuilder, context.getSourcePathResolver());
      appendCopyDsymStep(stepsBuilder, buildableContext, context.getSourcePathResolver());
    }

    if (!Iterables.isEmpty(
        Iterables.concat(
            resources.getResourceDirs(),
            resources.getDirsContainingResourceDirs(),
            resources.getResourceFiles()))) {
      stepsBuilder.add(MkdirStep.of(getProjectFilesystem(), resourcesDestinationPath));
      for (SourcePath dir : resources.getResourceDirs()) {
        stepsBuilder.add(
            CopyStep.forDirectory(
                getProjectFilesystem(),
                context.getSourcePathResolver().getAbsolutePath(dir),
                resourcesDestinationPath,
                CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
      }
      for (SourcePath dir : resources.getDirsContainingResourceDirs()) {
        stepsBuilder.add(
            CopyStep.forDirectory(
                getProjectFilesystem(),
                context.getSourcePathResolver().getAbsolutePath(dir),
                resourcesDestinationPath,
                CopyStep.DirectoryMode.CONTENTS_ONLY));
      }
      for (SourcePath file : resources.getResourceFiles()) {
        Path resolvedFilePath = context.getSourcePathResolver().getAbsolutePath(file);
        Path destinationPath = resourcesDestinationPath.resolve(resolvedFilePath.getFileName());
        addResourceProcessingSteps(
            context.getSourcePathResolver(), resolvedFilePath, destinationPath, stepsBuilder);
      }
    }

    ImmutableList.Builder<Path> codeSignOnCopyPathsBuilder = ImmutableList.builder();

    addStepsToCopyExtensionBundlesDependencies(
        context.getSourcePathResolver(), stepsBuilder, codeSignOnCopyPathsBuilder);

    for (SourcePath variantSourcePath : resources.getResourceVariantFiles()) {
      Path variantFilePath = context.getSourcePathResolver().getAbsolutePath(variantSourcePath);

      Path variantDirectory = variantFilePath.getParent();
      if (variantDirectory == null || !variantDirectory.toString().endsWith(".lproj")) {
        throw new HumanReadableException(
            "Variant files have to be in a directory with name ending in '.lproj', "
                + "but '%s' is not.",
            variantFilePath);
      }

      Path bundleVariantDestinationPath =
          resourcesDestinationPath.resolve(variantDirectory.getFileName());
      stepsBuilder.add(MkdirStep.of(getProjectFilesystem(), bundleVariantDestinationPath));

      Path destinationPath = bundleVariantDestinationPath.resolve(variantFilePath.getFileName());
      addResourceProcessingSteps(
          context.getSourcePathResolver(), variantFilePath, destinationPath, stepsBuilder);
    }

    if (!frameworks.isEmpty()) {
      Path frameworksDestinationPath = bundleRoot.resolve(this.destinations.getFrameworksPath());
      stepsBuilder.add(MkdirStep.of(getProjectFilesystem(), frameworksDestinationPath));
      for (SourcePath framework : frameworks) {
        Path srcPath = context.getSourcePathResolver().getAbsolutePath(framework);
        stepsBuilder.add(
            CopyStep.forDirectory(
                getProjectFilesystem(),
                srcPath,
                frameworksDestinationPath,
                CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
        codeSignOnCopyPathsBuilder.add(frameworksDestinationPath.resolve(srcPath.getFileName()));
      }
    }

    if (needCodeSign()) {
      Optional<Path> signingEntitlementsTempPath;
      Supplier<CodeSignIdentity> codeSignIdentitySupplier;

      if (adHocCodeSignIsSufficient()) {
        signingEntitlementsTempPath = Optional.empty();
        codeSignIdentitySupplier = () -> CodeSignIdentity.AD_HOC;
      } else {
        // Copy the .mobileprovision file if the platform requires it, and sign the executable.
        Optional<Path> entitlementsPlist = Optional.empty();
        final Path srcRoot =
            getProjectFilesystem().getRootPath().resolve(getBuildTarget().getBasePath());
        Optional<String> entitlementsPlistString =
            InfoPlistSubstitution.getVariableExpansionForPlatform(
                CODE_SIGN_ENTITLEMENTS,
                platform.getName(),
                withDefaults(
                    infoPlistSubstitutions,
                    ImmutableMap.of(
                        "SOURCE_ROOT", srcRoot.toString(),
                        "SRCROOT", srcRoot.toString())));
        entitlementsPlist =
            entitlementsPlistString.map(
                entitlementsPlistName -> {
                  ProjectFilesystem filesystem = getProjectFilesystem();
                  Path originalEntitlementsPlist =
                      srcRoot.resolve(Paths.get(entitlementsPlistName));
                  Path entitlementsPlistWithSubstitutions =
                      BuildTargets.getScratchPath(
                          filesystem, getBuildTarget(), "%s-Entitlements.plist");

                  stepsBuilder.add(
                      new FindAndReplaceStep(
                          filesystem,
                          originalEntitlementsPlist,
                          entitlementsPlistWithSubstitutions,
                          InfoPlistSubstitution.createVariableExpansionFunction(
                              infoPlistSubstitutions)));

                  return filesystem.resolve(entitlementsPlistWithSubstitutions);
                });

        signingEntitlementsTempPath =
            Optional.of(
                BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s.xcent"));

        final Path dryRunResultPath = bundleRoot.resolve(PP_DRY_RUN_RESULT_FILE);

        final ProvisioningProfileCopyStep provisioningProfileCopyStep =
            new ProvisioningProfileCopyStep(
                getProjectFilesystem(),
                infoPlistOutputPath,
                platform,
                Optional.empty(), // Provisioning profile UUID -- find automatically.
                entitlementsPlist,
                provisioningProfileStore,
                resourcesDestinationPath.resolve("embedded.mobileprovision"),
                dryRunCodeSigning
                    ? bundleRoot.resolve(CODE_SIGN_DRY_RUN_ENTITLEMENTS_FILE)
                    : signingEntitlementsTempPath.get(),
                codeSignIdentityStore,
                dryRunCodeSigning ? Optional.of(dryRunResultPath) : Optional.empty());
        stepsBuilder.add(provisioningProfileCopyStep);

        codeSignIdentitySupplier =
            () -> {
              // Using getUnchecked here because the previous step should already throw if exception
              // occurred, and this supplier would never be evaluated.
              Optional<ProvisioningProfileMetadata> selectedProfile =
                  Futures.getUnchecked(
                      provisioningProfileCopyStep.getSelectedProvisioningProfileFuture());

              if (!selectedProfile.isPresent()) {
                // This should only happen in dry-run codesign mode (since otherwise an exception
                // would have been thrown already.)  Still, we need to return *something*.
                Preconditions.checkState(dryRunCodeSigning);
                return CodeSignIdentity.AD_HOC;
              }

              ImmutableSet<HashCode> fingerprints =
                  selectedProfile.get().getDeveloperCertificateFingerprints();
              if (fingerprints.isEmpty()) {
                // No constraints, pick an arbitrary identity.
                // If no identities are available, use an ad-hoc identity.
                return Iterables.getFirst(
                    codeSignIdentityStore.getIdentities(), CodeSignIdentity.AD_HOC);
              }
              for (CodeSignIdentity identity : codeSignIdentityStore.getIdentities()) {
                if (identity.getFingerprint().isPresent()
                    && fingerprints.contains(identity.getFingerprint().get())) {
                  return identity;
                }
              }

              throw new HumanReadableException(
                  "No code sign identity available for provisioning profile: %s\n"
                      + "Profile requires an identity with one of the following SHA1 fingerprints "
                      + "available in your keychain: \n  %s",
                  selectedProfile.get().getProfilePath(), Joiner.on("\n  ").join(fingerprints));
            };
      }

      addSwiftStdlibStepIfNeeded(
          context.getSourcePathResolver(),
          bundleRoot.resolve(Paths.get("Frameworks")),
          dryRunCodeSigning
              ? Optional.<Supplier<CodeSignIdentity>>empty()
              : Optional.of(codeSignIdentitySupplier),
          stepsBuilder,
          false /* is for packaging? */);

      for (Path codeSignOnCopyPath : codeSignOnCopyPathsBuilder.build()) {
        stepsBuilder.add(
            new CodeSignStep(
                getProjectFilesystem(),
                context.getSourcePathResolver(),
                codeSignOnCopyPath,
                Optional.empty(),
                codeSignIdentitySupplier,
                codesign,
                codesignAllocatePath,
                dryRunCodeSigning
                    ? Optional.of(codeSignOnCopyPath.resolve(CODE_SIGN_DRY_RUN_ARGS_FILE))
                    : Optional.empty()));
      }

      stepsBuilder.add(
          new CodeSignStep(
              getProjectFilesystem(),
              context.getSourcePathResolver(),
              bundleRoot,
              signingEntitlementsTempPath,
              codeSignIdentitySupplier,
              codesign,
              codesignAllocatePath,
              dryRunCodeSigning
                  ? Optional.of(bundleRoot.resolve(CODE_SIGN_DRY_RUN_ARGS_FILE))
                  : Optional.empty()));
    } else {
      addSwiftStdlibStepIfNeeded(
          context.getSourcePathResolver(),
          bundleRoot.resolve(Paths.get("Frameworks")),
          Optional.<Supplier<CodeSignIdentity>>empty(),
          stepsBuilder,
          false /* is for packaging? */);
    }

    // Ensure the bundle directory is archived so we can fetch it later.
    buildableContext.recordArtifact(
        context.getSourcePathResolver().getRelativePath(getSourcePathToOutput()));

    return stepsBuilder.build();
  }

  private void appendCopyBinarySteps(
      ImmutableList.Builder<Step> stepsBuilder, SourcePathResolver pathResolver) {
    Preconditions.checkArgument(hasBinary);

    final Path binaryOutputPath =
        pathResolver.getRelativePath(
            Preconditions.checkNotNull(binary.get().getSourcePathToOutput()));

    copyBinaryIntoBundle(stepsBuilder, binaryOutputPath);
    copyAnotherCopyOfWatchKitStub(stepsBuilder, binaryOutputPath);
  }

  private void copyBinaryIntoBundle(
      ImmutableList.Builder<Step> stepsBuilder, Path binaryOutputPath) {
    stepsBuilder.add(
        MkdirStep.of(
            getProjectFilesystem(), bundleRoot.resolve(this.destinations.getExecutablesPath())));
    stepsBuilder.add(CopyStep.forFile(getProjectFilesystem(), binaryOutputPath, bundleBinaryPath));
  }

  private void copyAnotherCopyOfWatchKitStub(
      ImmutableList.Builder<Step> stepsBuilder, Path binaryOutputPath) {
    if ((isLegacyWatchApp() || (platform.getName().contains("watch") && minOSVersion.equals("2.0")))
        && binary.get() instanceof WriteFile) {
      final Path watchKitStubDir = bundleRoot.resolve("_WatchKitStub");
      stepsBuilder.add(
          MkdirStep.of(getProjectFilesystem(), watchKitStubDir),
          CopyStep.forFile(
              getProjectFilesystem(), binaryOutputPath, watchKitStubDir.resolve("WK")));
    }
  }

  private void appendCopyDsymStep(
      ImmutableList.Builder<Step> stepsBuilder,
      BuildableContext buildableContext,
      SourcePathResolver pathResolver) {
    if (appleDsym.isPresent()) {
      stepsBuilder.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              pathResolver.getRelativePath(appleDsym.get().getSourcePathToOutput()),
              bundleRoot.getParent(),
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
      appendDsymRenameStepToMatchBundleName(stepsBuilder, buildableContext, pathResolver);
    }
  }

  private void appendDsymRenameStepToMatchBundleName(
      ImmutableList.Builder<Step> stepsBuilder,
      BuildableContext buildableContext,
      SourcePathResolver pathResolver) {
    Preconditions.checkArgument(hasBinary && appleDsym.isPresent());

    // rename dSYM bundle to match bundle name
    Path dsymPath = pathResolver.getRelativePath(appleDsym.get().getSourcePathToOutput());
    Path dsymSourcePath = bundleRoot.getParent().resolve(dsymPath.getFileName());
    Path dsymDestinationPath =
        bundleRoot
            .getParent()
            .resolve(bundleRoot.getFileName() + "." + AppleBundleExtension.DSYM.toFileExtension());
    stepsBuilder.add(RmStep.of(getProjectFilesystem(), dsymDestinationPath).withRecursive(true));
    stepsBuilder.add(new MoveStep(getProjectFilesystem(), dsymSourcePath, dsymDestinationPath));

    String dwarfFilename =
        AppleDsym.getDwarfFilenameForDsymTarget(appleDsym.get().getBuildTarget());

    // rename DWARF file inside dSYM bundle to match bundle name
    Path dwarfFolder = dsymDestinationPath.resolve(AppleDsym.DSYM_DWARF_FILE_FOLDER);
    Path dwarfSourcePath = dwarfFolder.resolve(dwarfFilename);
    Path dwarfDestinationPath = dwarfFolder.resolve(MorePaths.getNameWithoutExtension(bundleRoot));
    stepsBuilder.add(new MoveStep(getProjectFilesystem(), dwarfSourcePath, dwarfDestinationPath));

    // record dSYM so we can fetch it from cache
    buildableContext.recordArtifact(dsymDestinationPath);
  }

  private void addStepsToCopyExtensionBundlesDependencies(
      SourcePathResolver resolver,
      ImmutableList.Builder<Step> stepsBuilder,
      ImmutableList.Builder<Path> codeSignOnCopyPathsBuilder) {
    for (Map.Entry<SourcePath, String> entry : extensionBundlePaths.entrySet()) {
      Path srcPath = resolver.getAbsolutePath(entry.getKey());
      Path destPath = bundleRoot.resolve(entry.getValue());
      stepsBuilder.add(MkdirStep.of(getProjectFilesystem(), destPath));
      stepsBuilder.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              srcPath,
              destPath,
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
      if (srcPath.toString().endsWith("." + FRAMEWORK_EXTENSION)) {
        codeSignOnCopyPathsBuilder.add(destPath.resolve(srcPath.getFileName()));
      }
    }
  }

  public static ImmutableMap<String, String> withDefaults(
      ImmutableMap<String, String> map, ImmutableMap<String, String> defaults) {
    ImmutableMap.Builder<String, String> builder =
        ImmutableMap.<String, String>builder().putAll(map);
    for (ImmutableMap.Entry<String, String> entry : defaults.entrySet()) {
      if (!map.containsKey(entry.getKey())) {
        builder = builder.put(entry.getKey(), entry.getValue());
      }
    }
    return builder.build();
  }

  private ImmutableMap<String, NSObject> getInfoPlistOverrideKeys() {
    ImmutableMap.Builder<String, NSObject> keys = ImmutableMap.builder();

    if (platform.getName().contains("osx")) {
      keys.put("LSRequiresIPhoneOS", new NSNumber(false));
    } else if (!platform.getName().contains("watch") && !isLegacyWatchApp()) {
      keys.put("LSRequiresIPhoneOS", new NSNumber(true));
    }

    return keys.build();
  }

  private ImmutableMap<String, NSObject> getInfoPlistAdditionalKeys() {
    ImmutableMap.Builder<String, NSObject> keys = ImmutableMap.builder();
    final String platformName = platform.getName();

    if (platformName.contains("osx")) {
      keys.put("NSHighResolutionCapable", new NSNumber(true));
      keys.put("NSSupportsAutomaticGraphicsSwitching", new NSNumber(true));
      keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("MacOSX")));
    } else if (platformName.contains("iphoneos")) {
      keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("iPhoneOS")));
    } else if (platformName.contains("iphonesimulator")) {
      keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("iPhoneSimulator")));
    } else if (platformName.contains("watchos") && !isLegacyWatchApp()) {
      keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("WatchOS")));
    } else if (platformName.contains("watchsimulator") && !isLegacyWatchApp()) {
      keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("WatchSimulator")));
    }

    keys.put("DTPlatformName", new NSString(platformName));
    keys.put("DTPlatformVersion", new NSString(sdkVersion));
    keys.put("DTSDKName", new NSString(sdkName + sdkVersion));
    keys.put("MinimumOSVersion", new NSString(minOSVersion));
    if (platformBuildVersion.isPresent()) {
      keys.put("DTPlatformBuild", new NSString(platformBuildVersion.get()));
      keys.put("DTSDKBuild", new NSString(platformBuildVersion.get()));
    }

    if (xcodeBuildVersion.isPresent()) {
      keys.put("DTXcodeBuild", new NSString(xcodeBuildVersion.get()));
    }

    if (xcodeVersion.isPresent()) {
      keys.put("DTXcode", new NSString(xcodeVersion.get()));
    }

    return keys.build();
  }

  public void addSwiftStdlibStepIfNeeded(
      SourcePathResolver resolver,
      Path destinationPath,
      Optional<Supplier<CodeSignIdentity>> codeSignIdentitySupplier,
      ImmutableList.Builder<Step> stepsBuilder,
      boolean isForPackaging) {
    // It's apparently safe to run this even on a non-swift bundle (in that case, no libs
    // are copied over).
    if (swiftStdlibTool.isPresent()) {
      ImmutableList.Builder<String> swiftStdlibCommand = ImmutableList.builder();
      swiftStdlibCommand.addAll(swiftStdlibTool.get().getCommandPrefix(resolver));
      swiftStdlibCommand.add(
          "--scan-executable",
          bundleBinaryPath.toString(),
          "--scan-folder",
          bundleRoot.resolve(this.destinations.getFrameworksPath()).toString(),
          "--scan-folder",
          bundleRoot.resolve(destinations.getPlugInsPath()).toString());

      String tempDirPattern = isForPackaging ? "__swift_packaging_temp__%s" : "__swift_temp__%s";
      stepsBuilder.add(
          new SwiftStdlibStep(
              getProjectFilesystem().getRootPath(),
              BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), tempDirPattern),
              this.sdkPath,
              destinationPath,
              swiftStdlibCommand.build(),
              codeSignIdentitySupplier));
    }
  }

  private void addStoryboardProcessingSteps(
      SourcePathResolver resolver,
      Path sourcePath,
      Path destinationPath,
      ImmutableList.Builder<Step> stepsBuilder) {
    if (platform.getName().contains("watch") || isLegacyWatchApp()) {
      LOG.debug(
          "Compiling storyboard %s to storyboardc %s and linking", sourcePath, destinationPath);

      Path compiledStoryboardPath =
          BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s.storyboardc");
      stepsBuilder.add(
          new IbtoolStep(
              getProjectFilesystem(),
              ibtool.getEnvironment(resolver),
              ibtool.getCommandPrefix(resolver),
              ImmutableList.of("--target-device", "watch", "--compile"),
              sourcePath,
              compiledStoryboardPath));

      stepsBuilder.add(
          new IbtoolStep(
              getProjectFilesystem(),
              ibtool.getEnvironment(resolver),
              ibtool.getCommandPrefix(resolver),
              ImmutableList.of("--target-device", "watch", "--link"),
              compiledStoryboardPath,
              destinationPath.getParent()));

    } else {
      LOG.debug("Compiling storyboard %s to storyboardc %s", sourcePath, destinationPath);

      String compiledStoryboardFilename =
          Files.getNameWithoutExtension(destinationPath.toString()) + ".storyboardc";

      Path compiledStoryboardPath = destinationPath.getParent().resolve(compiledStoryboardFilename);
      stepsBuilder.add(
          new IbtoolStep(
              getProjectFilesystem(),
              ibtool.getEnvironment(resolver),
              ibtool.getCommandPrefix(resolver),
              ImmutableList.of("--compile"),
              sourcePath,
              compiledStoryboardPath));
    }
  }

  private void addResourceProcessingSteps(
      SourcePathResolver resolver,
      Path sourcePath,
      Path destinationPath,
      ImmutableList.Builder<Step> stepsBuilder) {
    String sourcePathExtension =
        Files.getFileExtension(sourcePath.toString()).toLowerCase(Locale.US);
    switch (sourcePathExtension) {
      case "plist":
      case "stringsdict":
        LOG.debug("Converting plist %s to binary plist %s", sourcePath, destinationPath);
        stepsBuilder.add(
            new PlistProcessStep(
                getProjectFilesystem(),
                sourcePath,
                Optional.empty(),
                destinationPath,
                ImmutableMap.of(),
                ImmutableMap.of(),
                PlistProcessStep.OutputFormat.BINARY));
        break;
      case "storyboard":
        addStoryboardProcessingSteps(resolver, sourcePath, destinationPath, stepsBuilder);
        break;
      case "xib":
        String compiledNibFilename =
            Files.getNameWithoutExtension(destinationPath.toString()) + ".nib";
        Path compiledNibPath = destinationPath.getParent().resolve(compiledNibFilename);
        LOG.debug("Compiling XIB %s to NIB %s", sourcePath, destinationPath);
        stepsBuilder.add(
            new IbtoolStep(
                getProjectFilesystem(),
                ibtool.getEnvironment(resolver),
                ibtool.getCommandPrefix(resolver),
                ImmutableList.of("--compile"),
                sourcePath,
                compiledNibPath));
        break;
      default:
        stepsBuilder.add(CopyStep.forFile(getProjectFilesystem(), sourcePath, destinationPath));
        break;
    }
  }

  @Override
  public boolean isTestedBy(BuildTarget testRule) {
    if (tests.contains(testRule)) {
      return true;
    }

    if (binary.isPresent()) {
      BuildRule binaryRule = binary.get();
      if (binaryRule instanceof NativeTestable) {
        return ((NativeTestable) binaryRule).isTestedBy(testRule);
      }
    }

    return false;
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform, HeaderVisibility headerVisibility)
      throws NoSuchBuildTargetException {
    if (binary.isPresent()) {
      BuildRule binaryRule = binary.get();
      if (binaryRule instanceof NativeTestable) {
        return ((NativeTestable) binaryRule).getCxxPreprocessorInput(cxxPlatform, headerVisibility);
      }
    }
    return CxxPreprocessorInput.EMPTY;
  }

  private boolean adHocCodeSignIsSufficient() {
    return ApplePlatform.adHocCodeSignIsSufficient(platform.getName());
  }

  // .framework bundles will be code-signed when they're copied into the containing bundle.
  private boolean needCodeSign() {
    return binary.isPresent()
        && ApplePlatform.needsCodeSign(platform.getName())
        && !extension.equals(FRAMEWORK_EXTENSION);
  }

  @Override
  public BuildRule getBinaryBuildRule() {
    return binary.get();
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps() {
    if (binary.get() instanceof ProvidesLinkedBinaryDeps) {
      List<BuildRule> linkDeps = new ArrayList<>();
      linkDeps.addAll(((ProvidesLinkedBinaryDeps) binary.get()).getCompileDeps());
      linkDeps.addAll(((ProvidesLinkedBinaryDeps) binary.get()).getStaticLibraryDeps());
      if (linkDeps.size() > 0) {
        return Stream.concat(Stream.of(binary.get()), linkDeps.stream())
            .map(BuildRule::getBuildTarget);
      }
    }
    return Stream.empty();
  }

  @Override
  public boolean isCacheable() {
    return cacheable;
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder()
        .addArg(SourcePathArg.of(new PathSourcePath(getProjectFilesystem(), bundleBinaryPath)))
        .build();
  }
}
