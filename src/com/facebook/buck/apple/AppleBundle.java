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
import com.facebook.buck.apple.platform_type.ApplePlatformType;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.apple.toolchain.CodeSignIdentityStore;
import com.facebook.buck.apple.toolchain.ProvisioningProfileMetadata;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HasAppleDebugSymbolDeps;
import com.facebook.buck.cxx.NativeTestable;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.FindAndReplaceStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.MoveStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
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
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Creates a bundle: a directory containing files and subdirectories, described by an Info.plist.
 */
public class AppleBundle extends AbstractBuildRuleWithDeclaredAndExtraDeps
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

  @AddToRuleKey private final Optional<SourcePath> entitlementsFile;

  @AddToRuleKey private final Optional<BuildRule> binary;

  @AddToRuleKey private final Optional<AppleDsym> appleDsym;

  @AddToRuleKey private final ImmutableSet<BuildRule> extraBinaries;

  @AddToRuleKey private final AppleBundleDestinations destinations;

  @AddToRuleKey private final AppleBundleResources resources;

  @AddToRuleKey private final Set<SourcePath> frameworks;

  @AddToRuleKey private final Tool ibtool;

  @AddToRuleKey private final ImmutableSortedSet<BuildTarget> tests;

  @AddToRuleKey private final ApplePlatform platform;

  @AddToRuleKey private final String sdkName;

  @AddToRuleKey private final String sdkVersion;

  @AddToRuleKey private final ProvisioningProfileStore provisioningProfileStore;

  @AddToRuleKey private final Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier;

  @AddToRuleKey private final Optional<Tool> codesignAllocatePath;

  @AddToRuleKey private final Tool codesign;

  @AddToRuleKey private final Optional<Tool> swiftStdlibTool;

  @AddToRuleKey private final boolean dryRunCodeSigning;

  @AddToRuleKey private final ImmutableList<String> codesignFlags;

  @AddToRuleKey private final Optional<String> codesignIdentitySubjectName;

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

  private final ImmutableList<String> ibtoolModuleParams;

  private final boolean hasBinary;
  private final boolean cacheable;
  private final boolean verifyResources;

  private final Duration codesignTimeout;

  AppleBundle(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      Either<AppleBundleExtension, String> extension,
      Optional<String> productName,
      SourcePath infoPlist,
      Map<String, String> infoPlistSubstitutions,
      Optional<BuildRule> binary,
      Optional<AppleDsym> appleDsym,
      ImmutableSet<BuildRule> extraBinaries,
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
      boolean cacheable,
      boolean verifyResources,
      ImmutableList<String> codesignFlags,
      Optional<String> codesignIdentity,
      Optional<Boolean> ibtoolModuleFlag,
      Duration codesignTimeout) {
    super(buildTarget, projectFilesystem, params);
    this.extension =
        extension.isLeft() ? extension.getLeft().toFileExtension() : extension.getRight();
    this.productName = productName;
    this.infoPlist = infoPlist;
    this.infoPlistSubstitutions = ImmutableMap.copyOf(infoPlistSubstitutions);
    this.binary = binary;
    Optional<SourcePath> entitlementsFile = Optional.empty();
    if (binary.isPresent()) {
      Optional<HasEntitlementsFile> hasEntitlementsFile =
          graphBuilder.requireMetadata(binary.get().getBuildTarget(), HasEntitlementsFile.class);
      if (hasEntitlementsFile.isPresent()) {
        entitlementsFile = hasEntitlementsFile.get().getEntitlementsFile();
      }
    }
    this.entitlementsFile = entitlementsFile;

    this.appleDsym = appleDsym;
    this.extraBinaries = extraBinaries;
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
    this.verifyResources = verifyResources;
    this.codesignFlags = codesignFlags;
    this.codesignIdentitySubjectName = codesignIdentity;
    this.ibtoolModuleParams =
        ibtoolModuleFlag.orElse(false)
            ? ImmutableList.of("--module", this.binaryName)
            : ImmutableList.of();

    bundleBinaryPath = bundleRoot.resolve(binaryPath);
    hasBinary = binary.isPresent() && binary.get().getSourcePathToOutput() != null;

    if (needCodeSign() && !adHocCodeSignIsSufficient()) {
      this.provisioningProfileStore = provisioningProfileStore;
      this.codeSignIdentitiesSupplier = codeSignIdentityStore.getIdentitiesSupplier();
    } else {
      this.provisioningProfileStore = ProvisioningProfileStore.empty();
      this.codeSignIdentitiesSupplier = Suppliers.ofInstance(ImmutableList.of());
    }
    this.codesignAllocatePath = appleCxxPlatform.getCodesignAllocate();
    this.codesign = appleCxxPlatform.getCodesignProvider().resolve(graphBuilder);
    this.swiftStdlibTool =
        appleCxxPlatform.getSwiftPlatform().isPresent()
            ? appleCxxPlatform.getSwiftPlatform().get().getSwiftStdlibTool()
            : Optional.empty();

    this.codesignTimeout = codesignTimeout;
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
    return BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s")
        .resolve(binaryName + "." + extension);
  }

  public String getExtension() {
    return extension;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), bundleRoot);
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

  public Optional<AppleDsym> getAppleDsym() {
    return appleDsym;
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

    stepsBuilder.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), bundleRoot)));

    Path resourcesDestinationPath = bundleRoot.resolve(this.destinations.getResourcesPath());
    if (assetCatalog.isPresent()) {
      stepsBuilder.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  resourcesDestinationPath)));
      Path bundleDir = assetCatalog.get().getOutputDir();
      stepsBuilder.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              bundleDir,
              resourcesDestinationPath,
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }

    if (coreDataModel.isPresent()) {
      stepsBuilder.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  resourcesDestinationPath)));
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
      stepsBuilder.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  resourcesDestinationPath)));
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
        BuildTargetPaths.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s.plist");
    Path infoPlistOutputPath = metadataPath.resolve("Info.plist");

    stepsBuilder.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), metadataPath)));

    if (needsPkgInfoFile()) {
      // TODO(bhamiltoncx): This is only appropriate for .app bundles.
      stepsBuilder.add(
          new WriteFileStep(
              getProjectFilesystem(),
              "APPLWRUN",
              metadataPath.resolve("PkgInfo"),
              /* executable */ false));
    }

    stepsBuilder.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                infoPlistSubstitutionTempPath.getParent())),
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
      appendCopyBinarySteps(stepsBuilder, context);
      appendCopyDsymStep(stepsBuilder, buildableContext, context);
    }

    if (!Iterables.isEmpty(
        Iterables.concat(
            resources.getResourceDirs(),
            resources.getDirsContainingResourceDirs(),
            resources.getResourceFiles()))) {
      if (verifyResources) {
        verifyResourceConflicts(resources, context.getSourcePathResolver());
      }
      stepsBuilder.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  resourcesDestinationPath)));
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

    addStepsToCopyExtensionBundlesDependencies(context, stepsBuilder, codeSignOnCopyPathsBuilder);

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
      stepsBuilder.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  bundleVariantDestinationPath)));

      Path destinationPath = bundleVariantDestinationPath.resolve(variantFilePath.getFileName());
      addResourceProcessingSteps(
          context.getSourcePathResolver(), variantFilePath, destinationPath, stepsBuilder);
    }

    if (!frameworks.isEmpty()) {
      Path frameworksDestinationPath = bundleRoot.resolve(this.destinations.getFrameworksPath());
      stepsBuilder.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  frameworksDestinationPath)));
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
        CodeSignIdentity identity =
            codesignIdentitySubjectName
                .map(id -> CodeSignIdentity.ofAdhocSignedWithSubjectCommonName(id))
                .orElse(CodeSignIdentity.AD_HOC);
        codeSignIdentitySupplier = () -> identity;
      } else {
        // Copy the .mobileprovision file if the platform requires it, and sign the executable.
        Optional<Path> entitlementsPlist = Optional.empty();

        // Try to use the entitlements file specified in the bundle's binary first.
        entitlementsPlist =
            entitlementsFile.map(p -> context.getSourcePathResolver().getAbsolutePath(p));

        // Fall back to getting CODE_SIGN_ENTITLEMENTS from info_plist_substitutions.
        if (!entitlementsPlist.isPresent()) {
          Path srcRoot =
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
                        BuildTargetPaths.getScratchPath(
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
        }

        signingEntitlementsTempPath =
            Optional.of(
                BuildTargetPaths.getScratchPath(
                    getProjectFilesystem(), getBuildTarget(), "%s.xcent"));

        Path dryRunResultPath = bundleRoot.resolve(PP_DRY_RUN_RESULT_FILE);

        ProvisioningProfileCopyStep provisioningProfileCopyStep =
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
                codeSignIdentitiesSupplier,
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
                    codeSignIdentitiesSupplier.get(), CodeSignIdentity.AD_HOC);
              }
              for (CodeSignIdentity identity : codeSignIdentitiesSupplier.get()) {
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
          bundleRoot.resolve(destinations.getFrameworksPath()),
          dryRunCodeSigning ? Optional.empty() : Optional.of(codeSignIdentitySupplier),
          stepsBuilder,
          false /* is for packaging? */);

      for (BuildRule extraBinary : extraBinaries) {
        Path outputPath = getBundleBinaryPathForBuildRule(extraBinary);
        codeSignOnCopyPathsBuilder.add(outputPath);
      }

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
                    : Optional.empty(),
                codesignFlags,
                codesignTimeout));
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
                  : Optional.empty(),
              codesignFlags,
              codesignTimeout));
    } else {
      addSwiftStdlibStepIfNeeded(
          context.getSourcePathResolver(),
          bundleRoot.resolve(destinations.getFrameworksPath()),
          Optional.empty(),
          stepsBuilder,
          false /* is for packaging? */);
    }

    // Ensure the bundle directory is archived so we can fetch it later.
    buildableContext.recordArtifact(
        context.getSourcePathResolver().getRelativePath(getSourcePathToOutput()));

    return stepsBuilder.build();
  }

  private void verifyResourceConflicts(
      AppleBundleResources resources, SourcePathResolver resolver) {
    // Ensure there are no resources that will overwrite each other
    // TODO: handle ResourceDirsContainingResourceDirs
    Set<Path> resourcePaths = new HashSet<>();
    for (SourcePath path :
        Iterables.concat(resources.getResourceDirs(), resources.getResourceFiles())) {
      Path pathInBundle = resolver.getRelativePath(path).getFileName();
      if (resourcePaths.contains(pathInBundle)) {
        throw new HumanReadableException(
            "Bundle contains multiple resources with path %s", pathInBundle);
      } else {
        resourcePaths.add(pathInBundle);
      }
    }
  }

  private boolean needsPkgInfoFile() {
    return !(extension.equals(AppleBundleExtension.XPC.toFileExtension())
        || extension.equals(AppleBundleExtension.QLGENERATOR.toFileExtension()));
  }

  private void appendCopyBinarySteps(
      ImmutableList.Builder<Step> stepsBuilder, BuildContext context) {
    Preconditions.checkArgument(hasBinary);

    Path binaryOutputPath =
        context
            .getSourcePathResolver()
            .getAbsolutePath(Preconditions.checkNotNull(binary.get().getSourcePathToOutput()));

    ImmutableMap.Builder<Path, Path> binariesBuilder = ImmutableMap.builder();
    binariesBuilder.put(bundleBinaryPath, binaryOutputPath);

    for (BuildRule extraBinary : extraBinaries) {
      Path outputPath =
          context.getSourcePathResolver().getRelativePath(extraBinary.getSourcePathToOutput());
      Path bundlePath = getBundleBinaryPathForBuildRule(extraBinary);
      binariesBuilder.put(bundlePath, outputPath);
    }

    copyBinariesIntoBundle(stepsBuilder, context, binariesBuilder.build());
    copyAnotherCopyOfWatchKitStub(stepsBuilder, context, binaryOutputPath);
  }

  private Path getBundleBinaryPathForBuildRule(BuildRule buildRule) {
    BuildTarget unflavoredTarget = buildRule.getBuildTarget().withFlavors();
    String binaryName = getBinaryName(unflavoredTarget, Optional.empty());
    Path pathRelativeToBundleRoot = destinations.getExecutablesPath().resolve(binaryName);
    return bundleRoot.resolve(pathRelativeToBundleRoot);
  }

  /**
   * @param binariesMap A map from destination to source. Destination is deliberately used as a key
   *     prevent multiple sources overwriting the same destination.
   */
  private void copyBinariesIntoBundle(
      ImmutableList.Builder<Step> stepsBuilder,
      BuildContext context,
      ImmutableMap<Path, Path> binariesMap) {
    stepsBuilder.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                bundleRoot.resolve(this.destinations.getExecutablesPath()))));

    binariesMap.forEach(
        (binaryBundlePath, binaryOutputPath) -> {
          stepsBuilder.add(
              CopyStep.forFile(getProjectFilesystem(), binaryOutputPath, binaryBundlePath));
        });
  }

  private void copyAnotherCopyOfWatchKitStub(
      ImmutableList.Builder<Step> stepsBuilder, BuildContext context, Path binaryOutputPath) {
    if ((isLegacyWatchApp() || platform.getName().contains("watch"))
        && binary.get() instanceof WriteFile) {
      Path watchKitStubDir = bundleRoot.resolve("_WatchKitStub");
      stepsBuilder.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), watchKitStubDir)),
          CopyStep.forFile(
              getProjectFilesystem(), binaryOutputPath, watchKitStubDir.resolve("WK")));
    }
  }

  private void appendCopyDsymStep(
      ImmutableList.Builder<Step> stepsBuilder,
      BuildableContext buildableContext,
      BuildContext buildContext) {
    if (appleDsym.isPresent()) {
      stepsBuilder.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              buildContext
                  .getSourcePathResolver()
                  .getAbsolutePath(appleDsym.get().getSourcePathToOutput()),
              bundleRoot.getParent(),
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
      appendDsymRenameStepToMatchBundleName(stepsBuilder, buildableContext, buildContext);
    }
  }

  private void appendDsymRenameStepToMatchBundleName(
      ImmutableList.Builder<Step> stepsBuilder,
      BuildableContext buildableContext,
      BuildContext buildContext) {
    Preconditions.checkArgument(hasBinary && appleDsym.isPresent());

    // rename dSYM bundle to match bundle name
    Path dsymPath =
        buildContext
            .getSourcePathResolver()
            .getRelativePath(appleDsym.get().getSourcePathToOutput());
    Path dsymSourcePath = bundleRoot.getParent().resolve(dsymPath.getFileName());
    Path dsymDestinationPath =
        bundleRoot
            .getParent()
            .resolve(bundleRoot.getFileName() + "." + AppleBundleExtension.DSYM.toFileExtension());
    stepsBuilder.add(
        RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    dsymDestinationPath))
            .withRecursive(true));
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
      BuildContext context,
      ImmutableList.Builder<Step> stepsBuilder,
      ImmutableList.Builder<Path> codeSignOnCopyPathsBuilder) {
    for (Map.Entry<SourcePath, String> entry : extensionBundlePaths.entrySet()) {
      Path srcPath = context.getSourcePathResolver().getAbsolutePath(entry.getKey());
      Path destPath = bundleRoot.resolve(entry.getValue());
      stepsBuilder.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), destPath)));
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

  private boolean needsLSRequiresIPhoneOSInfoPlistKeyOnMac() {
    return !extension.equals(AppleBundleExtension.XPC.toFileExtension());
  }

  private ImmutableMap<String, NSObject> getInfoPlistOverrideKeys() {
    ImmutableMap.Builder<String, NSObject> keys = ImmutableMap.builder();

    if (platform.getType() == ApplePlatformType.MAC) {
      if (needsLSRequiresIPhoneOSInfoPlistKeyOnMac()) {
        keys.put("LSRequiresIPhoneOS", new NSNumber(false));
      }
    } else if (!platform.getType().isWatch() && !isLegacyWatchApp()) {
      keys.put("LSRequiresIPhoneOS", new NSNumber(true));
    }

    return keys.build();
  }

  private boolean needsAppInfoPlistKeysOnMac() {
    // XPC bundles on macOS don't require app-specific keys
    // (which also confuses Finder in displaying the XPC bundles as apps)
    return !extension.equals(AppleBundleExtension.XPC.toFileExtension());
  }

  private ImmutableMap<String, NSObject> getInfoPlistAdditionalKeys() {
    ImmutableMap.Builder<String, NSObject> keys = ImmutableMap.builder();

    switch (platform.getType()) {
      case MAC:
        if (needsAppInfoPlistKeysOnMac()) {
          keys.put("NSHighResolutionCapable", new NSNumber(true));
          keys.put("NSSupportsAutomaticGraphicsSwitching", new NSNumber(true));
        }
        keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("MacOSX")));
        break;
      case IOS_DEVICE:
        keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("iPhoneOS")));
        break;
      case IOS_SIMULATOR:
        keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("iPhoneSimulator")));
        break;
      case WATCH_DEVICE:
        if (!isLegacyWatchApp()) {
          keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("WatchOS")));
        }
        break;
      case WATCH_SIMULATOR:
        if (!isLegacyWatchApp()) {
          keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("WatchSimulator")));
        }
        break;
      case TV_DEVICE:
      case TV_SIMULATOR:
      case UNKNOWN:
        break;
    }

    keys.put("DTPlatformName", new NSString(platform.getName()));
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
    boolean shouldCopySwiftStdlib = !extension.equals(AppleBundleExtension.APPEX.toFileExtension());
    if (swiftStdlibTool.isPresent() && shouldCopySwiftStdlib) {
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
              BuildTargetPaths.getScratchPath(
                  getProjectFilesystem(), getBuildTarget(), tempDirPattern),
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
          BuildTargetPaths.getScratchPath(
              getProjectFilesystem(), getBuildTarget(), "%s.storyboardc");
      stepsBuilder.add(
          new IbtoolStep(
              getProjectFilesystem(),
              ibtool.getEnvironment(resolver),
              ibtool.getCommandPrefix(resolver),
              ibtoolModuleParams,
              ImmutableList.of("--target-device", "watch", "--compile"),
              sourcePath,
              compiledStoryboardPath));

      stepsBuilder.add(
          new IbtoolStep(
              getProjectFilesystem(),
              ibtool.getEnvironment(resolver),
              ibtool.getCommandPrefix(resolver),
              ibtoolModuleParams,
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
              ibtoolModuleParams,
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
                ibtoolModuleParams,
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
  public CxxPreprocessorInput getPrivateCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    if (binary.isPresent()) {
      BuildRule binaryRule = binary.get();
      if (binaryRule instanceof NativeTestable) {
        return ((NativeTestable) binaryRule)
            .getPrivateCxxPreprocessorInput(cxxPlatform, graphBuilder);
      }
    }
    return CxxPreprocessorInput.of();
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
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    // When "running" an app bundle, ensure debug symbols are available.
    if (binary.get() instanceof HasAppleDebugSymbolDeps) {
      List<BuildRule> symbolDeps =
          ((HasAppleDebugSymbolDeps) binary.get())
              .getAppleDebugSymbolDeps()
              .collect(Collectors.toList());
      if (!symbolDeps.isEmpty()) {
        return Stream.concat(Stream.of(binary.get()), symbolDeps.stream())
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
        .addArg(SourcePathArg.of(PathSourcePath.of(getProjectFilesystem(), bundleBinaryPath)))
        .build();
  }
}
