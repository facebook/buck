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

import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;

import java.nio.file.Path;

import javax.annotation.Nullable;

public class ThriftCompiler extends AbstractBuildRule implements AbiRule {

  private final SourcePath compiler;
  private final ImmutableList<String> flags;
  private final Path outputDir;
  private final SourcePath input;
  private final String language;
  private final ImmutableSet<String> options;
  private final ImmutableList<Path> includeRoots;
  private final ImmutableMap<Path, SourcePath> includes;

  public ThriftCompiler(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath compiler,
      ImmutableList<String> flags,
      Path outputDir,
      SourcePath input,
      String language,
      ImmutableSet<String> options,
      ImmutableList<Path> includeRoots,
      ImmutableMap<Path, SourcePath> includes) {
    super(params, resolver);
    this.compiler = compiler;
    this.flags = flags;
    this.outputDir = outputDir;
    this.input = input;
    this.language = language;
    this.options = options;
    this.includeRoots = includeRoots;
    this.includes = includes;
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of(getResolver().getPath(input));
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    builder
        .setReflectively("compiler", getResolver().getPath(compiler))
        .setReflectively("flags", flags)
        .setReflectively("outputDir", outputDir.toString())
        .setReflectively("options", ImmutableSortedSet.copyOf(options))
        .setReflectively("language", language);


    // Hash the layout of each potentially included thrift file dependency and it's contents.
    // We do this here, rather than returning them from `getInputsToCompareToOutput` so that
    // we can match the contents hash up with where it was laid out in the include search path.
    for (Path path : ImmutableSortedSet.copyOf(includes.keySet())) {
      builder.setReflectively("include(" + path + ")", includes.get(path));
    }

    return builder;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    buildableContext.recordArtifactsInDirectory(outputDir);

    return ImmutableList.of(
        new MakeCleanDirectoryStep(outputDir),
        new ThriftCompilerStep(
            getResolver().getPath(compiler),
            flags,
            outputDir,
            getResolver().getPath(input),
            language,
            options,
            includeRoots));
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    return Sha1HashCode.fromHashCode(HashCode.fromInt(0));
  }

}
