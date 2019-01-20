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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;

/** A step which extracts specific sections from an ELF file into a new ELF file. */
class ElfExtractSectionsStep extends ShellStep {

  private final ImmutableList<String> objcopyPrefix;
  private final ImmutableSet<String> sections;
  private final ProjectFilesystem inputFilesystem;
  private final Path input;
  private final ProjectFilesystem outputFilesystem;
  private final Path output;

  public ElfExtractSectionsStep(
      ImmutableList<String> objcopyPrefix,
      ImmutableSet<String> sections,
      ProjectFilesystem inputFilesystem,
      Path input,
      ProjectFilesystem outputFilesystem,
      Path output) {
    super(outputFilesystem.getRootPath());
    this.objcopyPrefix = objcopyPrefix;
    this.sections = sections;
    this.inputFilesystem = inputFilesystem;
    this.input = input;
    this.outputFilesystem = outputFilesystem;
    this.output = output;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.addAll(objcopyPrefix);
    for (String section : sections) {
      args.add("--only-section", section);
    }
    args.add(inputFilesystem.resolve(input).toString());
    args.add(outputFilesystem.resolve(output).toString());
    return args.build();
  }

  @Override
  public final String getShortName() {
    return "scrub_symbol_table";
  }
}
