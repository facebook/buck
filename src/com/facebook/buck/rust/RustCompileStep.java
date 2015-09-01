/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rust;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public class RustCompileStep extends ShellStep {

  private final ImmutableList<String> compilerCommandPrefix;
  private final ImmutableList<String> flags;
  private final ImmutableSet<String> features;
  private final Path output;
  private final ImmutableMap<String, Path> crates;
  private final Path crateRoot;

  public RustCompileStep(
      Path workingDirectory,
      ImmutableList<String> compilerCommandPrefix,
      ImmutableList<String> flags,
      ImmutableSet<String> features,
      Path output,
      ImmutableMap<String, Path> crates,
      Path crateRoot) {
    super(workingDirectory);
    this.compilerCommandPrefix = compilerCommandPrefix;
    this.flags = flags;
    this.features = features;
    this.output = output;
    this.crates = crates;
    this.crateRoot = crateRoot;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.<String>builder()
        .addAll(compilerCommandPrefix)
        .addAll(flags)
        .add("-o", output.toString());

    for (String feature : features) {
      commandBuilder.add("--cfg", String.format("feature=\"%s\"", feature));
    }

    for (ImmutableMap.Entry<String, Path> entry : crates.entrySet()) {
      commandBuilder.add(
          "--extern",
          String.format("%s=%s", entry.getKey(), entry.getValue()));
    }

    return commandBuilder
        .add(crateRoot.toString())
        .build();
  }

  @Override
  public String getShortName() {
    return "rust compile";
  }
}
