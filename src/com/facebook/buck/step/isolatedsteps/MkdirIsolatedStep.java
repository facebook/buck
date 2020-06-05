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

package com.facebook.buck.step.isolatedsteps;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.Escaper;
import java.io.IOException;
import java.nio.file.Files;

/** Command that runs equivalent command of {@code mkdir -p} on the specified directory. */
@BuckStyleValue
public abstract class MkdirIsolatedStep extends IsolatedStep {

  public abstract AbsPath getProjectRoot();

  public abstract RelPath getDirPath();

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException {
    Files.createDirectories(
        ProjectFilesystemUtils.getPathForRelativePath(getProjectRoot(), getDirPath().getPath()));
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return String.format("mkdir -p %s", Escaper.escapeAsShellString(getDirPath().toString()));
  }

  @Override
  public String getShortName() {
    return "mkdir";
  }

  public static MkdirIsolatedStep of(AbsPath root, RelPath directoryToCreate) {
    return ImmutableMkdirIsolatedStep.ofImpl(root, directoryToCreate);
  }
}
