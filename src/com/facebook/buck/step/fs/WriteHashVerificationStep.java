/*
 * Copyright 2019-present Facebook, Inc.
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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.nio.file.Path;

/** Build Step to write out a hash.verify file next to rule output. */
public class WriteHashVerificationStep implements Step {
  private static final HashFunction HASHER = Hashing.sha1();
  private static final String FILE_HASH_VERIFICATION = "hash.verify";

  private final ProjectFilesystem filesystem;
  private final Path file;
  private BuildTarget buildTarget;
  private final Path output;

  public WriteHashVerificationStep(
      ProjectFilesystem filesystem, Path file, BuildTarget buildTarget) {
    this.filesystem = filesystem;
    this.file = file;
    this.buildTarget = buildTarget;
    this.output = filesystem.resolve(file);
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    if (output.getParent() != null) {
      Path metaOut =
          output
              .getParent()
              .resolve(output.getFileName().toString() + "." + FILE_HASH_VERIFICATION);
      HashCode hashCode =
          HASHER.hashBytes(ByteStreams.toByteArray(filesystem.newFileInputStream(output)));
      filesystem.writeContentsToPath(
          String.format(
              "hash: %s\ntarget: %s\nbuild: %s",
              hashCode, buildTarget.getFullyQualifiedName(), context.getBuildId()),
          metaOut);
    }
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "Hash " + file;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "hash";
  }
}
