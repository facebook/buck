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

package com.facebook.buck.apple;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Separate step to serialize {@link AppleBundleIncrementalInfo} to disk. */
public class AppleWriteIncrementalInfoStep extends AbstractExecutionStep {

  private final Supplier<Map<RelPath, String>> contentHashesSupplier;
  private final List<RelPath> codeSignOnCopyPaths;
  private final boolean codeSigned;
  private final Path outputPath;
  private final ProjectFilesystem projectFilesystem;

  AppleWriteIncrementalInfoStep(
      Supplier<Map<RelPath, String>> contentHashesSupplier,
      List<RelPath> codeSignOnCopyPaths,
      boolean codeSigned,
      Path outputPath,
      ProjectFilesystem projectFilesystem) {
    super("apple-bundle-write-incremental-info");
    this.contentHashesSupplier = contentHashesSupplier;
    this.codeSignOnCopyPaths = codeSignOnCopyPaths;
    this.codeSigned = codeSigned;
    this.outputPath = outputPath;
    this.projectFilesystem = projectFilesystem;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    AppleBundleIncrementalInfo incrementalInfo =
        new AppleBundleIncrementalInfo(
            contentHashesSupplier.get(), codeSigned, codeSignOnCopyPaths);
    try (OutputStream outputStream = projectFilesystem.newFileOutputStream(outputPath)) {
      try (JsonGenerator generator = ObjectMappers.createGenerator(outputStream)) {
        generator.writeObject(incrementalInfo);
      }
    }
    return StepExecutionResults.SUCCESS;
  }
}
