/*
 * Copyright 2014-present Facebook, Inc.
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
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.util.function.Function;

public class FindAndReplaceStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path input;
  private final Path output;
  private final Function<String, String> replacer;

  public FindAndReplaceStep(
      ProjectFilesystem filesystem, Path input, Path output, Function<String, String> replacer) {
    this.filesystem = filesystem;
    this.input = input;
    this.output = output;
    this.replacer = replacer;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(filesystem.newFileInputStream(input)));
        BufferedWriter writer =
            new BufferedWriter(new OutputStreamWriter(filesystem.newFileOutputStream(output)))) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = replacer.apply(line);
        writer.write(line, 0, line.length());
        writer.newLine();
      }
    }
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "sed";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("sed %s %s", input, output);
  }
}
