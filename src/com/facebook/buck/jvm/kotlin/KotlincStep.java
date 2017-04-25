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
// Copyright 2004-present Facebook. All Rights Reserved.

package com.facebook.buck.jvm.kotlin;

import static com.google.common.collect.Iterables.transform;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.nio.file.Path;

public class KotlincStep extends ShellStep {

  private static final String CLASSPATH_FLAG = "-cp";
  private static final String DESTINATION_FLAG = "-d";
  private static final String INCLUDE_RUNTIME_FLAG = "-include-runtime";

  private final Tool kotlinc;
  private final SourcePathResolver resolver;
  private final ImmutableSortedSet<Path> declaredClassPathEntries;
  private final Path outputDirectory;
  private final ImmutableList<String> extraArguments;
  private final ImmutableSortedSet<Path> sourceFilePaths;

  KotlincStep(
      Tool kotlinc,
      ImmutableList<String> extraArguments,
      SourcePathResolver resolver,
      Path outputDirectory,
      ImmutableSortedSet<Path> sourceFilePaths,
      ImmutableSortedSet<Path> declaredClassPathEntries,
      ProjectFilesystem filesystem) {
    super(filesystem.getRootPath());
    this.kotlinc = kotlinc;
    this.resolver = resolver;
    this.declaredClassPathEntries = declaredClassPathEntries;
    this.outputDirectory = outputDirectory;
    this.extraArguments = extraArguments;
    this.sourceFilePaths = sourceFilePaths;
  }

  @Override
  public String getShortName() {
    return Joiner.on(" ").join(kotlinc.getCommandPrefix(resolver));
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    final ImmutableList.Builder<String> command =
        ImmutableList.<String>builder().addAll(kotlinc.getCommandPrefix(resolver));

    String classpath =
        Joiner.on(File.pathSeparator).join(transform(declaredClassPathEntries, Object::toString));
    command
        .add(INCLUDE_RUNTIME_FLAG)
        .add(CLASSPATH_FLAG)
        .add(classpath.isEmpty() ? "''" : classpath)
        .add(DESTINATION_FLAG)
        .add(outputDirectory.toString());

    command.addAll(extraArguments).addAll(transform(sourceFilePaths, Object::toString));
    return command.build();
  }
}
