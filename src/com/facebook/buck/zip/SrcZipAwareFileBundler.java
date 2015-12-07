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
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

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

    for (SourcePath sourcePath : toCopy) {
      Path absolute = resolver.getAbsolutePath(sourcePath);

      if (absolute.toString().endsWith(JavaLibrary.SOURCE_ZIP) ||
          absolute.toString().endsWith(JavaLibrary.SOURCE_JAR)) {
        steps.add(new UnzipStep(filesystem, absolute, destinationDir));
        continue;
      }

      if (Files.isDirectory(absolute)) {
        throw new HumanReadableException("Cowardly refusing to copy a directory: " + absolute);
      }

      Path destination;
      Path relative = resolver.getRelativePath(sourcePath);
      if (!junkPaths && relative.startsWith(basePath)) {
        destination = basePath.relativize(relative);
      } else {
        destination = absolute.getFileName();
      }
      destination = destinationDir.resolve(destination);
      if (destination.getParent() != null) {
        steps.add(new MkdirStep(filesystem, destination.getParent()));
      }
      steps.add(CopyStep.forFile(filesystem, absolute, destination));
    }
  }
}
