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

package com.facebook.buck.features.python;

import com.facebook.buck.core.util.immutables.BuckStyleStep;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.zip.ZipScrubber;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Path;
import org.immutables.value.Value;

/** Step scrubbing all timestamps and undeterministic output from .pex files. */
@Value.Immutable
@BuckStyleStep
abstract class AbstractPexScrubberStep implements Step {

  @Value.Parameter
  protected abstract Path getPexAbsolutePath();

  @Value.Parameter
  protected abstract ProjectFilesystem getProjectFileSystem();

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(
        getPexAbsolutePath().isAbsolute(), "PexScrubberStep must take an absolute path");
  }

  @Override
  public String getShortName() {
    return "pex-scrub";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "pex-scrub " + getPexAbsolutePath();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {

    Path pexPath = getPexAbsolutePath();
    ProjectFilesystem projectFilesystem = getProjectFileSystem();
    if (projectFilesystem.isFile(pexPath)) {
      ZipScrubber.scrubZip(pexPath);
    }

    return StepExecutionResults.SUCCESS;
  }
}
