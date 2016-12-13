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
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A build rule which preprocesses and/or compiles a C/C++ source in a single step.
 */
public class CxxPreprocessAndCompile
    extends AbstractBuildRule
    implements RuleKeyAppendable, SupportsInputBasedRuleKey, SupportsDependencyFileRuleKey {

  @AddToRuleKey
  private final CxxPreprocessAndCompileStep.Operation operation;
  @AddToRuleKey
  private final Optional<PreprocessorDelegate> preprocessDelegate;
  @AddToRuleKey
  private final CompilerDelegate compilerDelegate;
  @AddToRuleKey(stringify = true)
  private final Path output;
  @AddToRuleKey
  private final SourcePath input;
  private final Optional<PrecompiledHeaderReference> precompiledHeaderRef;
  private final CxxSource.Type inputType;
  private final DebugPathSanitizer compilerSanitizer;
  private final DebugPathSanitizer assemblerSanitizer;
  private final Optional<SymlinkTree> sandboxTree;

  @VisibleForTesting
  public CxxPreprocessAndCompile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      CxxPreprocessAndCompileStep.Operation operation,
      Optional<PreprocessorDelegate> preprocessDelegate,
      CompilerDelegate compilerDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      Optional<PrecompiledHeaderReference> precompiledHeaderRef,
      DebugPathSanitizer compilerSanitizer,
      DebugPathSanitizer assemblerSanitizer,
      Optional<SymlinkTree> sandboxTree) {
    super(params, resolver);
    this.sandboxTree = sandboxTree;
    Preconditions.checkState(operation.isPreprocess() == preprocessDelegate.isPresent());
    if (precompiledHeaderRef.isPresent()) {
      Preconditions.checkState(
          operation == CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO,
          "Precompiled headers can only be used for same process preprocess/compile operations.");
    }
    this.operation = operation;
    this.preprocessDelegate = preprocessDelegate;
    this.compilerDelegate = compilerDelegate;
    this.output = output;
    this.input = input;
    this.inputType = inputType;
    this.precompiledHeaderRef = precompiledHeaderRef;
    this.compilerSanitizer = compilerSanitizer;
    this.assemblerSanitizer = assemblerSanitizer;
    performChecks(params);
  }

  private void performChecks(BuildRuleParams params) {
    Preconditions.checkArgument(
        !params.getBuildTarget().getFlavors().contains(CxxStrip.RULE_FLAVOR) ||
            !StripStyle.FLAVOR_DOMAIN.containsAnyOf(params.getBuildTarget().getFlavors()),
        "CxxPreprocessAndCompile should not be created with CxxStrip flavors");
    Preconditions.checkArgument(
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(params.getBuildTarget().getFlavors()),
        "CxxPreprocessAndCompile %s should not be created with LinkerMapMode flavor (%s)",
        this,
        LinkerMapMode.FLAVOR_DOMAIN);
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} step that compiles the given preprocessed source.
   */
  public static CxxPreprocessAndCompile compile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      CompilerDelegate compilerDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer compilerSanitizer,
      DebugPathSanitizer assemblerSanitizer,
      Optional<SymlinkTree> sandboxTree) {
    return new CxxPreprocessAndCompile(
        params,
        resolver,
        CxxPreprocessAndCompileStep.Operation.COMPILE,
        Optional.empty(),
        compilerDelegate,
        output,
        input,
        inputType,
        Optional.empty(),
        compilerSanitizer,
        assemblerSanitizer,
        sandboxTree);
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} step that preprocesses and compiles the given source.
   */
  public static CxxPreprocessAndCompile preprocessAndCompile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      PreprocessorDelegate preprocessorDelegate,
      CompilerDelegate compilerDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      Optional<PrecompiledHeaderReference> precompiledHeaderRef,
      DebugPathSanitizer compilerSanitizer,
      DebugPathSanitizer assemblerSanitizer,
      Optional<SymlinkTree> sandboxTree) {
    return new CxxPreprocessAndCompile(
        params,
        resolver,
        CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO,
        Optional.of(preprocessorDelegate),
        compilerDelegate,
        output,
        input,
        inputType,
        precompiledHeaderRef,
        compilerSanitizer,
        assemblerSanitizer,
        sandboxTree);
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    // If a sanitizer is being used for compilation, we need to record the working directory in
    // the rule key, as changing this changes the generated object file.
    if (operation == CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO) {
      sink.setReflectively("compilationDirectory", compilerSanitizer.getCompilationDirectory());
    }
    if (sandboxTree.isPresent()) {
      ImmutableMap<Path, SourcePath> links = sandboxTree.get().getLinks();
      for (Path path : ImmutableSortedSet.copyOf(links.keySet())) {
        SourcePath source = links.get(path);
        sink.setReflectively("sandbox(" + path.toString() + ")", source);
      }
    }
  }

  private Path getDepFilePath() {
    return output.getFileSystem().getPath(output.toString() + ".dep");
  }

  @VisibleForTesting
  CxxPreprocessAndCompileStep makeMainStep(Path scratchDir, boolean useArgfile) {

    // Check for conflicting headers.
    if (preprocessDelegate.isPresent()) {
      try {
        preprocessDelegate.get().checkForConflictingHeaders();
      } catch (PreprocessorDelegate.ConflictingHeadersException e) {
        throw e.getHumanReadableExceptionForBuildTarget(getBuildTarget());
      }
    }

    // If we're compiling, this will just be empty.
    HeaderPathNormalizer headerPathNormalizer =
        preprocessDelegate.isPresent() ?
            preprocessDelegate.get().getHeaderPathNormalizer() :
            HeaderPathNormalizer.empty(getResolver());

    Optional<CxxPreprocessAndCompileStep.ToolCommand> preprocessorCommand;
    if (operation.isPreprocess()) {
      preprocessorCommand = Optional.of(
          new CxxPreprocessAndCompileStep.ToolCommand(
              preprocessDelegate.get().getCommandPrefix(),
              preprocessDelegate.get().getArguments(
                  compilerDelegate.getCompilerFlags(), Optional.empty()),
              preprocessDelegate.get().getEnvironment(),
              preprocessDelegate.get().getFlagsForColorDiagnostics()));
    } else {
      preprocessorCommand = Optional.empty();
    }

    Optional<CxxPreprocessAndCompileStep.ToolCommand> compilerCommand;
    if (operation.isCompile()) {
      ImmutableList<String> arguments;
      if (operation == CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO) {
        Optional<CxxPrecompiledHeader> pch;
        if (precompiledHeaderRef.isPresent()) {
          pch = Optional.of(precompiledHeaderRef.get().getPrecompiledHeader());
        } else {
          pch = Optional.empty();
        }
        arguments =
            compilerDelegate.getArguments(preprocessDelegate.get().getFlagsWithSearchPaths(pch));
      } else {
        arguments = compilerDelegate.getArguments(CxxToolFlags.of());
      }
      compilerCommand = Optional.of(
          new CxxPreprocessAndCompileStep.ToolCommand(
              compilerDelegate.getCommandPrefix(),
              arguments,
              compilerDelegate.getEnvironment(),
              compilerDelegate.getFlagsForColorDiagnostics()));
    } else {
      compilerCommand = Optional.empty();
    }

    return new CxxPreprocessAndCompileStep(
        getProjectFilesystem(),
        operation,
        output,
        getDepFilePath(),
        // TODO(10194465): This uses relative paths where possible so as to get relative paths in
        // the dep file
        getResolver().deprecatedGetPath(input),
        inputType,
        preprocessorCommand,
        compilerCommand,
        /*pch*/ Optional.empty(),
        headerPathNormalizer,
        compilerSanitizer,
        assemblerSanitizer,
        preprocessDelegate.isPresent() ?
            preprocessDelegate.get().getHeaderVerification() :
            HeaderVerification.of(HeaderVerification.Mode.IGNORE),
        scratchDir,
        useArgfile,
        compilerDelegate.getCompiler());
  }

  @Override
  public String getType() {
    if (operation.isCompile() && operation.isPreprocess()) {
      return "cxx_preprocess_compile";
    } else if (operation.isPreprocess()) {
      return "cxx_preprocess";
    } else {
      return "cxx_compile";
    }
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    Path scratchDir =
        BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s-tmp");
    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), output.getParent()),
        new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir),
        makeMainStep(scratchDir, compilerDelegate.isArgFileSupported()));
  }

  @VisibleForTesting
  Optional<PreprocessorDelegate> getPreprocessorDelegate() {
    return preprocessDelegate;
  }

  // Used for compdb
  public ImmutableList<String> getCommand() {
    if (operation == CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO) {
      return makeMainStep(getProjectFilesystem().getRootPath(), false).getCommand();
    }

    PreprocessorDelegate effectivePreprocessorDelegate = preprocessDelegate.get();
    ImmutableList.Builder<String> cmd = ImmutableList.builder();
    cmd.addAll(
        compilerDelegate.getCommand(
            effectivePreprocessorDelegate.getFlagsWithSearchPaths(/*pch*/ Optional.empty())));
    // use the input of the preprocessor, since the fact that this is going through preprocessor is
    // hidden to compdb.
    cmd.add("-x", inputType.getLanguage());
    cmd.add("-c");
    cmd.add("-o", output.toString());
    cmd.add(getResolver().getAbsolutePath(input).toString());
    return cmd.build();
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  public SourcePath getInput() {
    return input;
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return compilerDelegate.isDependencyFileSupported() && operation.isPreprocess();
  }

  @Override
  public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() {
    if (preprocessDelegate.isPresent()) {
      return preprocessDelegate.get().getPossibleInputSourcePaths();
    }

    return Optional.empty();
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally() throws IOException {
    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();

    // If present, include all inputs coming from the preprocessor tool.
    if (preprocessDelegate.isPresent()) {
      Iterable<String> depFileLines = readDepFileLines();
      if (precompiledHeaderRef.isPresent()) {
        depFileLines =
            Iterables.concat(precompiledHeaderRef.get().getDepFileLines().get(), depFileLines);
      }
      inputs.addAll(preprocessDelegate.get().getInputsAfterBuildingLocally(depFileLines));
    }

    // If present, include all inputs coming from the compiler tool.
    if (operation.isCompile()) {
      inputs.addAll(compilerDelegate.getInputsAfterBuildingLocally());
    }

    // Add the input.
    inputs.add(input);

    return inputs.build();
  }

  private ImmutableList<String> readDepFileLines() throws IOException {
    return ImmutableList.copyOf(getProjectFilesystem().readLines(getDepFilePath()));
  }

}
