/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.step.fs;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.io.ByteSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

public class WriteFileStep implements Step {

  private final ByteSource source;
  private final ProjectFilesystem filesystem;
  private final Path outputPath;
  private final boolean executable;

  public WriteFileStep(
      ProjectFilesystem filesystem, ByteSource content, Path outputPath, boolean executable) {
    Preconditions.checkArgument(
        !outputPath.isAbsolute(), "Output path must not be absolute: %s", outputPath);

    this.source = content;
    this.filesystem = filesystem;
    this.outputPath = outputPath;
    this.executable = executable;
  }

  public WriteFileStep(
      ProjectFilesystem filesystem, String content, Path outputPath, boolean executable) {
    this(filesystem, Suppliers.ofInstance(content), outputPath, executable);
  }

  public WriteFileStep(
      ProjectFilesystem filesystem, Supplier<String> content, Path outputPath, boolean executable) {
    this(
        filesystem,
        new ByteSource() {
          @Override
          public InputStream openStream() {
            // echo by default writes a trailing new line and so should we.
            return new ByteArrayInputStream((content.get() + "\n").getBytes(Charsets.UTF_8));
          }
        },
        outputPath,
        executable);
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    try (InputStream sourceStream = source.openStream()) {
      filesystem.copyToPath(sourceStream, outputPath, StandardCopyOption.REPLACE_EXISTING);
      if (executable) {
        Path resolvedPath = filesystem.resolve(outputPath);
        MostFiles.makeExecutable(resolvedPath);
      }
      return StepExecutionResults.SUCCESS;
    }
  }

  @Override
  public String getShortName() {
    return "write_file";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("echo ... > %s", Escaper.escapeAsBashString(outputPath));
  }
}
