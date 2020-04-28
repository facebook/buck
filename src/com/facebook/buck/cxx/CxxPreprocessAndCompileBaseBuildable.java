/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.OutputPath;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Base class for {@link Buildable} implementations that need to generate Cxx compilation commands.
 */
abstract class CxxPreprocessAndCompileBaseBuildable implements Buildable {
  @AddToRuleKey protected final BuildTarget targetName;
  /** The presence or absence of this field denotes whether the input needs to be preprocessed. */
  @AddToRuleKey protected final Optional<PreprocessorDelegate> preprocessDelegate;

  @AddToRuleKey protected final CompilerDelegate compilerDelegate;
  @AddToRuleKey protected final DebugPathSanitizer sanitizer;
  @AddToRuleKey protected final OutputPath output;
  @AddToRuleKey protected final SourcePath input;
  @AddToRuleKey protected final CxxSource.Type inputType;

  @AddToRuleKey protected final Optional<PrecompiledHeaderData> precompiledHeaderData;

  public CxxPreprocessAndCompileBaseBuildable(
      BuildTarget targetName,
      Optional<PreprocessorDelegate> preprocessDelegate,
      CompilerDelegate compilerDelegate,
      String outputName,
      SourcePath input,
      Optional<CxxPrecompiledHeader> precompiledHeaderRule,
      CxxSource.Type inputType,
      DebugPathSanitizer sanitizer) {
    this.targetName = targetName;
    this.preprocessDelegate = preprocessDelegate;
    this.compilerDelegate = compilerDelegate;
    this.sanitizer = sanitizer;
    this.output = new OutputPath(outputName);
    this.input = input;
    this.inputType = inputType;
    this.precompiledHeaderData = precompiledHeaderRule.map(CxxPrecompiledHeader::getData);
  }

  static Path getDepFilePath(Path outputPath) {
    return outputPath.getParent().resolve(outputPath.getFileName() + ".dep");
  }

  protected CxxPreprocessAndCompileStep.ToolCommand makeCommand(
      SourcePathResolverAdapter resolver, ImmutableList<Arg> arguments) {
    return new CxxPreprocessAndCompileStep.ToolCommand(
        compilerDelegate.getCommandPrefix(resolver),
        Arg.stringify(arguments, resolver),
        compilerDelegate.getEnvironment(resolver));
  }

  protected Optional<Path> getDepFile(Path resolvedOutput) {
    // Use a depfile if there's a preprocessing stage, this logic should be kept in sync with
    // getInputsAfterBuildingLocally.
    return CxxSourceTypes.supportsDepFiles(inputType)
        ? preprocessDelegate.map(ignored -> getDepFilePath(resolvedOutput))
        : Optional.empty();
  }

  protected CxxPreprocessAndCompileStep.Operation getOperation() {
    return preprocessDelegate.isPresent()
        ? CxxPreprocessAndCompileStep.Operation.PREPROCESS_AND_COMPILE
        : CxxPreprocessAndCompileStep.Operation.COMPILE;
  }

  protected ImmutableList<Arg> getArguments(
      ProjectFilesystem filesystem, CxxToolFlags preprocessorDelegateFlags) {
    return compilerDelegate.getArguments(
        preprocessorDelegateFlags, filesystem.getRootPath().getPath());
  }

  protected CxxToolFlags getCxxToolFlags(SourcePathResolverAdapter resolver) {
    return preprocessDelegate
        .map(delegate -> delegate.getFlagsWithSearchPaths(precompiledHeaderData, resolver))
        .orElseGet(CxxToolFlags::of);
  }

  protected HeaderPathNormalizer getHeaderPathNormalizer(BuildContext context) {
    // If we're compiling, this will just be empty.
    return preprocessDelegate
        .map(x -> x.getHeaderPathNormalizer(context))
        .orElseGet(() -> HeaderPathNormalizer.empty());
  }
}
