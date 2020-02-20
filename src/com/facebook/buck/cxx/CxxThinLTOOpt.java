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
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.CxxSource.Type;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

/** A build rule which runs the opt phase of an incremental ThinLTO build */
public class CxxThinLTOOpt extends ModernBuildRule<CxxThinLTOOpt.Impl>
    implements CxxIntermediateBuildProduct {
  private CxxThinLTOOpt(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      CompilerDelegate compilerDelegate,
      String outputName,
      SourcePath input,
      SourcePath thinIndicesRoot,
      Type inputType,
      DebugPathSanitizer sanitizer) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(
            buildTarget,
            compilerDelegate,
            outputName,
            input,
            thinIndicesRoot,
            inputType,
            sanitizer));
    Preconditions.checkArgument(
        !buildTarget.getFlavors().contains(CxxStrip.RULE_FLAVOR)
            || !StripStyle.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "CxxThinLTOOpt should not be created with CxxStrip flavors");
    Preconditions.checkArgument(
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "CxxThinLTOOpt %s should not be created with LinkerMapMode flavor (%s)",
        this,
        LinkerMapMode.FLAVOR_DOMAIN);
  }

  /** @return a {@link CxxThinLTOOpt} step that optimizes the given bitcode source. */
  public static CxxThinLTOOpt optimize(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      CompilerDelegate compilerDelegate,
      String outputName,
      SourcePath input,
      SourcePath thinIndicesRoot,
      Type inputType,
      DebugPathSanitizer sanitizer) {
    return new CxxThinLTOOpt(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        compilerDelegate,
        outputName,
        input,
        thinIndicesRoot,
        inputType,
        sanitizer);
  }

  CompilerDelegate getCompilerDelegate() {
    return getBuildable().compilerDelegate;
  }

  /** Returns the compilation command (used for compdb). */
  public ImmutableList<String> getCommand(BuildContext context) {
    return getBuildable()
        .makeMainStep(context, getProjectFilesystem(), getOutputPathResolver(), false)
        .getCommand();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  public SourcePath getInput() {
    return getBuildable().input;
  }

  /** Buildable implementation for CxxThinLTOOpt. */
  public static class Impl implements Buildable {
    @AddToRuleKey private final BuildTarget targetName;

    @AddToRuleKey private final CompilerDelegate compilerDelegate;
    @AddToRuleKey private final DebugPathSanitizer sanitizer;
    @AddToRuleKey private final OutputPath output;
    @AddToRuleKey private final SourcePath input;
    @AddToRuleKey private final SourcePath thinIndicesRoot;
    @AddToRuleKey private final CxxSource.Type inputType;

    public Impl(
        BuildTarget targetName,
        CompilerDelegate compilerDelegate,
        String outputName,
        SourcePath input,
        SourcePath thinIndicesRoot,
        Type inputType,
        DebugPathSanitizer sanitizer) {
      this.targetName = targetName;
      this.compilerDelegate = compilerDelegate;
      this.sanitizer = sanitizer;
      this.output = new OutputPath(outputName);
      this.input = input;
      this.thinIndicesRoot = thinIndicesRoot;
      this.inputType = inputType;
    }

    CxxPreprocessAndCompileStep makeMainStep(
        BuildContext context,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        boolean useArgfile) {
      SourcePathResolverAdapter resolver = context.getSourcePathResolver();

      ImmutableList<Arg> arguments =
          compilerDelegate.getArguments(CxxToolFlags.of(), filesystem.getRootPath().getPath());

      RelPath relativeInputPath = filesystem.relativize(resolver.getAbsolutePath(input));
      Path resolvedOutput = outputPathResolver.resolvePath(output);

      return new CxxPreprocessAndCompileStep(
          filesystem,
          CxxPreprocessAndCompileStep.Operation.COMPILE,
          resolvedOutput,
          Optional.empty(),
          relativeInputPath.getPath(),
          inputType,
          new CxxPreprocessAndCompileStep.ToolCommand(
              compilerDelegate.getCommandPrefix(resolver),
              Arg.stringify(arguments, resolver),
              compilerDelegate.getEnvironment(resolver)),
          context.getSourcePathResolver(),
          HeaderPathNormalizer.empty(),
          sanitizer,
          outputPathResolver.getTempPath(),
          useArgfile,
          compilerDelegate.getPreArgfileArgs(),
          compilerDelegate.getCompiler(),
          Optional.of(
              ImmutableCxxLogInfo.of(
                  Optional.ofNullable(targetName),
                  Optional.ofNullable(relativeInputPath.getPath()),
                  Optional.ofNullable(resolvedOutput))));
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      Path resolvedOutput = outputPathResolver.resolvePath(output);

      return new ImmutableList.Builder<Step>()
          .add(
              MkdirStep.of(
                  BuildCellRelativePath.fromCellRelativePath(
                      context.getBuildCellRootPath(), filesystem, resolvedOutput.getParent())))
          .add(
              makeMainStep(
                  context, filesystem, outputPathResolver, compilerDelegate.isArgFileSupported()))
          .build();
    }
  }
}
