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
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import java.nio.file.Path;
import java.util.Map;

/**
 * A build rule which preprocesses a C/C++ source.
 */
public class CxxPreprocess extends AbstractBuildRule {

  private final Tool preprocessor;
  private final ImmutableList<String> flags;
  private final Path output;
  private final SourcePath input;
  private final ImmutableList<Path> includeRoots;
  private final ImmutableList<Path> systemIncludeRoots;
  private final ImmutableList<Path> frameworkRoots;
  private final CxxHeaders includes;
  private final Optional<DebugPathSanitizer> sanitizer;

  public CxxPreprocess(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Tool preprocessor,
      ImmutableList<String> flags,
      Path output,
      SourcePath input,
      ImmutableList<Path> includeRoots,
      ImmutableList<Path> systemIncludeRoots,
      ImmutableList<Path> frameworkRoots,
      CxxHeaders includes,
      Optional<DebugPathSanitizer> sanitizer) {
    super(params, resolver);
    this.preprocessor = preprocessor;
    this.flags = flags;
    this.output = output;
    this.input = input;
    this.includeRoots = includeRoots;
    this.systemIncludeRoots = systemIncludeRoots;
    this.frameworkRoots = frameworkRoots;
    this.includes = includes;
    this.sanitizer = sanitizer;
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return getResolver().filterInputsToCompareToOutput(input);
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    builder
        .set("preprocessor", preprocessor)
        .set("output", output.toString());

    // Sanitize any relevant paths in the flags we pass to the preprocessor, to prevent them
    // from contributing to the rule key.
    ImmutableList<String> flags = this.flags;
    if (sanitizer.isPresent()) {
      flags = FluentIterable.from(flags)
          .transform(sanitizer.get().sanitize(Optional.<Path>absent(), /* expandPaths */ false))
          .toList();
    }
    builder.set("flags", flags);

    // Hash the layout of each potentially included C/C++ header file and it's contents.
    // We do this here, rather than returning them from `getInputsToCompareToOutput` so
    // that we can match the contents hash up with where it was laid out in the include
    // search path, and therefore can accurately capture header file renames.
    for (Path path : ImmutableSortedSet.copyOf(includes.nameToPathMap().keySet())) {
      SourcePath source = includes.nameToPathMap().get(path);
      builder.setInput("include(" + path + ")", getResolver().getPath(source));
    }

    builder.set(
        "frameworkRoots",
        FluentIterable.from(frameworkRoots)
            .transform(Functions.toStringFunction())
            .toSortedSet(Ordering.natural()));

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
            preprocessor.getCommandPrefix(getResolver()),
            flags,
            output,
            getResolver().getPath(input),
            includeRoots,
            systemIncludeRoots,
            frameworkRoots,
            replacementPaths,
            sanitizer));
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }

  public Tool getPreprocessor() {
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

  public ImmutableList<Path> getFrameworkRoots() {
    return frameworkRoots;
  }

  public CxxHeaders getIncludes() {
    return includes;
  }

}
