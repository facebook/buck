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

package com.facebook.buck.step.isolatedsteps.common;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.Escaper;
import com.google.common.io.ByteSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@BuckStyleValue
public abstract class WriteFileIsolatedStep extends IsolatedStep {

  abstract AbsPath getRootPath();

  abstract ByteSource getSource();

  abstract Path getOutputPath();

  abstract boolean getExecutable();

  @Override
  public String getShortName() {
    return "write_file";
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    try (InputStream sourceStream = getSource().openStream()) {
      ProjectFilesystemUtils.copyToPath(
          getRootPath(), sourceStream, getOutputPath(), StandardCopyOption.REPLACE_EXISTING);
      if (getExecutable()) {
        Path resolvedPath = getRootPath().getPath().resolve(getOutputPath());
        MostFiles.makeExecutable(resolvedPath);
      }
      return StepExecutionResults.SUCCESS;
    }
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return String.format("echo ... > %s", Escaper.escapeAsBashString(getOutputPath()));
  }

  public static WriteFileIsolatedStep of(
      AbsPath rootPath, ByteSource content, Path outputPath, boolean executable) {
    return ImmutableWriteFileIsolatedStep.ofImpl(rootPath, content, outputPath, executable);
  }
}
