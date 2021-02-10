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
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Step to construct build outputs for exo-for-resources. */
public class MergeJarResourcesStep extends IsolatedStep {

  private final ImmutableSortedSet<RelPath> pathsToJars;
  private final Path mergedPath;

  public MergeJarResourcesStep(ImmutableSortedSet<RelPath> pathsToJars, Path mergedPath) {
    this.pathsToJars = pathsToJars;
    this.mergedPath = mergedPath;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException {
    try (ResourcesZipBuilder builder = new ResourcesZipBuilder(mergedPath)) {
      for (RelPath jar : pathsToJars) {
        try (ZipFile base =
            new ZipFile(
                ProjectFilesystemUtils.getPathForRelativePath(context.getRuleCellRoot(), jar)
                    .toFile())) {
          for (ZipEntry inputEntry : Collections.list(base.entries())) {
            if (inputEntry.isDirectory()) {
              continue;
            }
            String name = inputEntry.getName();
            String ext = Files.getFileExtension(name);
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
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "merge_jar_resources";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return String.join(
        " ",
        "merge_jar_resources",
        mergedPath.toString(),
        pathsToJars.stream().map(RelPath::toString).collect(Collectors.joining(",")));
  }
}
