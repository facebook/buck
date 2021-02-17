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

package com.facebook.buck.step.isolatedsteps.android;

import com.facebook.buck.android.resources.ResourcesZipBuilder;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
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
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Step to construct build outputs for exo-for-resources. */
public class MergeAssetsStep extends IsolatedStep {
  // See
  // https://android.googlesource.com/platform/frameworks/base.git/+/nougat-release/tools/aapt/Package.cpp
  private static final ImmutableSet<String> NO_COMPRESS_EXTENSIONS =
      ImmutableSet.of(
          "jpg", "jpeg", "png", "gif", "wav", "mp2", "mp3", "ogg", "aac", "mpg", "mpeg", "mid",
          "midi", "smf", "jet", "rtttl", "imy", "xmf", "mp4", "m4a", "m4v", "3gp", "3gpp", "3g2",
          "3gpp2", "amr", "awb", "wma", "wmv", "webm", "mkv", "tflite");

  private final RelPath relativePathToMergedAssets;
  private final Optional<RelPath> relativePathToBaseApk;
  private final ImmutableSet<RelPath> assetsDirectories;

  public MergeAssetsStep(
      RelPath relativePathToMergedAssets,
      Optional<RelPath> relativePathToBaseApk,
      ImmutableSet<RelPath> assetsDirectories) {
    this.relativePathToMergedAssets = relativePathToMergedAssets;
    this.relativePathToBaseApk = relativePathToBaseApk;
    this.assetsDirectories = assetsDirectories;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    TreeMultimap<Path, Path> assets = getAllAssets(context);

    try (ResourcesZipBuilder output =
        new ResourcesZipBuilder(
            ProjectFilesystemUtils.getPathForRelativePath(
                context.getRuleCellRoot(), relativePathToMergedAssets))) {
      if (relativePathToBaseApk.isPresent()) {
        Path pathToBaseApk =
            ProjectFilesystemUtils.getPathForRelativePath(
                context.getRuleCellRoot(), relativePathToBaseApk.get());
        try (ZipFile base = new ZipFile(pathToBaseApk.toFile())) {
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

  private TreeMultimap<Path, Path> getAllAssets(IsolatedExecutionContext context)
      throws IOException {
    TreeMultimap<Path, Path> assets = TreeMultimap.create();

    for (RelPath assetDirectory : assetsDirectories) {
      AbsPath absolutePath =
          ProjectFilesystemUtils.getAbsPathForRelativePath(
              context.getRuleCellRoot(), assetDirectory);

      ProjectFilesystemUtils.walkFileTree(
          context.getRuleCellRoot(),
          assetDirectory.getPath(),
          ImmutableSet.of(),
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Preconditions.checkState(
                  !Files.getFileExtension(file.toString()).equals("gz"),
                  "BUCK doesn't support adding .gz files to assets (%s).",
                  file);
              assets.put(
                  absolutePath.getPath(), absolutePath.getPath().relativize(file.normalize()));
              return super.visitFile(file, attrs);
            }
          },
          ProjectFilesystemUtils.getIgnoreFilter(
              context.getRuleCellRoot(), false, ImmutableSet.of()));
    }

    return assets;
  }

  @Override
  public String getShortName() {
    return "merge_assets";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return String.format(
        "merge_assets %s %s",
        relativePathToMergedAssets,
        relativePathToBaseApk.map(RelPath::toString).orElse("no_base_apk"));
  }
}
