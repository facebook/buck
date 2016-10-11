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
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

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
    implements RuleKeyAppendable, SupportsDependencyFileRuleKey, SupportsInputBasedRuleKey {

  private final Path output;

  @AddToRuleKey
  private final PreprocessorDelegate preprocessorDelegate;
  @AddToRuleKey
  private final CompilerDelegate compilerDelegate;
  private final CxxToolFlags compilerFlags;
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
      CompilerDelegate compilerDelegate,
      CxxToolFlags compilerFlags,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer sanitizer) {
    super(buildRuleParams, resolver);
    this.preprocessorDelegate = preprocessorDelegate;
    this.compilerDelegate = compilerDelegate;
    this.compilerFlags = compilerFlags;
    this.output = output;
    this.input = input;
    this.inputType = inputType;
    this.sanitizer = sanitizer;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("compilationDirectory", sanitizer.getCompilationDirectory());
    sink.setReflectively(
        "compilerFlagsPlatform",
        sanitizer.sanitizeFlags(compilerFlags.getPlatformFlags()));
    sink.setReflectively(
        "compilerFlagsRule",
        sanitizer.sanitizeFlags(compilerFlags.getRuleFlags()));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    Path scratchDir =
        BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s_tmp");
    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), output.getParent()),
        new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir),
        makeMainStep(scratchDir));
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
  public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() throws IOException {
    return preprocessorDelegate.getPossibleInputSourcePaths();
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally() throws IOException {
    return ImmutableList.<SourcePath>builder()
        .addAll(preprocessorDelegate.getInputsAfterBuildingLocally(readDepFileLines()))
        .add(input)
        .build();
  }

  @Override
  public boolean isCacheable() {
    return false;
  }

  private Path getDepFilePath() {
    return output.getFileSystem().getPath(output.toString() + ".dep");
  }

  public ImmutableList<String> readDepFileLines() throws IOException {
    return ImmutableList.copyOf(getProjectFilesystem().readLines(getDepFilePath()));
  }

  @VisibleForTesting
  CxxPreprocessAndCompileStep makeMainStep(Path scratchDir) {
    try {
      preprocessorDelegate.checkForConflictingHeaders();
    } catch (PreprocessorDelegate.ConflictingHeadersException e) {
      throw e.getHumanReadableExceptionForBuildTarget(getBuildTarget());
    }
    return new CxxPreprocessAndCompileStep(
        getProjectFilesystem(),
        CxxPreprocessAndCompileStep.Operation.GENERATE_PCH,
        getPathToOutput(),
        getDepFilePath(),
        // TODO(10194465): This uses relative path so as to get relative paths in the dep file
        getResolver().getRelativePath(input),
        inputType,
        Optional.of(
            new CxxPreprocessAndCompileStep.ToolCommand(
                preprocessorDelegate.getCommandPrefix(),
                preprocessorDelegate.getArguments(compilerFlags),
                preprocessorDelegate.getEnvironment(),
                preprocessorDelegate.getFlagsForColorDiagnostics())),
        Optional.absent(),
        preprocessorDelegate.getHeaderPathNormalizer(),
        sanitizer,
        preprocessorDelegate.getHeaderVerification(),
        scratchDir,
        true,
        compilerDelegate.getCompiler());
  }
}
