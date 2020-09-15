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

package com.facebook.buck.step.isolatedsteps.cxx;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;

/** Step to write test list to file. */
public class WriteTestListToFileStep extends IsolatedShellStep {
  private final ImmutableList<String> command;
  private final Path testListPath;

  public WriteTestListToFileStep(
      ImmutableList<String> testCommand,
      Path workingDirectory,
      RelPath cellRootPath,
      Path testListPath,
      boolean withDownwardApi) {
    super(workingDirectory, cellRootPath, withDownwardApi);
    this.command = testCommand;
    this.testListPath = testListPath;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(IsolatedExecutionContext context) {
    return ImmutableList.<String>builder().addAll(command).add("--gtest_list_tests").build();
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {

    StepExecutionResult result = super.executeIsolatedStep(context);
    if (!result.isSuccess()) {
      return result;
    }

    // DefaultProcessExecutor used in the super class adds ANSI codes to stdout
    // there's no easy way to disable this behavior, so we have to clean it up here
    String testsList = removeANSI(getStdout());
    ProjectFilesystemUtils.writeContentsToPath(context.getRuleCellRoot(), testsList, testListPath);

    return StepExecutionResults.SUCCESS;
  }

  private String removeANSI(String text) {
    return text.replaceAll("\u001b\\[\\d+m", "");
  }

  @Override
  public String getShortName() {
    return "Write Test List To A File";
  }
}
