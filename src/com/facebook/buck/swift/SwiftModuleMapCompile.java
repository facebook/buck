/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.swift;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

/** A build rule to compile a Clang module from a given modulemap file */
public class SwiftModuleMapCompile extends ModernBuildRule<SwiftModuleMapCompile.Impl> {
  public SwiftModuleMapCompile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      String targetTriple,
      Tool swiftc,
      ImmutableList<Arg> swiftArgs,
      boolean withDownwardApi,
      String moduleName,
      boolean isSystemModule,
      ExplicitModuleInput moduleMapPath,
      ImmutableSet<ExplicitModuleOutput> clangModuleDeps) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new SwiftModuleMapCompile.Impl(
            targetTriple,
            swiftc,
            swiftArgs,
            withDownwardApi,
            moduleName,
            isSystemModule,
            moduleMapPath,
            clangModuleDeps));
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  /** Inner class to implement logic for .modulemap compilation. */
  static class Impl implements Buildable {
    @AddToRuleKey private final String targetTriple;
    @AddToRuleKey private final Tool swiftc;
    @AddToRuleKey private final ImmutableList<Arg> swiftArgs;
    @AddToRuleKey private final boolean withDownwardApi;
    @AddToRuleKey private final String moduleName;
    @AddToRuleKey private final ExplicitModuleInput modulemapPath;
    @AddToRuleKey private final boolean isSystemModule;
    @AddToRuleKey private final ImmutableSet<ExplicitModuleOutput> clangModuleDeps;
    @AddToRuleKey private final OutputPath output;

    Impl(
        String targetTriple,
        Tool swiftc,
        ImmutableList<Arg> swiftArgs,
        boolean withDownwardApi,
        String moduleName,
        boolean isSystemModule,
        ExplicitModuleInput modulemapPath,
        ImmutableSet<ExplicitModuleOutput> clangModuleDeps) {
      this.targetTriple = targetTriple;
      this.swiftc = swiftc;
      this.swiftArgs = swiftArgs;
      this.withDownwardApi = withDownwardApi;
      this.moduleName = moduleName;
      this.isSystemModule = isSystemModule;
      this.modulemapPath = modulemapPath;
      this.clangModuleDeps = clangModuleDeps;
      this.output = new OutputPath(moduleName + ".pcm");
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      SourcePathResolverAdapter resolver = buildContext.getSourcePathResolver();

      ImmutableList.Builder<String> argsBuilder = ImmutableList.builder();
      argsBuilder.addAll(Arg.stringify(swiftArgs, resolver));
      argsBuilder.add(
          "-emit-pcm",
          "-target",
          targetTriple,
          "-module-name",
          moduleName,
          "-o",
          outputPathResolver.resolvePath(output).getPath().toString(),
          // Embed all input files into the PCM so we don't need to include module map files when
          // building remotely.
          // https://github.com/apple/llvm-project/commit/fb1e7f7d1aca7bcfc341e9214bda8b554f5ae9b6
          "-Xcc",
          "-Xclang",
          "-Xcc",
          "-fmodules-embed-all-files");

      if (isSystemModule) {
        argsBuilder.add(
            "-Xcc",
            "-Xclang",
            "-Xcc",
            "-emit-module",
            "-Xcc",
            "-Xclang",
            "-Xcc",
            "-fsystem-module");
      }

      argsBuilder.add(modulemapPath.resolve(resolver));

      for (ExplicitModuleOutput dep : clangModuleDeps) {
        Preconditions.checkState(
            !dep.getIsSwiftmodule(),
            "Clang module compilation should not have a swiftmodule dependency");
        Path depPath = resolver.getIdeallyRelativePath(dep.getOutputPath());
        argsBuilder.add("-Xcc", "-fmodule-file=" + dep.getName() + "=" + depPath);
      }

      return ImmutableList.of(
          new SwiftCompileStep(
              filesystem.getRootPath(),
              ImmutableMap.of(),
              swiftc.getCommandPrefix(resolver),
              argsBuilder.build(),
              filesystem,
              Optional.empty(),
              withDownwardApi,
              false));
    }
  }
}
