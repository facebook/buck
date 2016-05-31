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
package com.facebook.buck.cli;

import com.facebook.buck.macho.CompDirReplacer;
import com.google.common.base.Preconditions;

import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import javax.annotation.Nullable;

public class MachOFixCompDirCommand extends AbstractCommand {

  private static final String BINARY_OPTION = "--binary";
  private static final String OUTPUT_OPTION = "--output";
  private static final String OLD_COMPDIR_OPTION = "--old_compdir";
  private static final String NEW_COMPDIR_OPTION = "--new_compdir";

  @Option(
      name = BINARY_OPTION,
      required = true,
      usage = "Mach O binary file which compdir must be fixed.")
  @Nullable
  private Path binary = null;

  @Option(
      name = OUTPUT_OPTION,
      required = true,
      usage = "The destination where the resulting binary should be stored.")
  @Nullable
  private Path output = null;

  @Option(
      name = OLD_COMPDIR_OPTION,
      required = true,
      usage = "Old value for compdir.")
  @Nullable
  private String oldCompdir = null;

  @Option(
      name = NEW_COMPDIR_OPTION,
      required = true,
      usage = "New value for compdir.")
  @Nullable
  private String updatedCompdir = null;

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    Preconditions.checkNotNull(binary, BINARY_OPTION + " must be set");
    Preconditions.checkNotNull(output, OUTPUT_OPTION + " must be set");
    Preconditions.checkNotNull(oldCompdir, OLD_COMPDIR_OPTION + " must be set");
    Preconditions.checkNotNull(updatedCompdir, NEW_COMPDIR_OPTION + " must be set");

    Files.copy(binary, output, StandardCopyOption.REPLACE_EXISTING);

    try (FileChannel file =
             FileChannel.open(
                 output,
                 StandardOpenOption.READ,
                 StandardOpenOption.WRITE)) {
      ByteBuffer byteBuffer = file.map(FileChannel.MapMode.READ_WRITE, 0, file.size());
      CompDirReplacer compDirReplacer = new CompDirReplacer(byteBuffer);
      compDirReplacer.replaceCompDir(oldCompdir, updatedCompdir);
    }

    return 0;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "fixes compilation directory inside Mach O binary";
  }
}
