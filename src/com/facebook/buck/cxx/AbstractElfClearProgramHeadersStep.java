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
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.immutables.value.Value;

/** A step which zeros out the program headers of an ELF file. */
@Value.Immutable
@BuckStylePackageVisibleTuple
abstract class AbstractElfClearProgramHeadersStep implements Step {

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
      Preconditions.checkState(
          elf.header.e_phoff == (int) elf.header.e_phoff,
          "program headers are expected to be within 4GB of beginning of file");
      buffer.position((int) elf.header.e_phoff);
      for (int index = 0; index < elf.header.e_phnum * elf.header.e_phentsize; index++) {
        buffer.put((byte) 0);
      }
    }
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public final String getShortName() {
    return "clear_program_headers";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "Clear ELF program headers in " + getPath();
  }
}
