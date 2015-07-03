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
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * Run strip on a binary.
 */
public class StripStep extends ShellStep {

  private final ImmutableList<String> stripCommandPrefix;
  private final ImmutableList<String> flags;
  private final Path source;
  private final Path destination;

  public StripStep(
      ImmutableList<String> stripCommandPrefix,
      ImmutableList<String> flags,
      Path source,
      Path destination) {
    this.stripCommandPrefix = stripCommandPrefix;
    this.flags = flags;
    this.source = source;
    this.destination = destination;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .addAll(stripCommandPrefix)
        .addAll(flags)
        .add(source.toString())
        .add("-o")
        .add(destination.toString())
        .build();
  }

  @Override
  public String getShortName() {
    return "strip";
  }

}
