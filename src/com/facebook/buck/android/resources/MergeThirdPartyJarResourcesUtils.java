/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MergeThirdPartyJarResourcesUtils {
  public static void mergeThirdPartyJarResources(
      AbsPath root, ImmutableSortedSet<RelPath> pathsToJars, Path mergedPath) throws IOException {
    try (ResourcesZipBuilder builder = new ResourcesZipBuilder(mergedPath)) {
      for (RelPath jar : pathsToJars) {
        try (ZipFile base =
            new ZipFile(ProjectFilesystemUtils.getPathForRelativePath(root, jar).toFile())) {
          for (ZipEntry inputEntry : Collections.list(base.entries())) {
            if (inputEntry.isDirectory()) {
              continue;
            }
            String name = inputEntry.getName();
            String ext = com.google.common.io.Files.getFileExtension(name);
            String filename = Paths.get(name).getFileName().toString();
            // Android's ApkBuilder filters out a lot of files from Java resources. Try to
            // match its behavior.
            // See
            // https://android.googlesource.com/platform/sdk/+/jb-release/sdkmanager/libs/sdklib/src/com/android/sdklib/build/ApkBuilder.java
            if (name.startsWith(".")
                || name.endsWith("~")
                || name.startsWith("META-INF")
                || "aidl".equalsIgnoreCase(ext)
                || "rs".equalsIgnoreCase(ext)
                || "rsh".equalsIgnoreCase(ext)
                || "d".equalsIgnoreCase(ext)
                || "java".equalsIgnoreCase(ext)
                || "scala".equalsIgnoreCase(ext)
                || "class".equalsIgnoreCase(ext)
                || "scc".equalsIgnoreCase(ext)
                || "swp".equalsIgnoreCase(ext)
                || "thumbs.db".equalsIgnoreCase(filename)
                || "picasa.ini".equalsIgnoreCase(filename)
                || "package.html".equalsIgnoreCase(filename)
                || "overview.html".equalsIgnoreCase(filename)) {
              continue;
            }
            try (InputStream inputStream = base.getInputStream(inputEntry)) {
              builder.addEntry(
                  inputStream,
                  inputEntry.getSize(),
                  inputEntry.getCrc(),
                  name,
                  Deflater.NO_COMPRESSION,
                  false);
            }
          }
        }
      }
    }
  }
}
