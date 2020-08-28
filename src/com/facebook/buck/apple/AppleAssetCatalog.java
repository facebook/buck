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
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.PathWrapper;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.rules.modern.DefaultOutputPathResolver;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Supplier;

public class AppleAssetCatalog extends AbstractBuildRule {

  public static final Flavor FLAVOR = InternalFlavor.of("apple-asset-catalog");

  private static final String BUNDLE_DIRECTORY_EXTENSION = ".bundle";

  @AddToRuleKey private final ApplePlatform applePlatform;

  @AddToRuleKey private final String targetSDKVersion;

  @AddToRuleKey private final Optional<String> deviceFamily;

  @AddToRuleKey private final Tool actool;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> assetCatalogDirs;

  @AddToRuleKey private final OutputPath outputDirPath;

  @AddToRuleKey private final OutputPath plistOutputPath;

  @AddToRuleKey private final Optional<String> appIcon;

  @AddToRuleKey private final Optional<String> launchImage;

  @AddToRuleKey private final AppleAssetCatalogsCompilationOptions compilationOptions;

  private final Supplier<SortedSet<BuildRule>> buildDepsSupplier;

  @AddToRuleKey private final boolean withDownwardApi;

  private OutputPathResolver outputPathResolver;

  private static final ImmutableSet<String> TYPES_REQUIRING_CONTENTS_JSON =
      ImmutableSet.of(
          "appiconset",
          "brandassets",
          "cubetextureset",
          "dataset",
          "imageset",
          "imagestack",
          "launchimage",
          "mipmapset",
          "sticker",
          "stickerpack",
          "stickersequence",
          "textureset",
          "complicationset");

  AppleAssetCatalog(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ApplePlatform applePlatform,
      String targetSDKVersion,
      Optional<String> maybeDeviceFamily,
      Tool actool,
      ImmutableSortedSet<SourcePath> assetCatalogDirs,
      Optional<String> appIcon,
      Optional<String> launchImage,
      AppleAssetCatalogsCompilationOptions compilationOptions,
      String bundleName,
      boolean withDownwardApi) {
    super(buildTarget, projectFilesystem);
    this.applePlatform = applePlatform;
    this.targetSDKVersion = targetSDKVersion;
    this.deviceFamily = maybeDeviceFamily;
    this.actool = actool;
    this.assetCatalogDirs = assetCatalogDirs;
    this.withDownwardApi = withDownwardApi;
    this.outputDirPath = new OutputPath(bundleName + BUNDLE_DIRECTORY_EXTENSION);
    this.plistOutputPath = new OutputPath("AssetCatalog.plist");
    this.appIcon = appIcon;
    this.launchImage = launchImage;
    this.compilationOptions = compilationOptions;
    this.buildDepsSupplier = BuildableSupport.buildDepsSupplier(this, ruleFinder);
    this.outputPathResolver =
        new DefaultOutputPathResolver(getProjectFilesystem(), getBuildTarget());
  }

  public SourcePath getSourcePathToPlist() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getResolvedPlistPath());
  }

  private RelPath getResolvedPlistPath() {
    return outputPathResolver.resolvePath(plistOutputPath);
  }

  private RelPath getResolvedOutputDirPath() {
    return outputPathResolver.resolvePath(outputDirPath);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();

    RelPath resolvedOutputDirPath = getResolvedOutputDirPath();
    stepsBuilder.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), resolvedOutputDirPath)));

    RelPath resolvedPlistPath = getResolvedPlistPath();
    stepsBuilder.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                resolvedPlistPath.getParent())));
    ImmutableSortedSet<AbsPath> absoluteAssetCatalogDirs =
        context.getSourcePathResolver().getAllAbsolutePaths(assetCatalogDirs);
    stepsBuilder.add(
        new ActoolStep(
            getProjectFilesystem().getRootPath(),
            applePlatform,
            targetSDKVersion,
            deviceFamily,
            actool.getEnvironment(context.getSourcePathResolver()),
            actool.getCommandPrefix(context.getSourcePathResolver()),
            absoluteAssetCatalogDirs.stream()
                .map(PathWrapper::getPath)
                .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder())),
            getProjectFilesystem().resolve(resolvedOutputDirPath).getPath(),
            getProjectFilesystem().resolve(resolvedPlistPath).getPath(),
            appIcon,
            launchImage,
            compilationOptions,
            ProjectFilesystemUtils.relativize(
                getProjectFilesystem().getRootPath(), context.getBuildCellRootPath()),
            withDownwardApi));

    buildableContext.recordArtifact(resolvedOutputDirPath.getPath());
    buildableContext.recordArtifact(resolvedPlistPath.getPath());
    return stepsBuilder.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getResolvedOutputDirPath());
  }

  public static void validateAssetCatalogs(
      ImmutableSortedSet<SourcePath> assetCatalogDirs,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathResolverAdapter sourcePathResolverAdapter,
      ValidationType validationType)
      throws HumanReadableException {
    HashMap<String, Path> catalogPathsForImageNames = new HashMap<>();
    ArrayList<String> errors = new ArrayList<>();

    for (SourcePath assetCatalogDir : assetCatalogDirs) {
      RelPath catalogPath = sourcePathResolverAdapter.getCellUnsafeRelPath(assetCatalogDir);
      if (!catalogPath.getFileName().toString().endsWith(".xcassets")) {
        errors.add(
            String.format(
                "Target %s had asset catalog dir %s - asset catalog dirs must end with .xcassets",
                buildTarget, catalogPath));
        continue;
      }

      switch (validationType) {
        case XCODE:
          continue;
        case STRICT:
          strictlyValidateAssetCatalog(
              catalogPath, catalogPathsForImageNames, errors, projectFilesystem);
      }
    }

    if (!errors.isEmpty()) {
      throw new HumanReadableException(
          String.format("Asset catalogs invalid\n%s", String.join("\n", errors)));
    }
  }

  /*
   * Perform strict validation, guarding against missing Contents.json and duplicate image names.
   */
  private static void strictlyValidateAssetCatalog(
      RelPath catalogPath,
      Map<String, Path> catalogPathsForImageNames,
      List<String> errors,
      ProjectFilesystem projectFilesystem)
      throws HumanReadableException {
    try {
      for (Path asset : projectFilesystem.getDirectoryContents(catalogPath.getPath())) {
        String assetName = asset.getFileName().toString();
        if (assetName.equals("Contents.json")) {
          continue;
        }

        String[] parts = assetName.split("\\.");
        if (parts.length < 2) {
          errors.add(String.format("Unexpected file in %s: '%s'", catalogPath, assetName));
        }
        String assetType = parts[parts.length - 1];

        if (!TYPES_REQUIRING_CONTENTS_JSON.contains(assetType)) {
          continue;
        }
        boolean contentsJsonPresent = false;
        for (Path assetContentPath : projectFilesystem.getDirectoryContents(asset)) {
          String filename = assetContentPath.getFileName().toString();
          if (filename.equals("Contents.json")) {
            contentsJsonPresent = true;
            continue;
          }

          if (!assetType.equals("imageset")) {
            continue;
          }
          // Lowercase asset name in case we're building on a case sensitive file system.
          String filenameKey = filename.toLowerCase();
          if (catalogPathsForImageNames.containsKey(filenameKey)) {
            Path existingCatalogPath = catalogPathsForImageNames.get(filenameKey);
            if (catalogPath.getPath().equals(existingCatalogPath)) {
              continue;
            } else {
              // All asset catalogs (.xcassets directories) get merged into a single directory per
              // apple bundle.
              // Imagesets containing images with identical names can overwrite one another, this is
              // especially
              // problematic if two images share a name but are different
              errors.add(
                  String.format(
                      "%s is included by two asset catalogs: '%s' and '%s'",
                      assetContentPath.getFileName(), catalogPath, existingCatalogPath));
            }
          } else {
            catalogPathsForImageNames.put(filenameKey, catalogPath.getPath());
          }
        }

        if (!contentsJsonPresent) {
          errors.add(String.format("%s doesn't have Contents.json", asset));
        }
      }

    } catch (IOException e) {
      throw new HumanReadableException(
          "Failed to process asset catalog at %s: %s", catalogPath, e.getMessage());
    }
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDepsSupplier.get();
  }

  public enum ValidationType {
    // Roughly match what Xcode is doing, only check whether the directory ends in .xcassets
    XCODE,
    // Guard against duplicate image names and missing Contents.json files
    STRICT
  }
}
