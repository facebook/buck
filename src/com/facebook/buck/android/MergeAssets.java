/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.android.resources.ResourcesZipBuilder;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.TreeMultimap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import com.google.common.io.Files;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * MergeAssets adds the assets for an APK into the output of aapt.
 *
 * <p>Android's ApkBuilder seemingly would do this, but it doesn't actually compress the assets that
 * are added.
 */
public class MergeAssets extends AbstractBuildRule {
  // TODO(cjhopman): This should be an input-based rule, but the asset directories are from symlink
  // trees and the file hash caches don't currently handle those correctly. The symlink trees
  // shouldn't actually be necessary anymore as we can just take the full list of source paths
  // directly here.
  @AddToRuleKey private final ImmutableSet<SourcePath> assetsDirectories;
  @AddToRuleKey private Optional<SourcePath> baseApk;

  private final Supplier<ImmutableSortedSet<BuildRule>> buildDepsSupplier;

  public MergeAssets(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Optional<SourcePath> baseApk,
      ImmutableSortedSet<SourcePath> assetsDirectories) {
    super(buildTarget, projectFilesystem);
    this.baseApk = baseApk;
    this.assetsDirectories = assetsDirectories;
    this.buildDepsSupplier =
        MoreSuppliers.memoize(
            () ->
                BuildableSupport.deriveDeps(this, ruleFinder)
                    .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDepsSupplier.get();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    SourcePathResolver pathResolver = context.getSourcePathResolver();
    TreeMultimap<Path, Path> assets = TreeMultimap.create();

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                getPathToMergedAssets().getParent())));
    steps.add(
        new AbstractExecutionStep("finding_assets") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) throws IOException {
            for (SourcePath sourcePath : assetsDirectories) {
              Path relativePath = pathResolver.getRelativePath(sourcePath);
              Path absolutePath = pathResolver.getAbsolutePath(sourcePath);
              ProjectFilesystem assetFilesystem = pathResolver.getFilesystem(sourcePath);
              assetFilesystem.walkFileTree(
                  relativePath,
                  new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                      Preconditions.checkState(
                          !Files.getFileExtension(file.toString()).equals("gz"),
                          "BUCK doesn't support adding .gz files to assets (%s).",
                          file);
                      assets.put(absolutePath, absolutePath.relativize(file.normalize()));
                      return super.visitFile(file, attrs);
                    }
                  });
            }
            return StepExecutionResults.SUCCESS;
          }
        });
    steps.add(
        new MergeAssetsStep(
            getProjectFilesystem().getPathForRelativePath(getPathToMergedAssets()),
            baseApk.map(pathResolver::getAbsolutePath),
            assets));
    buildableContext.recordArtifact(getPathToMergedAssets());
    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathToMergedAssets());
  }

  public Path getPathToMergedAssets() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/merged.assets.ap_");
  }

  private static class MergeAssetsStep extends AbstractExecutionStep {
    // See
    // https://android.googlesource.com/platform/frameworks/base.git/+/nougat-release/tools/aapt/Package.cpp
    private static final ImmutableSet<String> NO_COMPRESS_EXTENSIONS =
        ImmutableSet.of(
            "jpg", "jpeg", "png", "gif", "wav", "mp2", "mp3", "ogg", "aac", "mpg", "mpeg", "mid",
            "midi", "smf", "jet", "rtttl", "imy", "xmf", "mp4", "m4a", "m4v", "3gp", "3gpp", "3g2",
            "3gpp2", "amr", "awb", "wma", "wmv", "webm", "mkv");

    private final Path pathToMergedAssets;
    private final Optional<Path> pathToBaseApk;
    private final TreeMultimap<Path, Path> assets;

    public MergeAssetsStep(
        Path pathToMergedAssets, Optional<Path> pathToBaseApk, TreeMultimap<Path, Path> assets) {
      super("merging_assets");
      this.pathToMergedAssets = pathToMergedAssets;
      this.pathToBaseApk = pathToBaseApk;
      this.assets = assets;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context)
        throws IOException, InterruptedException {
      try (ResourcesZipBuilder output = new ResourcesZipBuilder(pathToMergedAssets)) {
        if (pathToBaseApk.isPresent()) {
          try (ZipFile base = new ZipFile(pathToBaseApk.get().toFile())) {
            for (ZipEntry inputEntry : Collections.list(base.entries())) {
              String extension = Files.getFileExtension(inputEntry.getName());
              // Only compress if aapt compressed it and the extension looks compressible.
              // This is a workaround for aapt2 compressing everything.
              boolean shouldCompress =
                  inputEntry.getMethod() != ZipEntry.STORED
                      && !NO_COMPRESS_EXTENSIONS.contains(extension);
              try (InputStream stream = base.getInputStream(inputEntry)) {
                output.addEntry(
                    stream,
                    inputEntry.getSize(),
                    inputEntry.getCrc(),
                    inputEntry.getName(),
                    shouldCompress ? Deflater.BEST_COMPRESSION : 0,
                    inputEntry.isDirectory());
              }
            }
          }
        }
        Path assetsZipRoot = Paths.get("assets");
        for (Path assetRoot : assets.keySet()) {
          for (Path asset : assets.get(assetRoot)) {
            ByteSource assetSource = Files.asByteSource(assetRoot.resolve(asset).toFile());
            HashCode assetCrc32 = assetSource.hash(Hashing.crc32());
            String extension = Files.getFileExtension(asset.toString());
            int compression =
                NO_COMPRESS_EXTENSIONS.contains(extension) ? 0 : Deflater.BEST_COMPRESSION;
            try (InputStream assetStream = assetSource.openStream()) {
              output.addEntry(
                  assetStream,
                  assetSource.size(),
                  // CRC32s are only 32 bits, but setCrc() takes a
                  // long.  Avoid sign-extension here during the
                  // conversion to long by masking off the high 32 bits.
                  assetCrc32.asInt() & 0xFFFFFFFFL,
                  assetsZipRoot.resolve(asset).toString(),
                  compression,
                  false);
            }
          }
        }
      }
      return StepExecutionResults.SUCCESS;
    }
  }
}
