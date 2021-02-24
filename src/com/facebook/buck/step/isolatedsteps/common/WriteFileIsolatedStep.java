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
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.io.ByteSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

@BuckStyleValue
public abstract class WriteFileIsolatedStep extends IsolatedStep {

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
          context.getRuleCellRoot(),
          sourceStream,
          getOutputPath(),
          StandardCopyOption.REPLACE_EXISTING);
      if (getExecutable()) {
        Path resolvedPath = context.getRuleCellRoot().getPath().resolve(getOutputPath());
        MostFiles.makeExecutable(resolvedPath);
      }
      return StepExecutionResults.SUCCESS;
    }
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return String.format("echo ... > %s", Escaper.escapeAsBashString(getOutputPath()));
  }

  public static WriteFileIsolatedStep of(ByteSource content, Path outputPath, boolean executable) {
    Preconditions.checkArgument(
        !outputPath.isAbsolute(), "Output path must not be absolute: %s", outputPath);
    return ImmutableWriteFileIsolatedStep.ofImpl(content, outputPath, executable);
  }

  public static WriteFileIsolatedStep of(
      ByteSource content, RelPath outputPath, boolean executable) {
    return ImmutableWriteFileIsolatedStep.ofImpl(content, outputPath.getPath(), executable);
  }

  public static WriteFileIsolatedStep of(String content, Path outputPath, boolean executable) {
    return of(Suppliers.ofInstance(content), outputPath, executable);
  }

  public static WriteFileIsolatedStep of(
      Supplier<String> content, Path outputPath, boolean executable) {
    return ImmutableWriteFileIsolatedStep.ofImpl(
        new ByteSource() {
          @Override
          public InputStream openStream() {
            // echo by default writes a trailing new line and so should we.
            return new ByteArrayInputStream(
                (content.get() + "\n").getBytes(StandardCharsets.UTF_8));
          }
        },
        outputPath,
        executable);
  }

  public static WriteFileIsolatedStep of(
      Supplier<String> content, RelPath outputPath, boolean executable) {
    return of(content, outputPath.getPath(), executable);
  }

  public static WriteFileIsolatedStep of(String content, RelPath outputPath, boolean executable) {
    return of(content, outputPath.getPath(), executable);
  }
}
