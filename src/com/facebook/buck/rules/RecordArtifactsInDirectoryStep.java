/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Not all build rules know the paths of the output files that their steps will generate. Such rules
 * often write their output to a directory under {@link BuckConstant#BIN_DIR}. This step traverses
 * the output directory, copies the appropriate files to {@link BuckConstant#GEN_DIR}, and records
 * these artifacts for upload via the {@link BuildableContext}.
 */
public class RecordArtifactsInDirectoryStep implements Step {

  private final BuildableContext buildableContext;
  private final Path binDirectory;
  private final Path genDirectory;

  /**
   * @param buildableContext Interface through which the outputs to include in the artifact should
   *     be recorded.
   * @param binDirectory Scratch directory where output files were generated.
   * @param genDirectory Output directory where files should be written.
   */
  public RecordArtifactsInDirectoryStep(BuildableContext buildableContext,
      Path binDirectory,
      Path genDirectory) {
    this.buildableContext = Preconditions.checkNotNull(buildableContext);
    this.binDirectory = Preconditions.checkNotNull(binDirectory);
    this.genDirectory = Preconditions.checkNotNull(genDirectory);
  }

  @Override
  public int execute(final ExecutionContext context) {
    final ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    ImmutableSet<Path> ignorePaths = ImmutableSet.of();

    DirectoryTraversal traversal = new DirectoryTraversal(
        projectFilesystem.getFileForRelativePath(binDirectory),
        ignorePaths) {
      @Override
      public void visit(File file, String relativePath) throws IOException {
        Path source = binDirectory.resolve(relativePath);
        Path target = genDirectory.resolve(relativePath);
        projectFilesystem.createParentDirs(target);
        projectFilesystem.copyFile(source, target);
        buildableContext.recordArtifact(target);
      }
    };

    try {
      traversal.traverse();
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }

    return 0;
  }

  @Override
  public String getShortName() {
    return "record_artifacts_in_dir";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("%s %s", getShortName(), binDirectory);
  }

}
