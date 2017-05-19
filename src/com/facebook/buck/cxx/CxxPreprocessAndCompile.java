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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;

/** A build rule which preprocesses and/or compiles a C/C++ source in a single step. */
public class CxxPreprocessAndCompile extends AbstractBuildRule
    implements SupportsInputBasedRuleKey, SupportsDependencyFileRuleKey {

  /** The presence or absence of this field denotes whether the input needs to be preprocessed. */
  @AddToRuleKey private final Optional<PreprocessorDelegate> preprocessDelegate;

  @AddToRuleKey private final CompilerDelegate compilerDelegate;

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey private final SourcePath input;
  private final Optional<CxxPrecompiledHeader> precompiledHeaderRule;
  private final CxxSource.Type inputType;
  private final DebugPathSanitizer sanitizer;
  private final Optional<SymlinkTree> sandboxTree;

  private CxxPreprocessAndCompile(
      BuildRuleParams params,
      Optional<PreprocessorDelegate> preprocessDelegate,
      CompilerDelegate compilerDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      Optional<CxxPrecompiledHeader> precompiledHeaderRule,
      DebugPathSanitizer sanitizer,
      Optional<SymlinkTree> sandboxTree) {
    super(params);
    this.sandboxTree = sandboxTree;
    if (precompiledHeaderRule.isPresent()) {
      Preconditions.checkState(
          preprocessDelegate.isPresent(),
          "Precompiled headers are only used when compilation includes preprocessing.");
    }
    this.preprocessDelegate = preprocessDelegate;
    this.compilerDelegate = compilerDelegate;
    this.output = output;
    this.input = input;
    this.inputType = inputType;
    this.precompiledHeaderRule = precompiledHeaderRule;
    this.sanitizer = sanitizer;
    Preconditions.checkArgument(
        !params.getBuildTarget().getFlavors().contains(CxxStrip.RULE_FLAVOR)
            || !StripStyle.FLAVOR_DOMAIN.containsAnyOf(params.getBuildTarget().getFlavors()),
        "CxxPreprocessAndCompile should not be created with CxxStrip flavors");
    Preconditions.checkArgument(
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(params.getBuildTarget().getFlavors()),
        "CxxPreprocessAndCompile %s should not be created with LinkerMapMode flavor (%s)",
        this,
        LinkerMapMode.FLAVOR_DOMAIN);
  }

  /** @return a {@link CxxPreprocessAndCompile} step that compiles the given preprocessed source. */
  public static CxxPreprocessAndCompile compile(
      BuildRuleParams params,
      CompilerDelegate compilerDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer sanitizer,
      Optional<SymlinkTree> sandboxTree) {
    return new CxxPreprocessAndCompile(
        params,
        Optional.empty(),
        compilerDelegate,
        output,
        input,
        inputType,
        Optional.empty(),
        sanitizer,
        sandboxTree);
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} step that preprocesses and compiles the given source.
   */
  public static CxxPreprocessAndCompile preprocessAndCompile(
      BuildRuleParams params,
      PreprocessorDelegate preprocessorDelegate,
      CompilerDelegate compilerDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      Optional<CxxPrecompiledHeader> precompiledHeaderRule,
      DebugPathSanitizer sanitizer,
      Optional<SymlinkTree> sandboxTree) {
    return new CxxPreprocessAndCompile(
        params,
        Optional.of(preprocessorDelegate),
        compilerDelegate,
        output,
        input,
        inputType,
        precompiledHeaderRule,
        sanitizer,
        sandboxTree);
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    // If a sanitizer is being used for compilation, we need to record the working directory in
    // the rule key, as changing this changes the generated object file.
    if (preprocessDelegate.isPresent()) {
      sink.setReflectively("compilationDirectory", sanitizer.getCompilationDirectory());
    }
    if (sandboxTree.isPresent()) {
      ImmutableMap<Path, SourcePath> links = sandboxTree.get().getLinks();
      for (Path path : ImmutableSortedSet.copyOf(links.keySet())) {
        SourcePath source = links.get(path);
        sink.setReflectively("sandbox(" + path.toString() + ")", source);
      }
    }
    precompiledHeaderRule.ifPresent(
        cxxPrecompiledHeader ->
            sink.setReflectively("precompiledHeaderRuleInput", cxxPrecompiledHeader.getInput()));
  }

  private Path getDepFilePath() {
    return output.getFileSystem().getPath(output.toString() + ".dep");
  }

  @VisibleForTesting
  CxxPreprocessAndCompileStep makeMainStep(
      SourcePathResolver resolver, Path scratchDir, boolean useArgfile) {

    // If we're compiling, this will just be empty.
    HeaderPathNormalizer headerPathNormalizer =
        preprocessDelegate
            .map(PreprocessorDelegate::getHeaderPathNormalizer)
            .orElseGet(() -> HeaderPathNormalizer.empty(resolver));

    ImmutableList<String> arguments =
        compilerDelegate.getArguments(
            preprocessDelegate
                .map(delegate -> delegate.getFlagsWithSearchPaths(precompiledHeaderRule))
                .orElseGet(CxxToolFlags::of),
            getBuildTarget().getCellPath());

    return new CxxPreprocessAndCompileStep(
        getBuildTarget(),
        getProjectFilesystem(),
        preprocessDelegate.isPresent()
            ? CxxPreprocessAndCompileStep.Operation.PREPROCESS_AND_COMPILE
            : CxxPreprocessAndCompileStep.Operation.COMPILE,
        output,
        // Use a depfile if there's a preprocessing stage, this logic should be kept in sync with
        // getInputsAfterBuildingLocally.
        preprocessDelegate.isPresent() ? Optional.of(getDepFilePath()) : Optional.empty(),
        getRelativeInputPath(resolver),
        inputType,
        new CxxPreprocessAndCompileStep.ToolCommand(
            compilerDelegate.getCommandPrefix(), arguments, compilerDelegate.getEnvironment()),
        headerPathNormalizer,
        sanitizer,
        scratchDir,
        useArgfile,
        compilerDelegate.getCompiler());
  }

  public Path getRelativeInputPath(SourcePathResolver resolver) {
    // For caching purposes, the path passed to the compiler is relativized by the absolute path by
    // the current cell root, so that file references emitted by the compiler would not change if
    // the repo is checked out into different places on disk.
    return getProjectFilesystem().getRootPath().relativize(resolver.getAbsolutePath(input));
  }

  @Override
  public String getType() {
    return "cxx_preprocess_compile";
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);

    for (String flag : compilerDelegate.getCompilerFlags().getAllFlags()) {
      if (flag.equals("-ftest-coverage")) {
        buildableContext.recordArtifact(getGcnoPath(output));
        break;
      }
    }

    return new ImmutableList.Builder<Step>()
        .add(MkdirStep.of(getProjectFilesystem(), output.getParent()))
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), getScratchPath()))
        .add(
            makeMainStep(
                context.getSourcePathResolver(),
                getScratchPath(),
                compilerDelegate.isArgFileSupported()))
        .build();
  }

  @VisibleForTesting
  static Path getGcnoPath(Path output) {
    String basename = MorePaths.getNameWithoutExtension(output);
    return output.getParent().resolve(basename + ".gcno");
  }

  private Path getScratchPath() {
    return BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s-tmp");
  }

  @VisibleForTesting
  Optional<PreprocessorDelegate> getPreprocessorDelegate() {
    return preprocessDelegate;
  }

  // Used for compdb
  public ImmutableList<String> getCommand(SourcePathResolver pathResolver) {
    return makeMainStep(pathResolver, getScratchPath(), false).getCommand();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
  }

  public SourcePath getInput() {
    return input;
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return compilerDelegate.isDependencyFileSupported();
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate() {
    if (preprocessDelegate.isPresent()) {
      return preprocessDelegate.get().getCoveredByDepFilePredicate();
    }
    return (SourcePath path) -> true;
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate() {
    return (SourcePath path) -> false;
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context)
      throws IOException {
    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();

    // If present, include all inputs coming from the preprocessor tool.
    if (preprocessDelegate.isPresent()) {
      Iterable<Path> depFileLines;
      try {
        depFileLines =
            Depfiles.parseAndOutputBuckCompatibleDepfile(
                context.getEventBus(),
                getProjectFilesystem(),
                preprocessDelegate.get().getHeaderPathNormalizer(),
                preprocessDelegate.get().getHeaderVerification(),
                getDepFilePath(),
                getRelativeInputPath(context.getSourcePathResolver()),
                output);
      } catch (Depfiles.HeaderVerificationException e) {
        throw new HumanReadableException(e);
      }

      inputs.addAll(preprocessDelegate.get().getInputsAfterBuildingLocally(depFileLines));
    }

    // If present, include all inputs coming from the compiler tool.
    inputs.addAll(compilerDelegate.getInputsAfterBuildingLocally());

    if (precompiledHeaderRule.isPresent()) {
      CxxPrecompiledHeader pch = precompiledHeaderRule.get();
      inputs.addAll(pch.getInputsAfterBuildingLocally(context));
    }

    // Add the input.
    inputs.add(input);

    return inputs.build();
  }
}
