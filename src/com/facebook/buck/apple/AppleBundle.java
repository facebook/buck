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

import com.dd.plist.NSNumber;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.NativeTestable;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasPostBuildSteps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.FindAndReplaceStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Creates a bundle: a directory containing files and subdirectories, described by an Info.plist.
 */
public class AppleBundle extends AbstractBuildRule implements HasPostBuildSteps, NativeTestable {

  public enum DebugInfoFormat {
    /**
     * Produces a binary with the debug map stripped.
     */
    NONE,

    /**
     * Generate a .dSYM file from the binary and its constituent object files.
     */
    DSYM,
  }

  private static final Logger LOG = Logger.get(AppleBundle.class);
  private static final String CODE_SIGN_ENTITLEMENTS = "CODE_SIGN_ENTITLEMENTS";
  private static final String CODE_SIGN_IDENTITY = "CODE_SIGN_IDENTITY";

  @AddToRuleKey
  private final String extension;

  @AddToRuleKey
  private final SourcePath infoPlist;

  @AddToRuleKey
  private final ImmutableMap<String, String> infoPlistSubstitutions;

  @AddToRuleKey
  private final Optional<BuildRule> binary;

  @AddToRuleKey
  private final AppleBundleDestinations destinations;

  @AddToRuleKey
  private final Set<SourcePath> resourceDirs;

  @AddToRuleKey
  private final Set<SourcePath> resourceFiles;

  @AddToRuleKey
  private final Set<SourcePath> dirsContainingResourceDirs;

  @AddToRuleKey
  private final Optional<ImmutableSet<SourcePath>> resourceVariantFiles;

  @AddToRuleKey
  private final Tool ibtool;

  @AddToRuleKey
  private final Tool dsymutil;

  @AddToRuleKey
  private final Tool strip;

  @AddToRuleKey
  private final ImmutableSortedSet<BuildTarget> tests;

  @AddToRuleKey
  private final String platformName;

  @AddToRuleKey
  private final String sdkName;

  @AddToRuleKey
  private final Optional<ImmutableSet<ProvisioningProfileMetadata>> provisioningProfiles;

  @AddToRuleKey
  private final Optional<CodeSignIdentity> codeSignIdentity;

  @AddToRuleKey
  private final DebugInfoFormat debugInfoFormat;

  private final ImmutableSet<SourcePath> extensionBundlePaths;

  private final Optional<AppleAssetCatalog> assetCatalog;

  private final String binaryName;
  private final Path bundleRoot;
  private final Path binaryPath;
  private final Path bundleBinaryPath;
  private final Path dsymPath;
  private final boolean hasBinary;

  AppleBundle(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Either<AppleBundleExtension, String> extension,
      SourcePath infoPlist,
      Map<String, String> infoPlistSubstitutions,
      Optional<BuildRule> binary,
      AppleBundleDestinations destinations,
      Set<SourcePath> resourceDirs,
      Set<SourcePath> resourceFiles,
      Set<SourcePath> dirsContainingResourceDirs,
      ImmutableSet<SourcePath> extensionBundlePaths,
      Optional<ImmutableSet<SourcePath>> resourceVariantFiles,
      Tool ibtool,
      Tool dsymutil,
      Tool strip,
      Optional<AppleAssetCatalog> assetCatalog,
      Set<BuildTarget> tests,
      AppleSdk sdk,
      ImmutableSet<CodeSignIdentity> allValidCodeSignIdentities,
      Optional<SourcePath> provisioningProfileSearchPath,
      DebugInfoFormat debugInfoFormat) {
    super(params, resolver);
    this.extension = extension.isLeft() ?
        extension.getLeft().toFileExtension() :
        extension.getRight();
    this.infoPlist = infoPlist;
    this.infoPlistSubstitutions = ImmutableMap.copyOf(infoPlistSubstitutions);
    this.binary = binary;
    this.destinations = destinations;
    this.resourceDirs = resourceDirs;
    this.resourceFiles = resourceFiles;
    this.dirsContainingResourceDirs = dirsContainingResourceDirs;
    this.extensionBundlePaths = extensionBundlePaths;
    this.resourceVariantFiles = resourceVariantFiles;
    this.ibtool = ibtool;
    this.dsymutil = dsymutil;
    this.strip = strip;
    this.assetCatalog = assetCatalog;
    this.binaryName = getBinaryName(getBuildTarget());
    this.bundleRoot = getBundleRoot(getBuildTarget(), this.extension);
    this.binaryPath = this.destinations.getExecutablesPath()
        .resolve(this.binaryName);
    this.tests = ImmutableSortedSet.copyOf(tests);
    this.platformName = sdk.getApplePlatform().getName();
    this.sdkName = sdk.getName();
    this.debugInfoFormat = debugInfoFormat;

    // We need to resolve the possible set of profiles and code sign identity at construction time
    // because they form part of the rule key.
    if (binary.isPresent() && ApplePlatform.needsCodeSign(this.platformName)) {
      final Path searchPath;
      if (provisioningProfileSearchPath.isPresent()) {
        searchPath = resolver.getResolvedPath(provisioningProfileSearchPath.get());
      } else {
        searchPath = Paths.get(System.getProperty("user.home") +
                "/Library/MobileDevice/Provisioning Profiles");
      }

      Optional<ImmutableSet<ProvisioningProfileMetadata>> provisioningProfiles;
      try {
        provisioningProfiles = Optional.of(
            ProvisioningProfileCopyStep.findProfilesInPath(searchPath));
      } catch (InterruptedException e) {
        // We get here if the user pressed Ctrl-C during the profile discovery step.
        // In this case, we'll fail anyway since the set of profiles will be empty.
        provisioningProfiles = Optional.of(ImmutableSet.<ProvisioningProfileMetadata>of());
      }
      this.provisioningProfiles = provisioningProfiles;

      Optional<CodeSignIdentity> foundIdentity = Optional.absent();
      Optional<String> customIdentity = InfoPlistSubstitution.getVariableExpansionForPlatform(
          CODE_SIGN_IDENTITY,
          this.platformName,
          this.infoPlistSubstitutions);
      if (customIdentity.isPresent()) {
        LOG.debug("Bundle specifies custom code signing identity: " + customIdentity.get());
        if (CodeSignIdentity.isHash(customIdentity.get())) {
          for (CodeSignIdentity identity : allValidCodeSignIdentities) {
            if (identity.getHash().equals(customIdentity.get())) {
              foundIdentity = Optional.of(identity);
              break;
            }
          }
        } else {
          for (CodeSignIdentity identity : allValidCodeSignIdentities) {
            if (identity.getFullName().startsWith(customIdentity.get())) {
              foundIdentity = Optional.of(identity);
              break;
            }
          }
        }
      } else if (!allValidCodeSignIdentities.isEmpty()) {
        LOG.debug("Using default code signing identity");
        Iterator<CodeSignIdentity> it = allValidCodeSignIdentities.iterator();
        foundIdentity = Optional.of(it.next());
      }
      if (!foundIdentity.isPresent()) {
        throw new HumanReadableException("The platform " + platformName + " for this target " +
            "requires code signing but couldn't find a compatible code signing identity to use.");
      }
      LOG.debug("Code signing identity is " + foundIdentity.toString());
      this.codeSignIdentity = foundIdentity;
    } else {
      this.provisioningProfiles = Optional.absent();
      this.codeSignIdentity = Optional.absent();
    }
    bundleBinaryPath = bundleRoot.resolve(binaryPath);
    dsymPath = bundleBinaryPath
        .getParent()
        .getParent()
        .resolve(bundleBinaryPath.getFileName().toString() + ".dSYM");
    hasBinary = binary.isPresent() && binary.get().getPathToOutput() != null;
  }

  public static String getBinaryName(BuildTarget buildTarget) {
    return buildTarget.getShortName();
  }

  public static Path getBundleRoot(BuildTarget buildTarget, String extension) {
    return BuildTargets
        .getGenPath(buildTarget, "%s")
        .resolve(getBinaryName(buildTarget) + "." + extension);
  }

  public String getExtension() {
    return extension;
  }

  @Override
  public Path getPathToOutput() {
    return bundleRoot;
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

  public String getPlatformName() { return platformName; }

  public Optional<BuildRule> getBinary() { return binary; }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();

    Path metadataPath = getMetadataPath();

    Path infoPlistInputPath = getResolver().getPath(infoPlist);
    Path infoPlistSubstitutionTempPath =
        BuildTargets.getScratchPath(getBuildTarget(), "%s.plist");
    Path infoPlistOutputPath = metadataPath.resolve("Info.plist");

    stepsBuilder.add(
        new MakeCleanDirectoryStep(getProjectFilesystem(), bundleRoot),
        new MkdirStep(getProjectFilesystem(), metadataPath),
        // TODO(user): This is only appropriate for .app bundles.
        new WriteFileStep(
            getProjectFilesystem(),
            "APPLWRUN",
            metadataPath.resolve("PkgInfo"),
            /* executable */ false),
        new FindAndReplaceStep(
            getProjectFilesystem(),
            infoPlistInputPath,
            infoPlistSubstitutionTempPath,
            InfoPlistSubstitution.createVariableExpansionFunction(
                withDefaults(
                    infoPlistSubstitutions,
                    ImmutableMap.of(
                        "EXECUTABLE_NAME", binaryName,
                        "PRODUCT_NAME", binaryName
                    ))
            )),
        new PlistProcessStep(
            getProjectFilesystem(),
            infoPlistSubstitutionTempPath,
            infoPlistOutputPath,
            getInfoPlistAdditionalKeys(platformName, sdkName),
            getInfoPlistOverrideKeys(platformName),
            PlistProcessStep.OutputFormat.BINARY));

    if (hasBinary) {
      stepsBuilder.add(
          new MkdirStep(
              getProjectFilesystem(),
              bundleRoot.resolve(this.destinations.getExecutablesPath())));
      Path bundleBinaryPath = bundleRoot.resolve(binaryPath);
      stepsBuilder.add(
          CopyStep.forFile(
              getProjectFilesystem(),
              binary.get().getPathToOutput(),
              bundleBinaryPath));
      if (debugInfoFormat == DebugInfoFormat.DSYM) {
        stepsBuilder.add(
            new DsymStep(
                getProjectFilesystem(),
                dsymutil.getCommandPrefix(getResolver()),
                bundleBinaryPath,
                dsymPath));
      }
      stepsBuilder.add(
          new DefaultShellStep(
              getProjectFilesystem().getRootPath(),
              ImmutableList.<String>builder()
                  .addAll(strip.getCommandPrefix(getResolver()))
                  .add("-S")
                  .add(getProjectFilesystem().resolve(bundleBinaryPath).toString())
                  .build()));
    }

    Path bundleDestinationPath = bundleRoot.resolve(this.destinations.getResourcesPath());
    for (SourcePath dir : resourceDirs) {
      stepsBuilder.add(new MkdirStep(getProjectFilesystem(), bundleDestinationPath));
      stepsBuilder.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              getResolver().getPath(dir),
              bundleDestinationPath,
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
    }
    for (SourcePath dir : dirsContainingResourceDirs) {
      stepsBuilder.add(new MkdirStep(getProjectFilesystem(), bundleDestinationPath));
      stepsBuilder.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              getResolver().getPath(dir),
              bundleDestinationPath,
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }
    for (SourcePath file : resourceFiles) {
      stepsBuilder.add(new MkdirStep(getProjectFilesystem(), bundleDestinationPath));
      Path resolvedFilePath = getResolver().getPath(file);
      Path destinationPath = bundleDestinationPath.resolve(resolvedFilePath.getFileName());
      addResourceProcessingSteps(resolvedFilePath, destinationPath, stepsBuilder);
    }

    addStepsToCopyExtensionBundlesDependencies(stepsBuilder);

    if (resourceVariantFiles.isPresent()) {
      for (SourcePath variantSourcePath : resourceVariantFiles.get()) {
        Path variantFilePath = getResolver().getPath(variantSourcePath);

        Path variantDirectory = variantFilePath.getParent();
        if (variantDirectory == null || !variantDirectory.toString().endsWith(".lproj")) {
          throw new HumanReadableException(
              "Variant files have to be in a directory with name ending in '.lproj', " +
                  "but '%s' is not.",
              variantFilePath);
        }

        Path bundleVariantDestinationPath =
            bundleDestinationPath.resolve(variantDirectory.getFileName());
        stepsBuilder.add(new MkdirStep(getProjectFilesystem(), bundleVariantDestinationPath));

        Path destinationPath = bundleVariantDestinationPath.resolve(variantFilePath.getFileName());
        addResourceProcessingSteps(variantFilePath, destinationPath, stepsBuilder);
      }
    }

    if (assetCatalog.isPresent()) {
      Path bundleDir = assetCatalog.get().getOutputDir();
      stepsBuilder.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              bundleDir,
              bundleRoot,
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }

    // Copy the .mobileprovision file if the platform requires it.
    if (provisioningProfiles.isPresent()) {
      Optional<Path> entitlementsPlist = Optional.absent();
      final String srcRoot = getProjectFilesystem().getRootPath().resolve(
          getBuildTarget().getBasePath()).toString();
      Optional<String> entitlementsPlistString =
          InfoPlistSubstitution.getVariableExpansionForPlatform(
              CODE_SIGN_ENTITLEMENTS,
              platformName,
              withDefaults(
                  infoPlistSubstitutions,
                  ImmutableMap.of(
                      "SOURCE_ROOT", srcRoot,
                      "SRCROOT", srcRoot
                  )));
      if (entitlementsPlistString.isPresent()) {
        entitlementsPlist = Optional.of(Paths.get(entitlementsPlistString.get()));
      }

      final Path signingEntitlementsTempPath =
          BuildTargets.getScratchPath(getBuildTarget(), "%s.xcent");

      stepsBuilder.add(
          new ProvisioningProfileCopyStep(
              getProjectFilesystem(),
              infoPlistOutputPath,
              Optional.<String>absent(),  // Provisioning profile UUID -- find automatically.
              entitlementsPlist,
              provisioningProfiles.get(),
              bundleDestinationPath.resolve("embedded.mobileprovision"),
              signingEntitlementsTempPath)
      );

      stepsBuilder.add(
          new CodeSignStep(
              getProjectFilesystem().getRootPath(),
              bundleDestinationPath,
              signingEntitlementsTempPath,
              codeSignIdentity.get().getHash()
          )
      );
    }

    // Ensure the bundle directory is archived so we can fetch it later.
    buildableContext.recordArtifact(getPathToOutput());

    return stepsBuilder.build();
  }

  @Override
  public ImmutableList<Step> getPostBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    if (!hasBinary || debugInfoFormat == DebugInfoFormat.NONE) {
      return ImmutableList.of();
    }
    return ImmutableList.<Step>of(
        new Step() {
          @Override
          public int execute(ExecutionContext context) throws IOException, InterruptedException {
            ProcessExecutorParams params = ProcessExecutorParams
                .builder()
                .addCommand("lldb")
                .build();
            return context.getProcessExecutor().launchAndExecute(
                params,
                ImmutableSet.<ProcessExecutor.Option>of(),
                Optional.of(
                    String.format("target create %s\ntarget symbols add %s", bundleRoot, dsymPath)),
                Optional.<Long>absent(),
                Optional.<Function<Process, Void>>absent()).getExitCode();
          }

          @Override
          public String getShortName() {
            return "register debug symbols";
          }

          @Override
          public String getDescription(ExecutionContext context) {
            return String.format(
                "register debug symbols for binary '%s': '%s'",
                bundleRoot,
                dsymPath);
          }
        });
  }

  public void addStepsToCopyExtensionBundlesDependencies(
      ImmutableList.Builder<Step> stepsBuilder) {
    for (SourcePath sourcePath : extensionBundlePaths) {
      Path plugInsDestPath = bundleRoot.resolve(destinations.getPlugInsPath());
      stepsBuilder.add(new MkdirStep(getProjectFilesystem(), plugInsDestPath));
      stepsBuilder.add(
        CopyStep.forDirectory(
            getProjectFilesystem(),
            getResolver().getPath(sourcePath),
            plugInsDestPath,
            CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
    }
  }

  static ImmutableMap<String, String> withDefaults(
      ImmutableMap<String, String> map,
      ImmutableMap<String, String> defaults) {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.<String, String>builder()
        .putAll(map);
    for (ImmutableMap.Entry<String, String> entry : defaults.entrySet()) {
      if (!map.containsKey(entry.getKey())) {
        builder = builder.put(entry.getKey(), entry.getValue());
      }
    }
    return builder.build();
  }

  static ImmutableMap<String, NSObject> getInfoPlistOverrideKeys(
      String platformName) {
    ImmutableMap.Builder<String, NSObject> keys = ImmutableMap.builder();

    if (platformName.contains("osx")) {
      keys.put("LSRequiresIPhoneOS", new NSNumber(false));
    } else {
      keys.put("LSRequiresIPhoneOS", new NSNumber(true));
    }

    return keys.build();
  }

  static ImmutableMap<String, NSObject> getInfoPlistAdditionalKeys(
      String platformName,
      String sdkName) {
    ImmutableMap.Builder<String, NSObject> keys = ImmutableMap.builder();

    if (platformName.contains("osx")) {
      keys.put("NSHighResolutionCapable", new NSNumber(true));
      keys.put("NSSupportsAutomaticGraphicsSwitching", new NSNumber(true));
    }

    keys.put("DTPlatformName", new NSString(platformName));
    keys.put("DTSDKName", new NSString(sdkName));

    return keys.build();
  }

  private void addResourceProcessingSteps(
      Path sourcePath,
      Path destinationPath,
      ImmutableList.Builder<Step> stepsBuilder) {
    String sourcePathExtension = Files.getFileExtension(sourcePath.toString())
        .toLowerCase(Locale.US);
    switch (sourcePathExtension) {
      case "plist":
      case "stringsdict":
        LOG.debug("Converting plist %s to binary plist %s", sourcePath, destinationPath);
        stepsBuilder.add(
            new PlistProcessStep(
                getProjectFilesystem(),
                sourcePath,
                destinationPath,
                ImmutableMap.<String, NSObject>of(),
                ImmutableMap.<String, NSObject>of(),
                PlistProcessStep.OutputFormat.BINARY));
        break;
      case "xib":
        String compiledNibFilename = Files.getNameWithoutExtension(destinationPath.toString()) +
            ".nib";
        Path compiledNibPath = destinationPath.getParent().resolve(compiledNibFilename);
        LOG.debug("Compiling XIB %s to NIB %s", sourcePath, destinationPath);
        stepsBuilder.add(
            new IbtoolStep(
                getProjectFilesystem(),
                ibtool.getCommandPrefix(getResolver()),
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
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    if (binary.isPresent()) {
      BuildRule binaryRule = binary.get();
      if (binaryRule instanceof NativeTestable) {
        return ((NativeTestable) binaryRule).getCxxPreprocessorInput(
            targetGraph,
            cxxPlatform,
            headerVisibility);
      }
    }
    return CxxPreprocessorInput.EMPTY;
  }
}
