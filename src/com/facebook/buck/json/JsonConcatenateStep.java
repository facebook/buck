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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import javax.annotation.Nullable;

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
  public StepExecutionResult execute(final ExecutionContext context)
      throws IOException, InterruptedException {
    ImmutableSortedSet<Path> filesToConcatenate =
        FluentIterable.from(this.inputs)
            .transform(
                new Function<Path, Path>() {
                  @Nullable
                  @Override
                  public Path apply(Path input) {
                    return filesystem.getRootPath().resolve(input);
                  }
                })
            .toSortedSet(Ordering.natural());
    Path destination = filesystem.getRootPath().resolve(output);
    new JsonConcatenator(filesToConcatenate, destination, filesystem).concatenate();
    return StepExecutionResult.SUCCESS;
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
