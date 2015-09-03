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
package com.facebook.buck.android;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;

import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * This step writes the size of a .dex.jar file and the uncompressed size of its contained
 * classes.dex to the given metadata file.  We use this information during
 * dex file loading to estimate the amount of disk space we'll need.
 */
class DexJarAnalysisStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path dexPath;
  private final Path dexMetaPath;

  DexJarAnalysisStep(ProjectFilesystem filesystem, Path dexPath, Path dexMetaPath) {
    this.filesystem = filesystem;
    this.dexPath = dexPath;
    this.dexMetaPath = dexMetaPath;
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {

    try (ZipFile zf = new ZipFile(filesystem.resolve(dexPath).toFile())) {
      ZipEntry classesDexEntry = zf.getEntry("classes.dex");
      if (classesDexEntry == null) {
        throw new RuntimeException("could not find classes.dex in jar");
      }

      long uncompressedSize = classesDexEntry.getSize();
      if (uncompressedSize == -1) {
        throw new RuntimeException("classes.dex size should be known");
      }

      filesystem.writeContentsToPath(
          String.format(
              "jar:%s dex:%s",
              filesystem.getFileSize(dexPath),
              uncompressedSize),
          dexMetaPath);

      return 0;
    } catch (IOException e) {
      context.logError(e, "There was an error in smart dexing step.");
      return 1;
    }
  }

  @Override
  public String getShortName() {
    return "dex_meta";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("dex_meta dexPath:%s dexMetaPath:%s", dexPath, dexMetaPath);
  }
}
