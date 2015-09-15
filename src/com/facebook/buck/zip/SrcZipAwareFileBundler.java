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

package com.facebook.buck.zip;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.Javac;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Files;
import java.nio.file.Path;

public class SrcZipAwareFileBundler {

  private final Path basePath;

  public SrcZipAwareFileBundler(BuildTarget target) {
    this(target.getBasePath());
  }

  public SrcZipAwareFileBundler(Path basePath) {
    this.basePath = Preconditions.checkNotNull(basePath);
  }

  public void copy(
      ProjectFilesystem filesystem,
      SourcePathResolver resolver,
      ImmutableList.Builder<Step> steps,
      Path destinationDir,
      Iterable<SourcePath> toCopy,
      boolean junkPaths) {

    ImmutableMap.Builder<Path, Path> links = ImmutableMap.builder();

    for (SourcePath sourcePath : toCopy) {
      Path resolved = resolver.getPath(sourcePath);

      if (resolved.toString().endsWith(Javac.SRC_ZIP) ||
          resolved.toString().endsWith(Javac.SRC_JAR)) {
        steps.add(new UnzipStep(filesystem, resolved, destinationDir));
        continue;
      }

      if (Files.isDirectory(resolved)) {
        throw new HumanReadableException("Cowardly refusing to copy a directory: " + resolved);
      }

      Path destination;
      if (!junkPaths && resolved.startsWith(basePath)) {
        destination = basePath.relativize(resolved);
      } else {
        destination = resolved.getFileName();
      }
      links.put(destination, resolved);
    }
    steps.add(new SymlinkTreeStep(filesystem, destinationDir, links.build()));
  }
}
