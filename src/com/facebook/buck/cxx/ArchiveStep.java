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

package com.facebook.buck.cxx;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

/**
 * Create an object archive with ar.
 */
public class ArchiveStep extends ShellStep {

  private final Path archiver;
  private final Path output;
  private final ImmutableList<Path> inputs;

  public ArchiveStep(
      Path archiver,
      Path output,
      ImmutableList<Path> inputs) {
    this.archiver = Preconditions.checkNotNull(archiver);
    this.output = Preconditions.checkNotNull(output);
    this.inputs = Preconditions.checkNotNull(inputs);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .add(archiver.toString())
        .add("rcs")
        .add(output.toString())
        .addAll(Iterables.transform(inputs, Functions.toStringFunction()))
        .build();
  }

  @Override
  public String getShortName() {
    return "archive";
  }

}
