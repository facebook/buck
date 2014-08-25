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

package com.facebook.buck.cxx;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class LexStep extends ShellStep {

  private final Path lex;
  private final ImmutableList<String> flags;
  private final Path outputSource;
  private final Path outputHeader;
  private final Path input;

  public LexStep(
      Path lex,
      ImmutableList<String> flags,
      Path outputSource,
      Path outputHeader,
      Path input) {

    this.lex = Preconditions.checkNotNull(lex);
    this.flags = Preconditions.checkNotNull(flags);
    this.outputSource = Preconditions.checkNotNull(outputSource);
    this.outputHeader = Preconditions.checkNotNull(outputHeader);
    this.input = Preconditions.checkNotNull(input);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .add(lex.toString())
        .addAll(flags)
        .add("--outfile=" + outputSource.toString())
        .add("--header-file=" + outputHeader.toString())
        .add(input.toString())
        .build();
  }

  @Override
  public String getShortName() {
    return "lex";
  }

}
