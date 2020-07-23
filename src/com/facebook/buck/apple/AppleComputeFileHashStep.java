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

package com.facebook.buck.apple;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.cxx.toolchain.objectfile.Machos;
import com.facebook.buck.cxx.toolchain.objectfile.ObjectFileScrubbers;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.nio.ByteBufferUnmapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Computes the hash of a file. Includes special configurable logic to extract UUIDs from Mach-O
 * files, which can be used instead, as to avoid re-hashing.
 */
public class AppleComputeFileHashStep extends AbstractExecutionStep {

  private final StringBuilder hashBuilder;
  private final boolean useMachoUuid;
  private final AbsPath filePath;
  private final ProjectFilesystem projectFilesystem;

  public AppleComputeFileHashStep(
      StringBuilder hashBuilder,
      AbsPath filePath,
      boolean useMachoUuid,
      ProjectFilesystem projectFilesystem) {
    super("apple-compute-file-hash");
    this.hashBuilder = hashBuilder;
    this.useMachoUuid = useMachoUuid;
    this.filePath = filePath;
    this.projectFilesystem = projectFilesystem;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {

    if (useMachoUuid) {
      Optional<String> maybeUuid = getMachoUuid(filePath);
      if (maybeUuid.isPresent()) {
        hashBuilder.append("uuid:").append(maybeUuid.get());
        return StepExecutionResults.SUCCESS;
      }
    }

    String sha1 = projectFilesystem.computeSha1(filePath.getPath()).getHash();
    // In order to guarantee no collisions between Mach-O and non-Mach-O files,
    // we namespace them with a suitable prefix.
    hashBuilder.append(useMachoUuid ? "sha1:" : "").append(sha1);
    return StepExecutionResults.SUCCESS;
  }

  private Optional<String> getMachoUuid(AbsPath path) throws IOException {
    try (FileChannel file = FileChannel.open(path.getPath(), StandardOpenOption.READ)) {
      if (!Machos.isMacho(file)) {
        return Optional.empty();
      }

      try (ByteBufferUnmapper unmapper =
          ByteBufferUnmapper.createUnsafe(
              file.map(FileChannel.MapMode.READ_ONLY, 0, file.size()))) {

        try {
          Optional<byte[]> maybeUuid = Machos.getUuidIfPresent(unmapper.getByteBuffer());
          if (maybeUuid.isPresent()) {
            String hexBytes = ObjectFileScrubbers.bytesToHex(maybeUuid.get(), true);
            return Optional.of(hexBytes);
          }
        } catch (Machos.MachoException e) {
          // Even though it's a Mach-O file, we failed to read it safely
          throw new RuntimeException("Internal Mach-O file parsing failure");
        }

        return Optional.empty();
      }
    }
  }
}
