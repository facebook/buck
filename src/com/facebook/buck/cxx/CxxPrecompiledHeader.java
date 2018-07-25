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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

/**
 * Rule to generate a precompiled header from an existing header.
 *
 * <p>Precompiled headers are only useful for compilation style where preprocessing and compiling
 * are both done in the same process. If a preprocessed output needs to be serialized and later read
 * back in, the entire rationale of using a precompiled header, to avoid parsing excess headers, is
 * obviated.
 *
 * <p>PCH files are not very portable, and so they are not cached.
 *
 * <ul>
 *   <li>The compiler verifies that header mtime identical to that recorded in the PCH file.
 *   <li>PCH file records absolute paths, limited support for "relocatable" pch exists in Clang, but
 *       it is not very flexible.
 * </ul>
 *
 * <p>While the problems are not impossible to overcome, PCH generation is fast enough that it isn't
 * a significant problem. The PCH file is only generated when a source file needs to be compiled,
 * anyway.
 *
 * <p>Additionally, since PCH files contain information like timestamps, absolute paths, and
 * (effectively) random unique IDs, they are not amenable to the InputBasedRuleKey optimization when
 * used to compile another file.
 */
class CxxPrecompiledHeader extends AbstractBuildRule
    implements SupportsDependencyFileRuleKey, SupportsInputBasedRuleKey {

  private final ImmutableSortedSet<BuildRule> buildDeps;

  // Fields that are added to rule key as is.
  @AddToRuleKey private final PreprocessorDelegate preprocessorDelegate;
  @AddToRuleKey private final CompilerDelegate compilerDelegate;
  @AddToRuleKey private final SourcePath input;
  @AddToRuleKey private final CxxSource.Type inputType;
  @AddToRuleKey private final boolean canPrecompileFlag;
  @AddToRuleKey private final CxxToolFlags compilerFlags;
  @AddToRuleKey private final DebugPathSanitizer compilerSanitizer;

  // Fields that are not added to the rule key.
  private final Path output;

  /**
   * Cache the loading and processing of the depfile. This data can always be reloaded from disk, so
   * only cache it weakly.
   */
  private final Cache<BuildContext, ImmutableList<Path>> depFileCache =
      CacheBuilder.newBuilder().weakKeys().weakValues().build();

  public CxxPrecompiledHeader(
      boolean canPrecompile,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSortedSet<BuildRule> buildDeps,
      Path output,
      PreprocessorDelegate preprocessorDelegate,
      CompilerDelegate compilerDelegate,
      CxxToolFlags compilerFlags,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer compilerSanitizer) {
    super(buildTarget, projectFilesystem);
    Preconditions.checkArgument(
        !inputType.isAssembly(), "Asm files do not use precompiled headers.");
    this.buildDeps = buildDeps;
    this.canPrecompileFlag = canPrecompile;
    this.preprocessorDelegate = preprocessorDelegate;
    this.compilerDelegate = compilerDelegate;
    this.compilerFlags = compilerFlags;
    this.output = output;
    this.input = input;
    this.inputType = inputType;
    this.compilerSanitizer = compilerSanitizer;
  }

  /** @return whether this should be precompiled, or treated as a regular uncompiled header. */
  public boolean canPrecompile() {
    return canPrecompileFlag;
  }

  /**
   * @return the path to the file suitable for inclusion on the command line. If this PCH is to be
   *     precompiled (see {@link #canPrecompile()}) this will correspond to {@link
   *     #getSourcePathToOutput()}, otherwise it'll be the input header file.
   */
  public Path getIncludeFilePath(SourcePathResolver pathResolver) {
    return pathResolver.getAbsolutePath(getIncludeFileSourcePath());
  }

  private SourcePath getIncludeFileSourcePath() {
    return canPrecompile() ? getSourcePathToOutput() : input;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    preprocessorDelegate
        .checkConflictingHeaders()
        .ifPresent(result -> result.throwHumanReadableExceptionWithContext(getBuildTarget()));

    Path scratchDir =
        BuildTargetPaths.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s_tmp");

    return new ImmutableList.Builder<Step>()
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())))
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), scratchDir)))
        .add(makeMainStep(context, scratchDir))
        .build();
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDeps;
  }

  public SourcePath getInput() {
    return input;
  }

  public Path getRelativeInputPath(SourcePathResolver resolver) {
    // TODO(mzlee): We should make a generic solution to address this
    return getProjectFilesystem().relativize(resolver.getAbsolutePath(input));
  }

  public Path getOutputPath() {
    return output;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  private Path getSuffixedOutput(SourcePathResolver pathResolver, String suffix) {
    return Paths.get(pathResolver.getRelativePath(getSourcePathToOutput()) + suffix);
  }

  public CxxIncludePaths getCxxIncludePaths() {
    return CxxIncludePaths.concat(
        RichStream.from(this.getBuildDeps())
            .filter(CxxPreprocessAndCompile.class)
            .map(CxxPreprocessAndCompile::getPreprocessorDelegate)
            .filter(Optional::isPresent)
            .map(ppDelegate -> ppDelegate.get().getCxxIncludePaths())
            .iterator());
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return true;
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate(SourcePathResolver pathResolver) {
    return preprocessorDelegate.getCoveredByDepFilePredicate();
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate(SourcePathResolver pathResolver) {
    return (SourcePath path) -> false;
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver) throws IOException {
    try {
      return ImmutableList.<SourcePath>builder()
          .addAll(
              preprocessorDelegate.getInputsAfterBuildingLocally(getDependencies(context), context))
          .add(input)
          .build();
    } catch (Depfiles.HeaderVerificationException e) {
      throw new HumanReadableException(e);
    }
  }

  @Override
  public boolean isCacheable() {
    return false;
  }

  private Path getDepFilePath(SourcePathResolver pathResolver) {
    return getSuffixedOutput(pathResolver, ".dep");
  }

  private ImmutableList<Path> getDependencies(BuildContext context)
      throws IOException, Depfiles.HeaderVerificationException {
    try {
      return depFileCache.get(
          context,
          () ->
              Depfiles.parseAndVerifyDependencies(
                  context.getEventBus(),
                  getProjectFilesystem(),
                  preprocessorDelegate.getHeaderPathNormalizer(context),
                  preprocessorDelegate.getHeaderVerification(),
                  getDepFilePath(context.getSourcePathResolver()),
                  // TODO(10194465): This uses relative path so as to get relative paths in the dep
                  // file
                  getRelativeInputPath(context.getSourcePathResolver()),
                  output,
                  compilerDelegate.getDependencyTrackingMode()));
    } catch (ExecutionException e) {
      // Unwrap and re-throw the loader's Exception.
      Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      Throwables.throwIfInstanceOf(e.getCause(), Depfiles.HeaderVerificationException.class);
      throw new IllegalStateException("Unexpected cause for ExecutionException: ", e);
    } catch (UncheckedExecutionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw e;
    }
  }

  @VisibleForTesting
  CxxPreprocessAndCompileStep makeMainStep(BuildContext context, Path scratchDir) {
    SourcePathResolver resolver = context.getSourcePathResolver();
    Path pchOutput =
        canPrecompile()
            ? resolver.getRelativePath(getSourcePathToOutput())
            : Platform.detect().getNullDevicePath();

    return new CxxPreprocessAndCompileStep(
        getProjectFilesystem(),
        CxxPreprocessAndCompileStep.Operation.GENERATE_PCH,
        pchOutput,
        Optional.of(getDepFilePath(resolver)),
        // TODO(10194465): This uses relative path so as to get relative paths in the dep file
        getRelativeInputPath(resolver),
        inputType,
        new CxxPreprocessAndCompileStep.ToolCommand(
            preprocessorDelegate.getCommandPrefix(resolver),
            Arg.stringify(
                ImmutableList.copyOf(
                    CxxToolFlags.explicitBuilder()
                        .addAllRuleFlags(
                            StringArg.from(
                                getCxxIncludePaths()
                                    .getFlags(resolver, preprocessorDelegate.getPreprocessor())))
                        .addAllRuleFlags(
                            preprocessorDelegate.getArguments(
                                compilerFlags, /* no pch */ Optional.empty(), resolver))
                        .build()
                        .getAllFlags()),
                resolver),
            preprocessorDelegate.getEnvironment(resolver)),
        preprocessorDelegate.getHeaderPathNormalizer(context),
        compilerSanitizer,
        scratchDir,
        /* useArgFile*/ true,
        compilerDelegate.getCompiler(),
        Optional.empty());
  }

  public PrecompiledHeaderData getData() {
    return PrecompiledHeaderData.of(
        new NonHashableSourcePathContainer(getIncludeFileSourcePath()),
        getInput(),
        canPrecompileFlag);
  }
}
