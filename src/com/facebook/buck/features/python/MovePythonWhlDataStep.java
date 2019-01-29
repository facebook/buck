/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.features.python;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A {@link Step} that moves the contents of the {package}-{version}.data directory within an
 * extracted .whl if that directory exists.
 *
 * <p>This is a basic, incomplete implementation of PEP491. It does not honor WHEEL files'
 * Install-Paths-To directive in .dist-info, and does not do any fancy path rewriting. For now, it
 * just moves things out of the data prefix (If other distutils' prefixes are required, we can do
 * those later, but they vary much more per-platform than data_files. See
 * https://svn.python.org/projects/python/tags/r32/Lib/distutils/command/install.py for the various
 * subdirs that can exist, and how they vary per-platform).
 */
public class MovePythonWhlDataStep implements Step {

  private final ProjectFilesystem projectFilesystem;
  private final Path extractedWhlDir;

  public MovePythonWhlDataStep(ProjectFilesystem projectFilesystem, Path extractedWhlDir) {
    this.projectFilesystem = projectFilesystem;
    this.extractedWhlDir = extractedWhlDir;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {

    Path resolvedWhlDir = projectFilesystem.resolve(extractedWhlDir);

    // The data directory is based on the .whl metadata. Most instances it'll probably be faster
    // to just list the couple of entries inside of the root of the extracted dir, rather than
    // parsing out the *.dist-info/METADATA file (we also would need the package name/version
    // anyways to find the .dist-info dir.
    Optional<Path> dataDir =
        Files.list(resolvedWhlDir)
            .filter(
                path -> path.getFileName().toString().endsWith(".data") && Files.isDirectory(path))
            .findFirst();
    if (!dataDir.isPresent()) {
      return StepExecutionResults.SUCCESS;
    }

    // There are a few other dirs that could be under here, but their destinations are platform
    // specific. Just do the data subdir for now.
    Path dataSubdir = dataDir.get().resolve("data");
    if (!Files.isDirectory(dataSubdir)) {
      return StepExecutionResults.SUCCESS;
    }

    // Copy everything under `data` to the root of the tree
    projectFilesystem.mergeChildren(dataSubdir, resolvedWhlDir);

    // Delete the original `data` dir.
    projectFilesystem.deleteRecursivelyIfExists(dataSubdir);

    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "move_whl_data_dir_contents";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "move_whl_data_dir_contents in " + extractedWhlDir;
  }
}
