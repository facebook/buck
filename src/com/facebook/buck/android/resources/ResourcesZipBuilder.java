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

import com.facebook.buck.util.zip.DeterministicZipBuilder;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Some versions of Android require that any zip file that contains resources/assets contains an
 * AndroidManifest.xml (though it doesn't actually read it). This is just a zip builder that will
 * add an empty (*) manifest if none has been added when it is closed.
 *
 * <p>See
 * https://android.googlesource.com/platform/frameworks/base/+/lollipop-release/libs/androidfw/AssetManager.cpp#209
 *
 * <p>(*) Due to a bug in Android's zip handling, we actually create a 1-byte manifest... See
 * https://android.googlesource.com/platform/system/core/+/48953a1b8fdcf1d6fa1aeeb40c57821d33fc87d2
 */
public class ResourcesZipBuilder implements Closeable {
  public static final String ANDROID_MANIFEST_XML = "AndroidManifest.xml";
  private boolean hasManifest;
  private DeterministicZipBuilder builder;

  public ResourcesZipBuilder(Path path) throws IOException {
    builder = new DeterministicZipBuilder(path);
    hasManifest = false;
  }

  public void addEntry(
      InputStream stream,
      long size,
      long crc,
      String name,
      int compressionLevel,
      boolean isDirectory)
      throws IOException {
    builder.addEntry(stream, size, crc, name, compressionLevel, isDirectory);
    if (name.equals(ANDROID_MANIFEST_XML)) {
      hasManifest = true;
    }
  }

  @Override
  public void close() throws IOException {
    if (!hasManifest) {
      byte[] data = new byte[1];
      CRC32 crc32 = new CRC32();
      crc32.update(data);
      addEntry(
          new ByteArrayInputStream(data),
          1,
          crc32.getValue(),
          ANDROID_MANIFEST_XML,
          Deflater.NO_COMPRESSION,
          false);
    }
    builder.close();
  }
}
