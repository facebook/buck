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
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Step to persist computed file and directory hashes on disk in JSON format. */
public class AppleWriteHashPerFileStep extends AbstractExecutionStep {

  private final Supplier<ImmutableMap<RelPath, String>> pathToHashSupplier;
  private final Path outputPath;
  private final ProjectFilesystem filesystem;

  AppleWriteHashPerFileStep(
      String description,
      Supplier<ImmutableMap<RelPath, String>> pathToHashSupplier,
      Path outputPath,
      ProjectFilesystem filesystem) {
    super(description);
    this.pathToHashSupplier = pathToHashSupplier;
    this.outputPath = outputPath;
    this.filesystem = filesystem;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    OutputStream outputStream = filesystem.newFileOutputStream(outputPath);
    JsonGenerator generator = ObjectMappers.createGenerator(outputStream);
    generator.writeStartObject();
    for (ImmutableMap.Entry<RelPath, String> entry : pathToHashSupplier.get().entrySet()) {
      generator.writeStringField(entry.getKey().toString(), entry.getValue());
    }
    generator.writeEndObject();
    generator.close();
    outputStream.close();
    return StepExecutionResults.SUCCESS;
  }
}
