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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.NativeTestable;
import com.facebook.buck.cxx.Tool;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.FindAndReplaceStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Creates a bundle: a directory containing files and subdirectories, described by an Info.plist.
 */
public class AppleBundle extends AbstractBuildRule implements NativeTestable {
  private static final Logger LOG = Logger.get(AppleBundle.class);

  @AddToRuleKey
  private final String extension;

  @AddToRuleKey
  private final Optional<SourcePath> infoPlist;

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
  private final Tool ibtool;

  @AddToRuleKey
  private final Tool dsymutil;

  @AddToRuleKey
  private final ImmutableSortedSet<BuildTarget> tests;

  private final ImmutableSet<AppleAssetCatalog> bundledAssetCatalogs;

  private final Optional<AppleAssetCatalog> mergedAssetCatalog;

  private final String binaryName;
  private final Path bundleRoot;
  private final Path binaryPath;

  AppleBundle(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Either<AppleBundleExtension, String> extension,
      Optional<SourcePath> infoPlist,
      Map<String, String> infoPlistSubstitutions,
      Optional<BuildRule> binary,
      AppleBundleDestinations destinations,
      Set<SourcePath> resourceDirs,
      Set<SourcePath> resourceFiles,
      Tool ibtool,
      Tool dsymutil,
      Set<AppleAssetCatalog> bundledAssetCatalogs,
      Optional<AppleAssetCatalog> mergedAssetCatalog,
      Set<BuildTarget> tests) {
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
    this.ibtool = ibtool;
    this.dsymutil = dsymutil;
    this.bundledAssetCatalogs = ImmutableSet.copyOf(bundledAssetCatalogs);
    this.mergedAssetCatalog = mergedAssetCatalog;
    this.binaryName = getBinaryName(getBuildTarget());
    this.bundleRoot = getBundleRoot(getBuildTarget(), this.extension);
    this.binaryPath = this.destinations.getExecutablesPath()
        .resolve(this.binaryName);
    this.tests = ImmutableSortedSet.copyOf(tests);
  }

  private static String getBinaryName(BuildTarget buildTarget) {
    return buildTarget.getShortName();
  }

  public static Path getBundleRoot(BuildTarget buildTarget, String extension) {
    return BuildTargets
        .getGenPath(buildTarget, "%s")
        .resolve(getBinaryName(buildTarget) + "." + extension);
  }

  @Override
  @Nullable
  public Path getPathToOutput() {
    return bundleRoot;
  }

  public Path getUnzippedOutputFilePathToBinary() {
    return this.binaryPath;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();

    Path metadataPath = bundleRoot.resolve(this.destinations.getMetadataPath());

    stepsBuilder.add(
        new MakeCleanDirectoryStep(bundleRoot),
        new MkdirStep(metadataPath),
        // TODO(user): This is only appropriate for .app bundles.
        new WriteFileStep("APPLWRUN", metadataPath.resolve("PkgInfo")),
        new FindAndReplaceStep(
            getResolver().getPath(infoPlist.get()),
            metadataPath.resolve("Info.plist"),
            InfoPlistSubstitution.createVariableExpansionFunction(
                withDefaults(
                    infoPlistSubstitutions,
                    ImmutableMap.of(
                        "EXECUTABLE_NAME", binaryName,
                        "PRODUCT_NAME", binaryName
                    ))
            )));

    if (binary.isPresent()) {
      stepsBuilder.add(
          new MkdirStep(bundleRoot.resolve(this.destinations.getExecutablesPath())));
      Path bundleBinaryPath = bundleRoot.resolve(binaryPath);
      stepsBuilder.add(
          CopyStep.forFile(
              binary.get().getPathToOutput(),
              bundleBinaryPath));
      stepsBuilder.add(
          new DsymStep(
              dsymutil.getCommandPrefix(getResolver()),
              bundleBinaryPath,
              bundleBinaryPath.resolveSibling(
                  bundleBinaryPath.getFileName().toString() + ".dSYM")));
    }

    for (SourcePath dir : resourceDirs) {
      Path bundleDestinationPath = bundleRoot.resolve(this.destinations.getResourcesPath());
      stepsBuilder.add(new MkdirStep(bundleDestinationPath));
      stepsBuilder.add(
          CopyStep.forDirectory(
              getResolver().getPath(dir),
              bundleDestinationPath,
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
    }
    for (SourcePath file : resourceFiles) {
      Path bundleDestinationPath = bundleRoot.resolve(this.destinations.getResourcesPath());
      stepsBuilder.add(new MkdirStep(bundleDestinationPath));
      Path resolvedFilePath = getResolver().getPath(file);
      Path destinationPath = bundleDestinationPath.resolve(resolvedFilePath.getFileName());
      addResourceProcessingSteps(resolvedFilePath, destinationPath, stepsBuilder);
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
    buildableContext.recordArtifact(bundleRoot);

    return stepsBuilder.build();
  }

  ImmutableMap<String, String> withDefaults(
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

  private void addResourceProcessingSteps(
      Path sourcePath,
      Path destinationPath,
      ImmutableList.Builder<Step> stepsBuilder) {
    String sourcePathExtension = Files.getFileExtension(sourcePath.toString())
        .toLowerCase(Locale.US);
    switch (sourcePathExtension) {
      case "xib":
        String compiledNibFilename = Files.getNameWithoutExtension(destinationPath.toString()) +
            ".nib";
        Path compiledNibPath = destinationPath.getParent().resolve(compiledNibFilename);
        LOG.debug("Compiling XIB %s to NIB %s", sourcePath, destinationPath);
        stepsBuilder.add(
            new IbtoolStep(
                ibtool.getCommandPrefix(getResolver()),
                sourcePath,
                compiledNibPath));
        break;
      default:
        stepsBuilder.add(CopyStep.forFile(sourcePath, destinationPath));
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
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    if (binary.isPresent()) {
      BuildRule binaryRule = binary.get();
      if (binaryRule instanceof NativeTestable) {
        return ((NativeTestable) binaryRule).getCxxPreprocessorInput(cxxPlatform, headerVisibility);
      }
    }
    return CxxPreprocessorInput.EMPTY;
  }
}
