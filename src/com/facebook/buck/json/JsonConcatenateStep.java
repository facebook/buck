/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.json;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;

public class JsonConcatenateStep implements Step {

  private final ProjectFilesystem filesystem;
  private final String shortName;
  private final String description;
  private ImmutableSortedSet<Path> inputs;
  private Path output;

  public JsonConcatenateStep(
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> inputs,
      Path output,
      String shortName,
      String description) {
    this.filesystem = filesystem;
    this.inputs = inputs;
    this.output = output;
    this.shortName = shortName;
    this.description = description;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    ImmutableSortedSet<Path> filesToConcatenate =
        inputs.stream()
            .map(input -> filesystem.getRootPath().resolve(input))
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    Path destination = filesystem.getRootPath().resolve(output);
    new JsonConcatenator(filesToConcatenate, destination, filesystem).concatenate();
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return shortName;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return description;
  }
}
