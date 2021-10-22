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

package com.facebook.buck.android.resources;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import com.google.common.io.Files;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Utils for merging assets into an apk. */
public class MergeAssetsUtils {

  // See
  // https://android.googlesource.com/platform/frameworks/base.git/+/nougat-release/tools/aapt/Package.cpp
  private static final ImmutableSet<String> NO_COMPRESS_EXTENSIONS =
      ImmutableSet.of(
          "jpg", "jpeg", "png", "gif", "wav", "mp2", "mp3", "ogg", "aac", "mpg", "mpeg", "mid",
          "midi", "smf", "jet", "rtttl", "imy", "xmf", "mp4", "m4a", "m4v", "3gp", "3gpp", "3g2",
          "3gpp2", "amr", "awb", "wma", "wmv", "webm", "mkv", "tflite");

  /**
   * Construct an APK containing assets. If a "baseApk" was provided, also include everything from
   * that APK.
   */
  public static void mergeAssets(
      Path outputApk, Optional<Path> baseApk, ImmutableMap<Path, Path> assets) throws IOException {
    try (ResourcesZipBuilder output = new ResourcesZipBuilder(outputApk)) {
      if (baseApk.isPresent()) {
        try (ZipFile base = new ZipFile(baseApk.get().toFile())) {
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
      for (Map.Entry<Path, Path> assetPaths : assets.entrySet()) {
        Path packagingPathForAsset = assetPaths.getKey();
        Path fullPathToAsset = assetPaths.getValue();
        ByteSource assetSource = Files.asByteSource(fullPathToAsset.toFile());
        HashCode assetCrc32 = assetSource.hash(Hashing.crc32());
        String extension = Files.getFileExtension(fullPathToAsset.toString());
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
              assetsZipRoot.resolve(packagingPathForAsset).toString(),
              compression,
              false);
        }
      }
    }
  }
}
