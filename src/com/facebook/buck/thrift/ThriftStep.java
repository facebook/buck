/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.thrift;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Collection;

public class ThriftStep extends ShellStep {

  private final Path src;
  private final Optional<Path> outputDir;
  private final Optional<Path> outputLocation;
  private final ImmutableSortedSet<Path> includePaths;
  private final ImmutableSortedSet<String> generators;
  private final ImmutableList<String> commandLineArgs;

  public ThriftStep(
      Path src,
      Optional<Path> outputDir,
      Optional<Path> outputLocation,
      ImmutableSortedSet<Path> includePaths,
      ImmutableSortedSet<String> generators,
      Collection<String> commandLineArgs) {
    this.src = src;
    this.outputDir = outputDir;
    this.outputLocation = outputLocation;
    this.includePaths = includePaths;
    this.generators = generators;
    this.commandLineArgs = ImmutableList.copyOf(commandLineArgs);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {

    ImmutableList.Builder<String> cmdBuilder = ImmutableList.builder();

    cmdBuilder.add("thrift");

    if (outputDir.isPresent()) {
      cmdBuilder.add("-o");
      cmdBuilder.add(outputDir.get().toString());
    }

    if (outputLocation.isPresent()) {
      cmdBuilder.add("-out");
      cmdBuilder.add(outputLocation.get().toString());
    }


    for (Path includePath : includePaths) {
      cmdBuilder.add("-I");
      cmdBuilder.add(includePath.toString());
    }

    cmdBuilder.addAll(commandLineArgs);

    for (String gen : generators) {
      cmdBuilder.add("--gen");
      cmdBuilder.add(gen);
    }

    cmdBuilder.add(src.toString());

    return cmdBuilder.build();
  }

  @Override
  public String getShortName() {
    return "thrift";
  }

}
