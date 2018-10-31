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

package com.facebook.buck.features.filebundler;

import static com.facebook.buck.features.filebundler.FileBundler.createRelativeMap;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.unarchive.UnzipStep;
import com.facebook.buck.util.PatternsMatcher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Extracts zip archives with excluding entries matching given patterns. */
public class ZipFileExtractor {

  private ZipFileExtractor() {}

  /**
   * Given a list of archive files, generates a list of steps to extract those files in a given
   * location.
   *
   * @param destinationDir location for extracted files.
   * @param entriesToExclude entries that match this matcher will not be extracted.
   */
  public static ImmutableList<Step> extractZipFiles(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path destinationDir,
      ImmutableSortedSet<SourcePath> toExtract,
      SourcePathResolver pathResolver,
      PatternsMatcher entriesToExclude) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Map<Path, Path> relativeMap =
        createRelativeMap(target.getBasePath(), filesystem, pathResolver, toExtract);

    for (Map.Entry<Path, Path> pathEntry : relativeMap.entrySet()) {
      Path relativePath = pathEntry.getKey();
      Path absolutePath = Objects.requireNonNull(pathEntry.getValue());
      Path destination = destinationDir.resolve(relativePath);

      steps.add(
          new UnzipStep(
              filesystem,
              absolutePath,
              destination.getParent(),
              Optional.empty(),
              entriesToExclude));
    }
    return steps.build();
  }
}
