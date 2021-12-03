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

package com.facebook.buck.swift;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Objects;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

class OutputFileMapStep implements Step {

  private static final Logger LOG = Logger.get(com.facebook.buck.swift.OutputFileMapStep.class);

  private final ProjectFilesystem filesystem;
  private final Path outputFileMapPath;
  private final OutputFileMap outputFileMap;

  public OutputFileMapStep(
      ProjectFilesystem filesystem, Path outputFileMapPath, OutputFileMap outputFileMap) {
    this.filesystem = filesystem;
    this.outputFileMapPath = outputFileMapPath;
    this.outputFileMap = outputFileMap;
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return "swift_output_file_map @ " + outputFileMapPath;
  }

  @Override
  public String getShortName() {
    return "output_file_map";
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context) throws IOException {
    LOG.debug("Writing OutputFileMap to %s", outputFileMapPath);
    if (!filesystem.exists(outputFileMapPath)) {
      filesystem.createParentDirs(outputFileMapPath);
    }

    try {
      OutputStream outputStream = filesystem.newFileOutputStream(outputFileMapPath);
      try (JsonGenerator jsonGen = ObjectMappers.createGenerator(outputStream)) {
        outputFileMap.render(jsonGen);
      }
      return StepExecutionResults.SUCCESS;
    } catch (IOException e) {
      LOG.debug("Error writing OutputFileMap to %s:\n%s", outputFileMapPath, e.getMessage());
      return StepExecutionResults.ERROR;
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof com.facebook.buck.swift.OutputFileMapStep)) {
      return false;
    }
    com.facebook.buck.swift.OutputFileMapStep that =
        (com.facebook.buck.swift.OutputFileMapStep) obj;
    return Objects.equal(this.outputFileMapPath, that.outputFileMapPath)
        && Objects.equal(this.outputFileMap, that.outputFileMap);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(outputFileMapPath, outputFileMap);
  }

  @Override
  public String toString() {
    return String.format("OutputFileMap %s", outputFileMapPath.toString());
  }
}
