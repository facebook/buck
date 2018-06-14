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

import com.facebook.buck.util.ExitCode;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.kohsuke.args4j.Option;

/**
 * Class-template for Mach O Util commands. As these utils should not modify the buck output,
 * commands are expected to obtain input file, copy it to the specified output directory and then
 * modify the copy.
 */
public abstract class MachOAbstractCommand extends AbstractCommand {

  private static final String BINARY_OPTION = "--binary";
  private static final String OUTPUT_OPTION = "--output";
  private static final String OLD_COMPDIR_OPTION = "--old_compdir";
  private static final String NEW_COMPDIR_OPTION = "--new_compdir";

  @Option(
      name = BINARY_OPTION,
      required = true,
      usage = "Mach O binary file which object paths must be updated.")
  @SuppressFieldNotInitialized
  private Path binary;

  @Option(
      name = OUTPUT_OPTION,
      required = true,
      usage = "The destination where the resulting binary should be stored.")
  @SuppressFieldNotInitialized
  private Path output;

  @Option(name = OLD_COMPDIR_OPTION, required = true, usage = "Old value for compdir.")
  @SuppressFieldNotInitialized
  private String oldCompDir;

  @Option(name = NEW_COMPDIR_OPTION, required = true, usage = "New value for compdir.")
  @SuppressFieldNotInitialized
  private String updatedCompDir;

  public final Path getOutput() {
    return output;
  }

  public final String getOldCompDir() {
    return oldCompDir;
  }

  public final String getUpdatedCompDir() {
    return updatedCompDir;
  }

  @Override
  public final ExitCode runWithoutHelp(CommandRunnerParams params) throws IOException {
    Preconditions.checkNotNull(binary, BINARY_OPTION + " must be set");
    Preconditions.checkNotNull(output, OUTPUT_OPTION + " must be set");
    Preconditions.checkNotNull(oldCompDir, OLD_COMPDIR_OPTION + " must be set");
    Preconditions.checkNotNull(updatedCompDir, NEW_COMPDIR_OPTION + " must be set");
    Preconditions.checkState(
        !binary.equals(output), BINARY_OPTION + " must be different from " + OUTPUT_OPTION);
    Preconditions.checkArgument(
        oldCompDir.length() >= updatedCompDir.length(),
        "Updated compdir length must be less or equal to old compdir length as replace is "
            + "performed in place");
    Preconditions.checkArgument(
        !oldCompDir.equals(updatedCompDir), "Updated compdir must be different from old compdir");

    Files.copy(binary, output, StandardCopyOption.REPLACE_EXISTING);

    return invokeWithParams(params);
  }

  @Override
  public final boolean isReadOnly() {
    return true;
  }

  /** The override point for subclasses. */
  protected abstract ExitCode invokeWithParams(CommandRunnerParams params) throws IOException;
}
