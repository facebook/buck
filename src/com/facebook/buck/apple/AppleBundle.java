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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.AppleBundleDestination;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.FindAndReplaceStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.zip.ZipStep;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Creates a bundle: a directory containing files and subdirectories, described by an Info.plist.
 */
public class AppleBundle extends AbstractBuildRule {

  @AddToRuleKey
  private final String extension;

  @AddToRuleKey
  private final Optional<SourcePath> infoPlist;

  @AddToRuleKey
  private final ImmutableMap<String, String> infoPlistSubstitutions;

  @AddToRuleKey
  private final Optional<BuildRule> binary;

  @AddToRuleKey
  private final ImmutableMap<AppleBundleDestination.SubfolderSpec, String> bundleSubfolders;

  @AddToRuleKey
  private final ImmutableMap<Path, AppleBundleDestination> dirs;

  @AddToRuleKey
  private final ImmutableMap<SourcePath, AppleBundleDestination> files;

  private final ImmutableSet<AppleAssetCatalog> bundledAssetCatalogs;

  private final Optional<AppleAssetCatalog> mergedAssetCatalog;

  private final Path outputZipPath;

  private final String binaryName;
  private final Path bundleRoot;
  private final Path executablesPath;
  private final Path binaryPath;

  AppleBundle(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Either<AppleBundleExtension, String> extension,
      Optional<SourcePath> infoPlist,
      Map<String, String> infoPlistSubstitutions,
      Optional<BuildRule> binary,
      Map<AppleBundleDestination.SubfolderSpec, String> bundleSubfolders,
      Map<Path, AppleBundleDestination> dirs,
      Map<SourcePath, AppleBundleDestination> files,
      Set<AppleAssetCatalog> bundledAssetCatalogs,
      Optional<AppleAssetCatalog> mergedAssetCatalog) {
    super(params, resolver);
    this.extension = extension.isLeft() ?
        extension.getLeft().toFileExtension() :
        extension.getRight();
    this.infoPlist = infoPlist;
    this.infoPlistSubstitutions = ImmutableMap.copyOf(infoPlistSubstitutions);
    this.binary = binary;
    this.bundleSubfolders = ImmutableMap.copyOf(bundleSubfolders);
    this.dirs = ImmutableMap.copyOf(dirs);
    this.files = ImmutableMap.copyOf(files);
    this.outputZipPath = BuildTargets.getGenPath(
        params.getBuildTarget(),
        "%s.zip");
    this.bundledAssetCatalogs = ImmutableSet.copyOf(bundledAssetCatalogs);
    this.mergedAssetCatalog = mergedAssetCatalog;
    this.binaryName = getBuildTarget().getShortName();
    this.bundleRoot = BuildTargets.getGenPath(getBuildTarget(), "%s")
        .resolve(binaryName + "." + this.extension);
    this.executablesPath =
        Paths.get(this.bundleSubfolders.get(AppleBundleDestination.SubfolderSpec.EXECUTABLES));
    this.binaryPath = this.executablesPath.resolve(this.binaryName);
  }

  @Override
  @Nullable
  public Path getPathToOutputFile() {
    return outputZipPath;
  }

  public Path getUnzippedOutputFilePathToBinary() {
    return this.binaryPath;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    // EXECUTABLE_NAME and PRODUCT_NAME default to the binary name, but can be overridden.
    ImmutableMap<String, String> plistVariables = ImmutableMap.<String, String>builder()
        .put("EXECUTABLE_NAME", binaryName)
        .put("PRODUCT_NAME", binaryName)
        .putAll(infoPlistSubstitutions)
        .build();
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();

    Path productsPath = bundleRoot.resolve(
        bundleSubfolders.get(AppleBundleDestination.SubfolderSpec.PRODUCTS));

    stepsBuilder.add(
        new MakeCleanDirectoryStep(bundleRoot),
        // TODO(user): This is only appropriate for .app bundles.
        new WriteFileStep("APPLWRUN", bundleRoot.resolve("PkgInfo")),
        new MkdirStep(productsPath),
        new FindAndReplaceStep(
            getResolver().getPath(infoPlist.get()),
            productsPath.resolve("Info.plist"),
            InfoPlistSubstitution.createVariableExpansionFunction(
                plistVariables
            )));

    if (binary.isPresent()) {
      stepsBuilder.add(new MkdirStep(bundleRoot.resolve(executablesPath)));
      stepsBuilder.add(
          CopyStep.forFile(
              binary.get().getPathToOutputFile(),
              bundleRoot.resolve(binaryPath)));
    }

    for (Map.Entry<Path, AppleBundleDestination> dirEntry : dirs.entrySet()) {
      Path bundleDestinationPath = getBundleDestinationPath(
          bundleRoot,
          bundleSubfolders,
          dirEntry.getValue());
      stepsBuilder.add(new MkdirStep(bundleDestinationPath));
      stepsBuilder.add(
          CopyStep.forDirectory(
              dirEntry.getKey(),
              bundleDestinationPath,
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
    }
    for (Map.Entry<SourcePath, AppleBundleDestination> fileEntry : files.entrySet()) {
      Path bundleDestinationPath = getBundleDestinationPath(
          bundleRoot,
          bundleSubfolders,
          fileEntry.getValue());
      stepsBuilder.add(new MkdirStep(bundleDestinationPath));
      Path resolvedFilePath = getResolver().getPath(fileEntry.getKey());
      stepsBuilder.add(
          CopyStep.forFile(
              resolvedFilePath,
              bundleDestinationPath.resolve(resolvedFilePath.getFileName())));
    }

    for (AppleAssetCatalog bundledAssetCatalog : bundledAssetCatalogs) {
      Path bundleDir = bundledAssetCatalog.getOutputDir();
      stepsBuilder.add(
          CopyStep.forDirectory(
              bundleDir,
              bundleRoot,
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
    }

    if (mergedAssetCatalog.isPresent()) {
      Path bundleDir = mergedAssetCatalog.get().getOutputDir();
      stepsBuilder.add(
          CopyStep.forDirectory(
              bundleDir,
              bundleRoot,
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }

    // Ensure the bundle directory is archived so we can fetch it later.
    buildableContext.recordArtifactsInDirectory(bundleRoot);

    // A bundle is a directory by definition, but a BuildRule has to
    // output a single file.
    //
    // Create an uncompressed zip to hold the bundle directory so we
    // can refer to the output of this rule elsewhere.
    stepsBuilder.add(new RmStep(outputZipPath, /* shouldForceDeletion */ true));
    stepsBuilder.add(
        new ZipStep(
            outputZipPath,
            ImmutableSet.<Path>of(),
            false, /* junkPaths */
            ZipStep.MIN_COMPRESSION_LEVEL,
            bundleRoot));
    return stepsBuilder.build();
  }

  private static Path getBundleDestinationPath(
      Path bundleRoot,
      ImmutableMap<AppleBundleDestination.SubfolderSpec, String> bundleSubfolders,
      AppleBundleDestination dest) {
    return bundleRoot
        .resolve(bundleSubfolders.get(dest.getSubfolderSpec()))
        .resolve(dest.getSubpath().or(""));
  }

}
