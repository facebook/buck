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

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * A build rule which preprocesses, compiles, and assembles a C/C++ source.
 */
public class CxxCompile extends AbstractBuildRule {

  private final Path compiler;
  private final ImmutableList<String> flags;
  private final Path output;
  private final SourcePath input;
  private final ImmutableList<Path> includeRoots;
  private final ImmutableList<Path> systemIncludeRoots;
  private final ImmutableMap<Path, SourcePath> includes;

  public CxxCompile(
      BuildRuleParams params,
      Path compiler,
      ImmutableList<String> flags,
      Path output,
      SourcePath input,
      ImmutableList<Path> includeRoots,
      ImmutableList<Path> systemIncludeRoots,
      ImmutableMap<Path, SourcePath> includes) {
    super(params);
    this.compiler = Preconditions.checkNotNull(compiler);
    this.flags = Preconditions.checkNotNull(flags);
    this.output = Preconditions.checkNotNull(output);
    this.input = Preconditions.checkNotNull(input);
    this.includeRoots = Preconditions.checkNotNull(includeRoots);
    this.systemIncludeRoots = Preconditions.checkNotNull(systemIncludeRoots);
    this.includes = Preconditions.checkNotNull(includes);
  }

  @Override
  protected Iterable<Path> getInputsToCompareToOutput() {
    return ImmutableList.of(input.resolve());
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    builder
        .setInput("compiler", compiler)
        .set("flags", flags)
        .set("output", output.toString());

    // Hash the layout of each potentially included C/C++ header file and it's contents.
    // We do this here, rather than returning them from `getInputsToCompareToOutput` so
    // that we can match the contents hash up with where it was laid out in the include
    // search path, and therefore can accurately capture header file renames.
    for (Path path : ImmutableSortedSet.copyOf(includes.keySet())) {
      SourcePath source = includes.get(path);
      builder.setInput("include(" + path + ")", source.resolve());
    }

    return builder;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        new MkdirStep(output.getParent()),
        new CxxCompileStep(
            compiler,
            flags,
            output,
            input.resolve(),
            includeRoots,
            systemIncludeRoots));
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }

  public Path getCompiler() {
    return compiler;
  }

  public ImmutableList<String> getFlags() {
    return flags;
  }

  public Path getOutput() {
    return output;
  }

  public SourcePath getInput() {
    return input;
  }

  public ImmutableList<Path> getIncludeRoots() {
    return includeRoots;
  }

  public ImmutableList<Path> getSystemIncludeRoots() {
    return systemIncludeRoots;
  }

  public ImmutableMap<Path, SourcePath> getIncludes() {
    return includes;
  }

}
