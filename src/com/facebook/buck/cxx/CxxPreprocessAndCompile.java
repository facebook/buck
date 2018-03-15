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

import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CacheableBuildRule;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.args.Arg;
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
import java.util.SortedSet;
import java.util.function.Predicate;

/** A build rule which preprocesses and/or compiles a C/C++ source in a single step. */
public class CxxPreprocessAndCompile extends AbstractBuildRule
    implements SupportsInputBasedRuleKey, SupportsDependencyFileRuleKey, CacheableBuildRule {

  private final ImmutableSortedSet<BuildRule> buildDeps;

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
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSortedSet<BuildRule> buildDeps,
      Optional<PreprocessorDelegate> preprocessDelegate,
      CompilerDelegate compilerDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      Optional<CxxPrecompiledHeader> precompiledHeaderRule,
      DebugPathSanitizer sanitizer,
      Optional<SymlinkTree> sandboxTree) {
    super(buildTarget, projectFilesystem);
    this.buildDeps = buildDeps;
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
        !buildTarget.getFlavors().contains(CxxStrip.RULE_FLAVOR)
            || !StripStyle.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "CxxPreprocessAndCompile should not be created with CxxStrip flavors");
    Preconditions.checkArgument(
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "CxxPreprocessAndCompile %s should not be created with LinkerMapMode flavor (%s)",
        this,
        LinkerMapMode.FLAVOR_DOMAIN);
  }

  /** @return a {@link CxxPreprocessAndCompile} step that compiles the given preprocessed source. */
  public static CxxPreprocessAndCompile compile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSortedSet<BuildRule> buildDeps,
      CompilerDelegate compilerDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer sanitizer,
      Optional<SymlinkTree> sandboxTree) {
    return new CxxPreprocessAndCompile(
        buildTarget,
        projectFilesystem,
        buildDeps,
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
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSortedSet<BuildRule> buildDeps,
      PreprocessorDelegate preprocessorDelegate,
      CompilerDelegate compilerDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      Optional<CxxPrecompiledHeader> precompiledHeaderRule,
      DebugPathSanitizer sanitizer,
      Optional<SymlinkTree> sandboxTree) {
    return new CxxPreprocessAndCompile(
        buildTarget,
        projectFilesystem,
        buildDeps,
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
        sink.setReflectively("sandbox(" + path + ")", source);
      }
    }
    precompiledHeaderRule.ifPresent(
        cxxPrecompiledHeader ->
            sink.setReflectively("precompiledHeaderRuleInput", cxxPrecompiledHeader.getInput()));
  }

  private Path getDepFilePath() {
    return output.getFileSystem().getPath(output + ".dep");
  }

  @VisibleForTesting
  CxxPreprocessAndCompileStep makeMainStep(
      SourcePathResolver resolver, Path scratchDir, boolean useArgfile) {

    // If we're compiling, this will just be empty.
    HeaderPathNormalizer headerPathNormalizer =
        preprocessDelegate
            .map(x -> x.getHeaderPathNormalizer(resolver))
            .orElseGet(() -> HeaderPathNormalizer.empty(resolver));

    ImmutableList<Arg> arguments =
        compilerDelegate.getArguments(
            preprocessDelegate
                .map(delegate -> delegate.getFlagsWithSearchPaths(precompiledHeaderRule, resolver))
                .orElseGet(CxxToolFlags::of),
            getBuildTarget().getCellPath());

    Path relativeInputPath = getRelativeInputPath(resolver);

    return new CxxPreprocessAndCompileStep(
        getProjectFilesystem(),
        preprocessDelegate.isPresent()
            ? CxxPreprocessAndCompileStep.Operation.PREPROCESS_AND_COMPILE
            : CxxPreprocessAndCompileStep.Operation.COMPILE,
        output,
        // Use a depfile if there's a preprocessing stage, this logic should be kept in sync with
        // getInputsAfterBuildingLocally.
        preprocessDelegate.isPresent() ? Optional.of(getDepFilePath()) : Optional.empty(),
        relativeInputPath,
        inputType,
        new CxxPreprocessAndCompileStep.ToolCommand(
            compilerDelegate.getCommandPrefix(resolver),
            Arg.stringify(arguments, resolver),
            compilerDelegate.getEnvironment(resolver)),
        headerPathNormalizer,
        sanitizer,
        scratchDir,
        useArgfile,
        compilerDelegate.getCompiler(),
        Optional.of(
            CxxLogInfo.builder()
                .setTarget(getBuildTarget())
                .setSourcePath(relativeInputPath)
                .setOutputPath(output)
                .build()));
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
    preprocessDelegate.ifPresent(
        delegate -> {
          try {
            CxxHeaders.checkConflictingHeaders(delegate.getCxxIncludePaths().getIPaths());
          } catch (CxxHeaders.ConflictingHeadersException e) {
            throw e.getHumanReadableExceptionForBuildTarget(getBuildTarget());
          }
        });

    buildableContext.recordArtifact(output);

    for (String flag :
        Arg.stringify(
            compilerDelegate.getCompilerFlags().getAllFlags(), context.getSourcePathResolver())) {
      if (flag.equals("-ftest-coverage") && hasGcno(output)) {
        buildableContext.recordArtifact(getGcnoPath(output));
        break;
      }
    }

    return new ImmutableList.Builder<Step>()
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())))
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), getScratchPath())))
        .add(
            makeMainStep(
                context.getSourcePathResolver(),
                getScratchPath(),
                compilerDelegate.isArgFileSupported()))
        .build();
  }

  private static boolean hasGcno(Path output) {
    return !MorePaths.getNameWithoutExtension(output).endsWith(".S");
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
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  public SourcePath getInput() {
    return input;
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return true;
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate(SourcePathResolver pathResolver) {
    if (preprocessDelegate.isPresent()) {
      return preprocessDelegate.get().getCoveredByDepFilePredicate();
    }
    return (SourcePath path) -> true;
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate(SourcePathResolver pathResolver) {
    return (SourcePath path) -> false;
  }

  // see com.facebook.buck.cxx.AbstractCxxSourceRuleFactory.getSandboxedCxxSource()
  private SourcePath getOriginalInput(SourcePathResolver sourcePathResolver) {
    // The current logic of handling depfiles for cxx requires that all headers files and source
    // files are "deciphered' from links from symlink tree to original locations.
    // It already happens in Depfiles.parseAndOutputBuckCompatibleDepfile via header normalizer.
    // This special case is for applying the same logic for an input cxx file in the case
    // when cxx.sandbox_sources=true.
    if (preprocessDelegate.isPresent()) {
      Path absPath = sourcePathResolver.getAbsolutePath(input);
      HeaderPathNormalizer headerPathNormalizer =
          preprocessDelegate.get().getHeaderPathNormalizer(sourcePathResolver);
      Optional<Path> original = headerPathNormalizer.getAbsolutePathForUnnormalizedPath(absPath);
      if (original.isPresent()) {
        return headerPathNormalizer.getSourcePathForAbsolutePath(original.get());
      }
    }
    return input;
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver) throws IOException {
    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();

    // If present, include all inputs coming from the preprocessor tool.
    if (preprocessDelegate.isPresent()) {
      Iterable<Path> dependencies;
      try {
        dependencies =
            Depfiles.parseAndVerifyDependencies(
                context.getEventBus(),
                getProjectFilesystem(),
                preprocessDelegate.get().getHeaderPathNormalizer(context.getSourcePathResolver()),
                preprocessDelegate.get().getHeaderVerification(),
                getDepFilePath(),
                getRelativeInputPath(context.getSourcePathResolver()),
                output,
                compilerDelegate.getDependencyTrackingMode());
      } catch (Depfiles.HeaderVerificationException e) {
        throw new HumanReadableException(e);
      }

      inputs.addAll(
          preprocessDelegate
              .get()
              .getInputsAfterBuildingLocally(dependencies, context.getSourcePathResolver()));
    }

    // If present, include all inputs coming from the compiler tool.
    inputs.addAll(compilerDelegate.getInputsAfterBuildingLocally());

    if (precompiledHeaderRule.isPresent()) {
      CxxPrecompiledHeader pch = precompiledHeaderRule.get();
      inputs.addAll(pch.getInputsAfterBuildingLocally(context, cellPathResolver));
    }

    // Add the input.
    inputs.add(getOriginalInput(context.getSourcePathResolver()));

    return inputs.build();
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDeps;
  }
}
