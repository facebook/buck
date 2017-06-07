/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Joiner;
import java.io.IOException;
import java.nio.file.Path;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractSymlinkFileStep implements Step {

  @Value.Parameter
  // TODO(dwh): Remove filesystem when ignored files are removed.
  protected abstract ProjectFilesystem getFilesystem();

  @Value.Parameter
  // TODO(dwh): Require this to be one of absolute or relative.
  protected abstract Path getExistingFile();

  @Value.Parameter
  // TODO(dwh): Require this to be absolute.
  protected abstract Path getDesiredLink();

  /** Get the path to the existing file that should be linked. */
  private Path getAbsoluteExistingFilePath() {
    return getFilesystem().resolve(getExistingFile());
  }

  /** Get the path to the desired link that should be created. */
  private Path getAbsoluteDesiredLinkPath() {
    return getFilesystem().resolve(getDesiredLink());
  }

  @Override
  public String getShortName() {
    return "symlink_file";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(" ")
        .join("ln", "-f", "-s", getAbsoluteExistingFilePath(), getAbsoluteDesiredLinkPath());
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) {
    Path existingFilePath = getAbsoluteExistingFilePath();
    Path desiredLinkPath = getAbsoluteDesiredLinkPath();
    try {
      getFilesystem().createSymLink(desiredLinkPath, existingFilePath, /* force */ true);
      return StepExecutionResult.SUCCESS;
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return StepExecutionResult.ERROR;
    }
  }
}
