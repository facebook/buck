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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.unarchive.UnzipStep;
import com.facebook.buck.util.PatternsMatcher;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

public class SrcZipAwareFileBundler extends FileBundler {

  private final PatternsMatcher entriesToExclude;

  public SrcZipAwareFileBundler(BuildTarget target, PatternsMatcher entriesToExclude) {
    super(target);
    this.entriesToExclude = entriesToExclude;
  }

  public SrcZipAwareFileBundler(Path basePath, PatternsMatcher entriesToExclude) {
    super(basePath);
    this.entriesToExclude = entriesToExclude;
  }

  @Override
  protected void addCopySteps(
      ProjectFilesystem filesystem,
      BuildCellRelativePathFactory buildCellRelativePathFactory,
      ImmutableList.Builder<Step> steps,
      Path relativePath,
      Path absolutePath,
      Path destination) {
    if (relativePath.toString().endsWith(Javac.SRC_ZIP)
        || relativePath.toString().endsWith(Javac.SRC_JAR)) {
      steps.add(
          new UnzipStep(
              filesystem,
              absolutePath,
              destination.getParent(),
              Optional.empty(),
              entriesToExclude));
      return;
    }

    if (destination.getParent() != null) {
      steps.add(MkdirStep.of(buildCellRelativePathFactory.from(destination.getParent())));
    }
    steps.add(CopyStep.forFile(filesystem, absolutePath, destination));
  }
}
