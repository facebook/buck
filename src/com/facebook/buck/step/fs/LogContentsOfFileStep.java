/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.io.Files;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.logging.Level;

public class LogContentsOfFileStep implements Step {

  private static final Logger LOG = Logger.get(LogContentsOfFileStep.class);

  private final Path absolutePath;
  private final Level level;

  public LogContentsOfFileStep(Path absolutePath, Level level) {
    this.absolutePath = absolutePath;
    this.level = level;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {

    if (LOG.isLoggable(level)) {
      String contents = Files.toString(absolutePath.toFile(), StandardCharsets.UTF_8);
      LOG.logWithLevel(level, contents);
    }
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "log_contents_of_file";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "log contents of file: " + absolutePath;
  }
}
