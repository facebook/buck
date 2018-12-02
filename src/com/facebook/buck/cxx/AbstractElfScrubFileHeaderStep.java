/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import static java.nio.channels.FileChannel.MapMode.READ_WRITE;

import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleTuple;
import com.facebook.buck.cxx.toolchain.elf.Elf;
import com.facebook.buck.cxx.toolchain.elf.ElfHeader;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.immutables.value.Value;

/** A step which zeros out the program headers of an ELF file. */
@Value.Immutable
@BuckStylePackageVisibleTuple
abstract class AbstractElfScrubFileHeaderStep implements Step {

  abstract ProjectFilesystem getFilesystem();

  abstract Path getPath();

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    try (FileChannel channel =
        FileChannel.open(
            getFilesystem().resolve(getPath()),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE)) {
      MappedByteBuffer buffer = channel.map(READ_WRITE, 0, channel.size());
      Elf elf = new Elf(buffer);
      ElfHeader header = elf.header;

      // Clear the `e_entry` entry.
      header = header.withEntry(0);

      // Position the buffer to the beginning of the file header.
      buffer.position(0);

      // Write the new header back out.
      header.write(buffer);
    }
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public final String getShortName() {
    return "scrub_file_header";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "Scrub the ELF file header in " + getPath();
  }
}
