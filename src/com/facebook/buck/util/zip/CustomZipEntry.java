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

package com.facebook.buck.util.zip;

import static java.util.zip.Deflater.BEST_COMPRESSION;
import static java.util.zip.Deflater.NO_COMPRESSION;

import com.google.common.base.Preconditions;
import java.io.File;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;

public class CustomZipEntry extends ZipEntry {

  private int compressionLevel = Deflater.DEFAULT_COMPRESSION;
  private long externalAttributes = 0;

  public CustomZipEntry(ZipEntry other) {
    super(other);
    setDefaultMethodAndTimeIfUnset();
  }

  public CustomZipEntry(String name) {
    super(name);
    setDefaultMethodAndTimeIfUnset();
  }

  public CustomZipEntry(Path path, boolean isDirectory) {
    this(path.toString().replace(File.separatorChar, '/') + (isDirectory ? "/" : ""));
  }

  public CustomZipEntry(Path path) {
    this(path, false);
  }

  private void setDefaultMethodAndTimeIfUnset() {
    if (getMethod() == -1) {
      setMethod(DEFLATED);
    }
    if (getTime() == -1) {
      setFakeTime();
    }
  }

  public void setFakeTime() {
    setTime(ZipConstants.getFakeTime());
  }

  public void setCompressionLevel(int compressionLevel) {
    Preconditions.checkArgument(
        compressionLevel >= NO_COMPRESSION && compressionLevel <= BEST_COMPRESSION);
    this.compressionLevel = compressionLevel;

    // We need to update the underlying method declared
    setMethod(compressionLevel == NO_COMPRESSION ? STORED : DEFLATED);

    // Reset the various fields that need to be updated.
    setCrc(0);
    setSize(0);
    setCompressedSize(0);
  }

  public int getCompressionLevel() {
    return compressionLevel;
  }

  public long getExternalAttributes() {
    return externalAttributes;
  }

  public void setExternalAttributes(long externalAttributes) {
    this.externalAttributes = externalAttributes;
  }
}
