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
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Map;

/**
 * A build rule which preprocesses a C/C++ source.
 */
public class CxxPreprocess extends AbstractBuildRule {

  private final SourcePath preprocessor;
  private final ImmutableList<String> flags;
  private final Path output;
  private final SourcePath input;
  private final ImmutableList<Path> includeRoots;
  private final ImmutableList<Path> systemIncludeRoots;
  private final CxxHeaders includes;

  public CxxPreprocess(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath preprocessor,
      ImmutableList<String> flags,
      Path output,
      SourcePath input,
      ImmutableList<Path> includeRoots,
      ImmutableList<Path> systemIncludeRoots,
      CxxHeaders includes) {
    super(params, resolver);
    this.preprocessor = preprocessor;
    this.flags = flags;
    this.output = output;
    this.input = input;
    this.includeRoots = includeRoots;
    this.systemIncludeRoots = systemIncludeRoots;
    this.includes = includes;
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of(getResolver().getPath(input));
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    builder
        .setInput("preprocessor", preprocessor)
        .set("flags", flags)
        .set("output", output.toString());

    // Hash the layout of each potentially included C/C++ header file and it's contents.
    // We do this here, rather than returning them from `getInputsToCompareToOutput` so
    // that we can match the contents hash up with where it was laid out in the include
    // search path, and therefore can accurately capture header file renames.
    for (Path path : ImmutableSortedSet.copyOf(includes.nameToPathMap().keySet())) {
      SourcePath source = includes.nameToPathMap().get(path);
      builder.setInput("include(" + path + ")", getResolver().getPath(source));
    }

    return builder;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);

    // Resolve the map of symlinks to real paths to hand off the preprocess step.
    ImmutableMap.Builder<Path, Path> replacementPathsBuilder = ImmutableMap.builder();
    for (Map.Entry<Path, SourcePath> entry : includes.fullNameToPathMap().entrySet()) {
      replacementPathsBuilder.put(entry.getKey(), getResolver().getPath(entry.getValue()));
    }
    ImmutableMap<Path, Path> replacementPaths = replacementPathsBuilder.build();

    return ImmutableList.of(
        new MkdirStep(output.getParent()),
        new CxxPreprocessStep(
            getResolver().getPath(preprocessor),
            flags,
            output,
            getResolver().getPath(input),
            includeRoots,
            systemIncludeRoots,
            replacementPaths));
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }

  public SourcePath getPreprocessor() {
    return preprocessor;
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

  public CxxHeaders getIncludes() {
    return includes;
  }

}
