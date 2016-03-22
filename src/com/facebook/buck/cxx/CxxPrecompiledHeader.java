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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasPostBuildSteps;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Rule to generate a precompiled header from an existing header.
 *
 * Precompiled headers are only useful for compilation style where preprocessing and compiling are
 * both done in the same process. If a preprocessed output needs to be serialized and later read
 * back in, the entire rationale of using a precompiled header, to avoid parsing excess headers,
 * is obviated.
 *
 * PCH files are not very portable, and so they are not cached.
 * <ul>
 *   <li>The compiler verifies that header mtime identical to that recorded in the PCH file.</li>
 *   <li>
 *     PCH file records absolute paths, limited support for "relocatable" pch exists in Clang, but
 *     it is not very flexible.
 *   </li>
 * </ul>
 * While the problems are not impossible to overcome, PCH generation is fast enough that it isn't a
 * significant problem. The PCH file is only generated when a source file needs to be compiled,
 * anyway.
 *
 * Additionally, since PCH files contain information like timestamps, absolute paths, and
 * (effectively) random unique IDs, they are not amenable to the InputBasedRuleKey optimization when
 * used to compile another file.
 */
public class CxxPrecompiledHeader
    extends AbstractBuildRule
    implements HasPostBuildSteps, RuleKeyAppendable, SupportsDependencyFileRuleKey {

  private final Path output;

  @AddToRuleKey
  private final PreprocessorDelegate preprocessorDelegate;
  @AddToRuleKey
  private final SourcePath input;
  @AddToRuleKey
  private final CxxSource.Type inputType;

  private final DebugPathSanitizer sanitizer;

  public CxxPrecompiledHeader(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Path output,
      PreprocessorDelegate preprocessorDelegate,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer sanitizer) {
    super(buildRuleParams, resolver);
    this.preprocessorDelegate = preprocessorDelegate;
    this.output = output;
    this.input = input;
    this.inputType = inputType;
    this.sanitizer = sanitizer;
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    builder.setReflectively("compilationDirectory", sanitizer.getCompilationDirectory());
    return builder;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public ImmutableList<Step> getPostBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    // Check for conflicting headers.
    try {
      preprocessorDelegate.checkForConflictingHeaders();
    } catch (PreprocessorDelegate.ConflictingHeadersException e) {
      throw e.getHumanReadableExceptionForBuildTarget(getBuildTarget());
    }

    Path scratchDir = BuildTargets.getScratchPath(getBuildTarget(), "%s_tmp");
    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), output.getParent()),
        new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir),
        new CxxPreprocessAndCompileStep(
            getProjectFilesystem(),
            CxxPreprocessAndCompileStep.Operation.GENERATE_PCH,
            getPathToOutput(),
            getDepFilePath(),
            getResolver().getAbsolutePath(input),
            inputType,
            Optional.of(
                new CxxPreprocessAndCompileStep.ToolCommand(
                    // Constructing invocation from parts instead of using getCommand because it
                    // adds some undesirable flags via preprocessor.getExtraFlags().
                    ImmutableList.<String>builder()
                        .addAll(
                            preprocessorDelegate.getPreprocessor().getCommandPrefix(getResolver()))
                        .addAll(preprocessorDelegate.getFlagsWithSearchPaths().getAllFlags())
                        .build(),
                    preprocessorDelegate.getEnvironment(),
                    preprocessorDelegate.getFlagsForColorDiagnostics())),
            Optional.<CxxPreprocessAndCompileStep.ToolCommand>absent(),
            preprocessorDelegate.getReplacementPaths(),
            sanitizer,
            Optional.<Function<String, Iterable<String>>>absent(),
            scratchDir));
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return true;
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally() throws IOException {
    return ImmutableList.<SourcePath>builder()
        .addAll(preprocessorDelegate.getInputsAfterBuildingLocally(readDepFileLines()))
        .add(input)
        .build();
  }

  private Path getDepFilePath() {
    return output.getFileSystem().getPath(output.toString() + ".dep");
  }

  private ImmutableList<String> readDepFileLines() throws IOException {
    return ImmutableList.copyOf(getProjectFilesystem().readLines(getDepFilePath()));
  }
}
