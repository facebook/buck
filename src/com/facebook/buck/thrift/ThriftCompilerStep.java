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
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

public class ThriftCompilerStep extends ShellStep {

  private final Path compiler;
  private final ImmutableList<String> flags;
  private final Path outputDir;
  private final Path input;
  private final String language;
  private final ImmutableSet<String> options;
  private final ImmutableList<Path> includes;

  public ThriftCompilerStep(
      Path compiler,
      ImmutableList<String> flags,
      Path outputDir,
      Path input,
      String language,
      ImmutableSet<String> options,
      ImmutableList<Path> includes) {

    this.compiler = Preconditions.checkNotNull(compiler);
    this.flags = Preconditions.checkNotNull(flags);
    this.outputDir = Preconditions.checkNotNull(outputDir);
    this.input = Preconditions.checkNotNull(input);
    this.language = Preconditions.checkNotNull(language);
    this.options = Preconditions.checkNotNull(options);
    this.includes = Preconditions.checkNotNull(includes);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .add(compiler.toString())
        .addAll(flags)
        .add("--gen", String.format("%s:%s", language, Joiner.on(',').join(options)))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-I"),
                Iterables.transform(includes, Functions.toStringFunction())))
        .add("-o", outputDir.toString())
        .add(input.toString())
        .build();
  }

  @Override
  public String getShortName() {
    return "thrift";
  }

  @Override
  public boolean equals(Object o) {

    if (this == o) {
      return true;
    }

    if (!(o instanceof ThriftCompilerStep)) {
      return false;
    }

    ThriftCompilerStep that = (ThriftCompilerStep) o;

    if (!compiler.equals(that.compiler)) {
      return false;
    }

    if (!flags.equals(that.flags)) {
      return false;
    }

    if (!includes.equals(that.includes)) {
      return false;
    }

    if (!input.equals(that.input)) {
      return false;
    }

    if (!language.equals(that.language)) {
      return false;
    }

    if (!options.equals(that.options)) {
      return false;
    }

    if (!outputDir.equals(that.outputDir)) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(compiler, flags, outputDir, input, language, options, includes);
  }

}
