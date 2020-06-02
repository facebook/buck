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

package com.facebook.buck.features.supermodule;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collection;

/** Step that writes out target graph data (labels and deps) as JSON. */
public class GenerateSupermoduleTargetGraphJsonStep extends AbstractExecutionStep {
  private final Path outputRelativePath;
  private final ProjectFilesystem filesystem;
  private final ImmutableSortedMap<BuildTarget, TargetInfo> targetGraphMap;

  public GenerateSupermoduleTargetGraphJsonStep(
      ProjectFilesystem fs,
      Path outputRelativePath,
      ImmutableSortedMap<BuildTarget, TargetInfo> targetGraphMap) {
    super("generate " + outputRelativePath);
    this.filesystem = fs;
    this.outputRelativePath = outputRelativePath;
    this.targetGraphMap = targetGraphMap;
  }

  private void writeCollectionAsJsonArray(Collection<String> c, JsonGenerator jsonGen)
      throws IOException {
    jsonGen.writeStartArray();
    for (String elem : c) {
      jsonGen.writeString(elem);
    }
    jsonGen.writeEndArray();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    try (OutputStream outputStream = filesystem.newFileOutputStream(outputRelativePath)) {
      try (JsonGenerator jsonGen = ObjectMappers.createGenerator(outputStream)) {
        jsonGen.writeStartObject();
        for (BuildTarget target : targetGraphMap.keySet()) {
          jsonGen.writeFieldName(target.getUnflavoredBuildTarget().toString());
          jsonGen.writeStartObject();
          jsonGen.writeFieldName("name");
          jsonGen.writeString(target.getShortName());
          jsonGen.writeFieldName("deps");
          writeCollectionAsJsonArray(targetGraphMap.get(target).getDeps(), jsonGen);
          jsonGen.writeFieldName("labels");
          writeCollectionAsJsonArray(targetGraphMap.get(target).getLabels(), jsonGen);
          jsonGen.writeEndObject();
        }
        jsonGen.writeEndObject();
      }
    }

    return StepExecutionResults.SUCCESS;
  }
}
