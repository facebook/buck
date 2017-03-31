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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.zip.CustomZipEntry;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.TreeMultimap;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MergeAssets adds the assets for an APK into the output of aapt.
 *
 * Android's ApkBuilder seemingly would do this, but it doesn't actually compress the assets that
 * are added.
 */
public class MergeAssets extends AbstractBuildRule {
  // TODO(cjhopman): This should be an input-based rule, but the asset directories are from symlink
  // trees and the file hash caches don't currently handle those correctly. The symlink trees
  // shouldn't actually be necessary anymore as we can just take the full list of source paths
  // directly here.
  @AddToRuleKey
  private final ImmutableSet<SourcePath> assetsDirectories;
  @AddToRuleKey
  private SourcePath baseApk;

  public MergeAssets(
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      SourcePath baseApk,
      ImmutableSortedSet<SourcePath> assetsDirectories) {
    super(buildRuleParams
        .copyAppendingExtraDeps(
        ImmutableSortedSet.copyOf(
            ruleFinder.filterBuildRuleInputs(
                FluentIterable.from(assetsDirectories).append(baseApk)))));
    this.baseApk = baseApk;
    this.assetsDirectories = assetsDirectories;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    SourcePathResolver pathResolver = context.getSourcePathResolver();
    TreeMultimap<Path, Path> assets = TreeMultimap.create();

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.addAll(MakeCleanDirectoryStep.of(
        getProjectFilesystem(), getPathToMergedAssets().getParent()));
    steps.add(new AbstractExecutionStep("finding_assets") {
      @Override
      public StepExecutionResult execute(ExecutionContext context)
          throws IOException, InterruptedException {
        for (SourcePath sourcePath : assetsDirectories) {
          try {
            Path relativePath = pathResolver.getRelativePath(sourcePath);
            Path absolutePath = pathResolver.getAbsolutePath(sourcePath);
            ProjectFilesystem assetFilesystem = pathResolver.getFilesystem(sourcePath);
            assetFilesystem.walkFileTree(relativePath, new SimpleFileVisitor<Path>() {
              @Override
              public FileVisitResult visitFile(
                  Path file, BasicFileAttributes attrs) throws IOException {
                Preconditions.checkState(
                    !Files.getFileExtension(file.toString()).equals("gz"),
                    "BUCK doesn't support adding .gz files to assets (%s).",
                    file
                );
                assets.put(absolutePath, absolutePath.relativize(file));
                return super.visitFile(file, attrs);
              }
            });
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
        return StepExecutionResult.SUCCESS;
      }
    });
    steps.add(new MergeAssetsStep(
        getProjectFilesystem().getPathForRelativePath(getPathToMergedAssets()),
        pathResolver.getAbsolutePath(baseApk),
        assets));
    buildableContext.recordArtifact(getPathToMergedAssets());
    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), getPathToMergedAssets());
  }

  public Path getPathToMergedAssets() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/merged.assets.ap_");
  }

  private static class MergeAssetsStep extends AbstractExecutionStep {
    // See https://android.googlesource.com/platform/frameworks/base.git/+/nougat-release/tools/aapt/Package.cpp
    private static final ImmutableSet<String> NO_COMPRESS_EXTENSIONS = ImmutableSet.of(
        "jpg", "jpeg", "png", "gif",
        "wav", "mp2", "mp3", "ogg", "aac",
        "mpg", "mpeg", "mid", "midi", "smf", "jet",
        "rtttl", "imy", "xmf", "mp4", "m4a",
        "m4v", "3gp", "3gpp", "3g2", "3gpp2",
        "amr", "awb", "wma", "wmv", "webm", "mkv"
    );

    private final Path pathToMergedAssets;
    private final Path pathToBaseApk;
    private final TreeMultimap<Path, Path> assets;

    public MergeAssetsStep(
        Path pathToMergedAssets,
        Path pathToBaseApk,
        TreeMultimap<Path, Path> assets) {
      super("merging_assets");
      this.pathToMergedAssets = pathToMergedAssets;
      this.pathToBaseApk = pathToBaseApk;
      this.assets = assets;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context)
        throws IOException, InterruptedException {
      try (CustomZipOutputStream output = ZipOutputStreams.newOutputStream(pathToMergedAssets)) {
        try (ZipInputStream base =
                 new ZipInputStream(
                     new BufferedInputStream(
                         new FileInputStream(pathToBaseApk.toFile())
            )
        )) {
          for (ZipEntry inputEntry = base.getNextEntry();
               inputEntry != null;
               base.closeEntry(), inputEntry = base.getNextEntry()) {
            byte[] data = ByteStreams.toByteArray(base);
            addEntry(
                output,
                data,
                inputEntry.getName(),
                inputEntry.getMethod() == ZipEntry.STORED ? 0 : Deflater.BEST_COMPRESSION,
                inputEntry.isDirectory());
          }
        }
        Path assetsZipRoot = Paths.get("assets");
        for (Path assetRoot : assets.keySet()) {
          for (Path asset : assets.get(assetRoot)) {
            File file = assetRoot.resolve(asset).toFile();
            byte[] data = Files.toByteArray(file);
            String extension = Files.getFileExtension(asset.toString());
            int compression =
                NO_COMPRESS_EXTENSIONS.contains(extension) ? 0 : Deflater.BEST_COMPRESSION;
            addEntry(
                output,
                data,
                assetsZipRoot.resolve(asset).toString(),
                compression,
                false);
          }
        }
        return StepExecutionResult.SUCCESS;
      }
    }

    private void addEntry(
        CustomZipOutputStream output,
        byte[] data,
        String name,
        int compressionLevel,
        boolean isDirectory) throws IOException {
      CustomZipEntry outputEntry = new CustomZipEntry(
          Paths.get(name),
          isDirectory);
      outputEntry.setCompressionLevel(compressionLevel);
      CRC32 crc = new CRC32();
      crc.update(data);
      outputEntry.setCrc(crc.getValue());
      if (compressionLevel == 0) {
        outputEntry.setCompressedSize(data.length);
      }
      outputEntry.setSize(data.length);
      output.putNextEntry(outputEntry);
      output.write(data);
      output.closeEntry();
    }
  }
}
