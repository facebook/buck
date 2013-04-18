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

package com.facebook.buck.shell;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.Set;

public class InstrumentCommand extends ShellCommand {

  private final String mode;
  private final Set<String> instrumentDirectories;

  public InstrumentCommand(String mode, Set<String> instrumentDirectories) {
    this.mode = mode;
    this.instrumentDirectories = ImmutableSet.copyOf(instrumentDirectories);
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return String.format("emma instr -out %s", JUnitCommand.EMMA_OUTPUT_DIR);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("java");

    // Add EMMA to the class path.
    args.add("-classpath", JUnitCommand.PATH_TO_EMMA_JAR);

    args.add("emma", "instr");

    args.add("-outmode", mode);

    // Specify the output path to the EMMA metadata file.
    args.add("-outfile", String.format("%s/coverage.em", JUnitCommand.EMMA_OUTPUT_DIR));

    // Create a comma-delimited string of instrumentation directories.
    String pathsToInstrument = Joiner.on(",").join(instrumentDirectories);

    // Specify the set of paths with class files to instrument.
    args.add("-instrpath").add(pathsToInstrument);

    return args.build();
  }
}
