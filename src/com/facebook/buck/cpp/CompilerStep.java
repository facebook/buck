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
import java.nio.file.Paths;

import java.util.Collection;

/**
 * Call the given C/C++ compiler.
 *
 * <p> Note: In the future this class is likely to become abstract and have separate subclasses for
 * clang and gcc. </p>
 */
public class CompilerStep extends ShellStep {

  private final String compiler;
  private final boolean shouldLink;
  private final ImmutableSortedSet<Path> srcs;
  private final Path outputFile;
  private final boolean shouldAddProjectRootToIncludePaths;
  private final ImmutableSortedSet<Path> includePaths;
  private final ImmutableList<String> commandLineArgs;

  public CompilerStep(
      String compiler,
      boolean shouldLink,
      ImmutableSortedSet<Path> srcs,
      Path outputFile,
      boolean shouldAddProjectRootToIncludePaths,
      ImmutableSortedSet<Path> includePaths,
      Collection<String> commandLineArgs) {
    this.compiler = Preconditions.checkNotNull(compiler);
    this.shouldLink = shouldLink;
    this.srcs = Preconditions.checkNotNull(srcs);
    this.outputFile = Preconditions.checkNotNull(outputFile);
    this.shouldAddProjectRootToIncludePaths = shouldAddProjectRootToIncludePaths;
    this.includePaths = Preconditions.checkNotNull(includePaths);
    this.commandLineArgs = ImmutableList.copyOf(commandLineArgs);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> cmdBuilder = ImmutableList.builder();

    cmdBuilder.add(compiler);

    if (context.isDebugEnabled()) {
      cmdBuilder.add("-g");
    }

    if (!shouldLink) {
      cmdBuilder.add("-c");
    }

    if (shouldAddProjectRootToIncludePaths) {
      cmdBuilder.add("-I");
      cmdBuilder.add(context.getProjectDirectoryRoot().toString());
    }

    for (Path includePath : includePaths) {
      cmdBuilder.add("-I");
      cmdBuilder.add(includePath.toString());
    }

    cmdBuilder.addAll(commandLineArgs);

    for (Path src : srcs) {
      cmdBuilder.add(src.toString());
    }
    cmdBuilder.add("-o").add(outputFile.toString());

    return cmdBuilder.build();
  }

  @Override
  public String getShortName() {
    return Paths.get(compiler).getFileName().toString();
  }
}
