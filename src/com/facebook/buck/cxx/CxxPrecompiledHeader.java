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
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Optional;
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
public class CxxPrecompiledHeader extends AbstractBuildRule
    implements SupportsDependencyFileRuleKey, SupportsInputBasedRuleKey {

  // Fields that are added to rule key as is.
  @AddToRuleKey private final PreprocessorDelegate preprocessorDelegate;
  @AddToRuleKey private final CompilerDelegate compilerDelegate;
  @AddToRuleKey private final SourcePath input;
  @AddToRuleKey private final CxxSource.Type inputType;

  // Fields that added to the rule key with some processing.
  private final CxxToolFlags compilerFlags;

  // Fields that are not added to the rule key.
  private final DebugPathSanitizer compilerSanitizer;
  private final Path output;

  /**
   * Cache the loading and processing of the depfile. This data can always be reloaded from disk, so
   * only cache it weakly.
   */
  private final Cache<BuildContext, ImmutableList<Path>> depFileCache =
      CacheBuilder.newBuilder().weakKeys().weakValues().build();

  public CxxPrecompiledHeader(
      BuildRuleParams buildRuleParams,
      Path output,
      PreprocessorDelegate preprocessorDelegate,
      CompilerDelegate compilerDelegate,
      CxxToolFlags compilerFlags,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer compilerSanitizer) {
    super(buildRuleParams);
    Preconditions.checkArgument(
        !inputType.isAssembly(), "Asm files do not use precompiled headers.");
    this.preprocessorDelegate = preprocessorDelegate;
    this.compilerDelegate = compilerDelegate;
    this.compilerFlags = compilerFlags;
    this.output = output;
    this.input = input;
    this.inputType = inputType;
    this.compilerSanitizer = compilerSanitizer;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("compilationDirectory", compilerSanitizer.getCompilationDirectory());
    sink.setReflectively(
        "compilerFlagsPlatform", compilerSanitizer.sanitizeFlags(compilerFlags.getPlatformFlags()));
    sink.setReflectively(
        "compilerFlagsRule", compilerSanitizer.sanitizeFlags(compilerFlags.getRuleFlags()));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    Path scratchDir =
        BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s_tmp");
    return new ImmutableList.Builder<Step>()
        .add(MkdirStep.of(getProjectFilesystem(), output.getParent()))
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), scratchDir))
        .add(makeMainStep(context.getSourcePathResolver(), scratchDir))
        .build();
  }

  public SourcePath getInput() {
    return input;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
  }

  private Path getSuffixedOutput(SourcePathResolver pathResolver, String suffix) {
    return Paths.get(pathResolver.getRelativePath(getSourcePathToOutput()).toString() + suffix);
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
  public Predicate<SourcePath> getCoveredByDepFilePredicate() {
    return preprocessorDelegate.getCoveredByDepFilePredicate();
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate() {
    return (SourcePath path) -> false;
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context)
      throws IOException {
    try {
      return ImmutableList.<SourcePath>builder()
          .addAll(preprocessorDelegate.getInputsAfterBuildingLocally(readDepFileLines(context)))
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

  public ImmutableList<Path> readDepFileLines(BuildContext context)
      throws IOException, Depfiles.HeaderVerificationException {
    try {
      return depFileCache.get(
          context,
          () ->
              Depfiles.parseAndOutputBuckCompatibleDepfile(
                  context.getEventBus(),
                  getProjectFilesystem(),
                  preprocessorDelegate.getHeaderPathNormalizer(),
                  preprocessorDelegate.getHeaderVerification(),
                  getDepFilePath(context.getSourcePathResolver()),
                  // TODO(10194465): This uses relative path so as to get relative paths in the dep file
                  context.getSourcePathResolver().getRelativePath(input),
                  output));
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
  CxxPreprocessAndCompileStep makeMainStep(SourcePathResolver resolver, Path scratchDir) {
    return new CxxPreprocessAndCompileStep(
        getBuildTarget(),
        getProjectFilesystem(),
        CxxPreprocessAndCompileStep.Operation.GENERATE_PCH,
        resolver.getRelativePath(getSourcePathToOutput()),
        Optional.of(getDepFilePath(resolver)),
        // TODO(10194465): This uses relative path so as to get relative paths in the dep file
        resolver.getRelativePath(input),
        inputType,
        new CxxPreprocessAndCompileStep.ToolCommand(
            preprocessorDelegate.getCommandPrefix(),
            ImmutableList.copyOf(
                CxxToolFlags.explicitBuilder()
                    .addAllRuleFlags(
                        getCxxIncludePaths()
                            .getFlags(resolver, preprocessorDelegate.getPreprocessor()))
                    .addAllRuleFlags(
                        preprocessorDelegate.getArguments(
                            compilerFlags, /* no pch */ Optional.empty()))
                    .build()
                    .getAllFlags()),
            preprocessorDelegate.getEnvironment()),
        preprocessorDelegate.getHeaderPathNormalizer(),
        compilerSanitizer,
        scratchDir,
        /* useArgFile*/ true,
        compilerDelegate.getCompiler());
  }

  /**
   * Helper method for dealing with compiler flags in a precompiled header build.
   *
   * <p>
   *
   * <p>Triage the given list of compiler flags, and divert {@code -I} flags' arguments to {@code
   * iDirsBuilder}, do similar for {@code -isystem} flags and {@code iSystemDirsBuilder}, and
   * finally output other non-include-path related stuff to {@code nonIncludeFlagsBuilder}.
   *
   * <p>
   *
   * <p>Note that while Buck doesn't tend to produce {@code -I} and {@code -isystem} flags without a
   * space between the flag and its argument, though historically compilers have accepted that.
   * We'll accept that here as well, inserting a break between the flag and its parameter.
   *
   * @param iDirsBuilder a builder which will receive a list of directories provided with the {@code
   *     -I} option (the flag itself will not be added to this builder)
   * @param iSystemDirsBuilder a builder which will receive a list of directories provided with the
   *     {@code -isystem} option (the flag itself will not be added to this builder)
   * @param nonIncludeFlagsBuilder builder that receives all the stuff not outputted to the above.
   */
  public static void separateIncludePathArgs(
      ImmutableList<String> flags,
      ImmutableList.Builder<String> iDirsBuilder,
      ImmutableList.Builder<String> iSystemDirsBuilder,
      ImmutableList.Builder<String> nonIncludeFlagsBuilder) {

    // TODO(steveo): unused?

    Iterator<String> it = flags.iterator();
    while (it.hasNext()) {
      String flag = it.next();
      if (flag.equals("-I") && it.hasNext()) {
        iDirsBuilder.add(it.next());
      } else if (flag.startsWith("-I")) {
        iDirsBuilder.add(flag.substring("-I".length()));
      } else if (flag.equals("-isystem") && it.hasNext()) {
        iSystemDirsBuilder.add(it.next());
      } else if (flag.startsWith("-isystem")) {
        iSystemDirsBuilder.add(flag.substring("-isystem".length()));
      } else {
        nonIncludeFlagsBuilder.add(flag);
      }
    }
  }
}
