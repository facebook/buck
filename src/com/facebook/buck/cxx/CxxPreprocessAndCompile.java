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
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreIterables;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Map;

/**
 * A build rule which preprocesses and/or compiles a C/C++ source in a single step.
 */
public class CxxPreprocessAndCompile extends AbstractBuildRule implements RuleKeyAppendable {

  @AddToRuleKey
  private final Tool compiler;
  @AddToRuleKey
  private final CxxPreprocessAndCompileStep.Operation operation;
  private final ImmutableList<String> flags;
  @AddToRuleKey(stringify = true)
  private final Path output;
  @AddToRuleKey
  private final SourcePath input;
  private final CxxSource.Type inputType;
  private final ImmutableList<Path> includeRoots;
  private final ImmutableList<Path> systemIncludeRoots;
  private final ImmutableList<Path> frameworkRoots;
  @AddToRuleKey
  private final CxxHeaders includes;
  private final DebugPathSanitizer sanitizer;

  @VisibleForTesting
  CxxPreprocessAndCompile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Tool compiler,
      CxxPreprocessAndCompileStep.Operation operation,
      ImmutableList<String> flags,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      ImmutableList<Path> includeRoots,
      ImmutableList<Path> systemIncludeRoots,
      ImmutableList<Path> frameworkRoots,
      CxxHeaders includes,
      DebugPathSanitizer sanitizer) {
    super(params, resolver);
    this.compiler = compiler;
    this.operation = operation;
    this.flags = flags;
    this.output = output;
    this.input = input;
    this.inputType = inputType;
    this.includeRoots = includeRoots;
    this.systemIncludeRoots = systemIncludeRoots;
    this.frameworkRoots = frameworkRoots;
    this.includes = includes;
    this.sanitizer = sanitizer;
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} step that compiles the given preprocessed source.
   */
  public static CxxPreprocessAndCompile compile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Tool compiler,
      ImmutableList<String> flags,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer sanitizer) {
    return new CxxPreprocessAndCompile(
        params,
        resolver,
        compiler,
        CxxPreprocessAndCompileStep.Operation.COMPILE,
        flags,
        output,
        input,
        inputType,
        ImmutableList.<Path>of(),
        ImmutableList.<Path>of(),
        ImmutableList.<Path>of(),
        CxxHeaders.builder().build(),
        sanitizer);
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} step that preprocesses the given source.
   */
  public static CxxPreprocessAndCompile preprocess(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Tool compiler,
      ImmutableList<String> flags,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      ImmutableList<Path> includeRoots,
      ImmutableList<Path> systemIncludeRoots,
      ImmutableList<Path> frameworkRoots,
      CxxHeaders includes,
      DebugPathSanitizer sanitizer) {
    return new CxxPreprocessAndCompile(
        params,
        resolver,
        compiler,
        CxxPreprocessAndCompileStep.Operation.PREPROCESS,
        flags,
        output,
        input,
        inputType,
        includeRoots,
        systemIncludeRoots,
        frameworkRoots,
        includes,
        sanitizer);
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} step that preprocesses and compiles the given source.
   */
  public static CxxPreprocessAndCompile preprocessAndCompile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Tool compiler,
      ImmutableList<String> flags,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      ImmutableList<Path> includeRoots,
      ImmutableList<Path> systemIncludeRoots,
      ImmutableList<Path> frameworkRoots,
      CxxHeaders includes,
      DebugPathSanitizer sanitizer,
      CxxPreprocessMode strategy) {

    return new CxxPreprocessAndCompile(
        params,
        resolver,
        compiler,
        (strategy == CxxPreprocessMode.PIPED
            ? CxxPreprocessAndCompileStep.Operation.PIPED_PREPROCESS_AND_COMPILE
            : CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO),
        flags,
        output,
        input,
        inputType,
        includeRoots,
        systemIncludeRoots,
        frameworkRoots,
        includes,
        sanitizer);
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    // Sanitize any relevant paths in the flags we pass to the preprocessor, to prevent them
    // from contributing to the rule key.
    ImmutableList<String> flags = this.flags;
    flags = FluentIterable.from(flags)
        .transform(sanitizer.sanitize(Optional.<Path>absent(), /* expandPaths */ false))
        .toList();
    builder.setReflectively("flags", flags);
    ImmutableList<String> frameworkRoots = FluentIterable.from(this.frameworkRoots)
        .transform(Functions.toStringFunction())
        .transform(sanitizer.sanitize(Optional.<Path>absent(), /* expandPaths */ false))
        .toList();
    builder.setReflectively("frameworkRoots", frameworkRoots);

    // If a sanitizer is being used for compilation, we need to record the working directory in
    // the rule key, as changing this changes the generated object file.
    if (operation == CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO) {
      builder.setReflectively("compilationDirectory", sanitizer.getCompilationDirectory());
    }

    return builder;
  }

  private CxxPreprocessAndCompileStep makeMainStep(
      CxxPreprocessAndCompileStep.Operation operation) {

    // Resolve the map of symlinks to real paths to hand off the preprocess step.  If we're
    // compiling, this will just be empty.
    ImmutableMap.Builder<Path, Path> replacementPathsBuilder = ImmutableMap.builder();
    for (Map.Entry<Path, SourcePath> entry : includes.getFullNameToPathMap().entrySet()) {
      replacementPathsBuilder.put(entry.getKey(), getResolver().getPath(entry.getValue()));
    }
    ImmutableMap<Path, Path> replacementPaths = replacementPathsBuilder.build();

    return new CxxPreprocessAndCompileStep(
        operation,
        output,
        getResolver().getPath(input),
        inputType,
        compiler.getCommandPrefix(getResolver()),
        getCommandSuffix(),
        replacementPaths,
        sanitizer);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        new MkdirStep(output.getParent()),
        makeMainStep(operation));
  }

  private ImmutableList<String> getCommandSuffix() {
    return ImmutableList.<String>builder()
        .addAll(flags)
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-include"),
                FluentIterable.from(includes.getPrefixHeaders())
                    .transform(getResolver().getPathFunction())
                    .transform(Functions.toStringFunction())))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-I"),
                Iterables.transform(includeRoots, Functions.toStringFunction())))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-isystem"),
                Iterables.transform(systemIncludeRoots, Functions.toStringFunction())))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-F"),
                Iterables.transform(frameworkRoots, Functions.toStringFunction())))
        .build();
  }

  public ImmutableList<String> getCompileCommandCombinedWithPreprocessBuildRule(
      CxxPreprocessAndCompile preprocessBuildRule) {
    if (operation != CxxPreprocessAndCompileStep.Operation.COMPILE ||
        preprocessBuildRule.operation != CxxPreprocessAndCompileStep.Operation.PREPROCESS) {
      throw new HumanReadableException("%s is not preprocess rule or %s is not compile rule.",
          preprocessBuildRule, this);
    }
    ImmutableList.Builder<String> cmd = ImmutableList.builder();
    cmd.addAll(compiler.getCommandPrefix(getResolver()));
    cmd.add("-x", preprocessBuildRule.inputType.getLanguage());
    cmd.add("-c");
    cmd.addAll(preprocessBuildRule.getCommandSuffix());
    cmd.addAll(getCommandSuffix());
    cmd.add("-o", output.toString());
    cmd.add(getResolver().getPath(preprocessBuildRule.input).toString());
    return cmd.build();
  }

  public ImmutableList<String> getCommand() {
    // CxxPreprocessMode.PIPED involves more than one command,
    // so just pretend that we're COMBINED if someone (like the compilation
    // database) wants a single command line.
    CxxPreprocessAndCompileStep.Operation operation = this.operation;
    if (operation == CxxPreprocessAndCompileStep.Operation.PIPED_PREPROCESS_AND_COMPILE) {
      operation = CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO;
    }

    return makeMainStep(operation).getCommand();
  }

  public String getMainCommandDescription() {
    return makeMainStep(operation).getDescriptionNoContext();
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  public Tool getCompiler() {
    return compiler;
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

  public CxxHeaders getIncludes() {
    return includes;
  }

}
