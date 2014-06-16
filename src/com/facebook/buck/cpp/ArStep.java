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

package com.facebook.buck.cpp;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * Create an object archive with ar.
 */
public class ArStep extends ShellStep {

  public static final String AR = "ar";

  private final ImmutableSortedSet<Path> srcs;
  private final Path outputFile;

  public ArStep(ImmutableSortedSet<Path> srcs,  Path outputFile) {
    this.srcs = Preconditions.checkNotNull(srcs);
    this.outputFile = Preconditions.checkNotNull(outputFile);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> cmdBuilder = ImmutableList.builder();

    cmdBuilder
        .add(AR)
        .add("-q") /* do not check for existing objects */
        .add(outputFile.toString());
    for (Path src : srcs) {
      cmdBuilder.add(src.toString());
    }

    return cmdBuilder.build();
  }

  @Override
  public String getShortName() {
    return AR;
  }
}
