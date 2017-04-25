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
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.immutables.BuckStyleStep;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleStep
abstract class AbstractRmStep implements Step {

  private static final Logger LOG = Logger.get(AbstractRmStep.class);

  @Value.Parameter
  // TODO(dwh): Remove this ProjectFilesystem when ignored files aren't a concept.
  protected abstract ProjectFilesystem getFilesystem();

  @Value.Parameter
  // TODO(dwh): Make this always an absolute path.
  protected abstract Path getAbsoluteOrRelativePath();

  @Value.Default
  protected boolean isRecursive() {
    return false;
  }

  @Override
  public String getShortName() {
    return "rm";
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) {
    try {
      if (isRecursive()) {
        // Delete a folder recursively
        getFilesystem().deleteRecursivelyIfExists(getAbsoluteOrRelativePath());
      } else {
        // Delete a single file
        getFilesystem().deleteFileAtPathIfExists(getAbsoluteOrRelativePath());
      }
    } catch (IOException e) {
      LOG.error(e);
      return StepExecutionResult.ERROR;
    }
    return StepExecutionResult.SUCCESS;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("rm");
    args.add("-f");

    if (isRecursive()) {
      args.add("-r");
    }

    Path absolutePath = getFilesystem().resolve(getAbsoluteOrRelativePath());
    args.add(absolutePath.toString());

    return Joiner.on(" ").join(args.build());
  }
}
