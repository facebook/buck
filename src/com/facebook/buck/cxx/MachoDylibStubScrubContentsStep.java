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

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.cxx.toolchain.objectfile.DylibStubContentsScrubber;
import com.facebook.buck.io.file.FileScrubber;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Resets all addresses in a dylib stub, so that if the ABI of a dylib stays the same, the dylib
 * stub itself will be identical on disk.
 */
public class MachoDylibStubScrubContentsStep implements Step {
  private final ProjectFilesystem filesystem;
  private final Path dylibPath;

  public MachoDylibStubScrubContentsStep(ProjectFilesystem filesystem, Path dylibPath) {
    this.filesystem = filesystem;
    this.dylibPath = dylibPath;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    try (FileChannel dylibFile =
        FileChannel.open(
            filesystem.resolve(dylibPath), StandardOpenOption.READ, StandardOpenOption.WRITE)) {

      DylibStubContentsScrubber scrubber = new DylibStubContentsScrubber();
      scrubber.scrubFile(dylibFile);
    } catch (FileScrubber.ScrubException scrubException) {
      throw new IOException(scrubException.getMessage());
    }

    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "apple_dylib_stub_scrub_contents";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "Scrubs addresses from an Apple dylib stub";
  }
}
