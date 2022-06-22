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
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.swift.toolchain.ExplicitModuleInput;
import com.facebook.buck.swift.toolchain.ExplicitModuleOutput;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.UnmodifiableIterator;
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
      ImmutableSet<ExplicitModuleOutput> clangModuleDeps,
      ImmutableSet<SourcePath> headers) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new SwiftModuleMapCompile.Impl(
            buildTarget,
            targetTriple,
            swiftc,
            swiftArgs,
            withDownwardApi,
            moduleName,
            isSystemModule,
            moduleMapPath,
            clangModuleDeps,
            headers));
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  /** Inner class to implement logic for .modulemap compilation. */
  static class Impl implements Buildable {
    @AddToRuleKey private final BuildTarget buildTarget;
    @AddToRuleKey private final String targetTriple;
    @AddToRuleKey private final Tool swiftc;
    @AddToRuleKey private final ImmutableList<Arg> swiftArgs;
    @AddToRuleKey private final boolean withDownwardApi;
    @AddToRuleKey private final String moduleName;
    @AddToRuleKey private final ExplicitModuleInput modulemapPath;
    @AddToRuleKey private final boolean isSystemModule;
    @AddToRuleKey private final ImmutableSet<ExplicitModuleOutput> clangModuleDeps;
    @AddToRuleKey private final ImmutableSet<SourcePath> headers;
    @AddToRuleKey private final OutputPath output;

    Impl(
        BuildTarget buildTarget,
        String targetTriple,
        Tool swiftc,
        ImmutableList<Arg> swiftArgs,
        boolean withDownwardApi,
        String moduleName,
        boolean isSystemModule,
        ExplicitModuleInput modulemapPath,
        ImmutableSet<ExplicitModuleOutput> clangModuleDeps,
        ImmutableSet<SourcePath> headers) {
      this.buildTarget = buildTarget;
      this.targetTriple = targetTriple;
      this.swiftc = swiftc;
      this.swiftArgs = swiftArgs;
      this.withDownwardApi = withDownwardApi;
      this.moduleName = moduleName;
      this.isSystemModule = isSystemModule;
      this.modulemapPath = modulemapPath;
      this.clangModuleDeps = clangModuleDeps;
      this.headers = headers;
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
      UnmodifiableIterator<Arg> iterator = swiftArgs.iterator();
      while (iterator.hasNext()) {
        Arg arg = iterator.next();
        if (arg instanceof SourcePathArg) {
          // We need relative paths here otherwise the absolute paths will end up serialized in the
          // pcm files, which will cause size differences and fail module validation.
          ((SourcePathArg) arg)
              .appendToCommandLineRel(argsBuilder::add, buildTarget.getCell(), resolver, false);
        } else if (arg.equals(StringArg.of("-module-cache-path"))) {
          // Setting a module cache path with implicit modules disabled will output a compiler
          // warning about unused arguments, so skip setting this.
          iterator.next();
        } else {
          arg.appendToCommandLine(argsBuilder::add, resolver);
        }
      }

      argsBuilder.add(
          "-emit-pcm",
          "-target",
          targetTriple,
          "-module-name",
          moduleName,
          "-o",
          outputPathResolver.resolvePath(output).getPath().toString(),
          "-Xfrontend",
          "-disable-implicit-swift-modules",
          "-Xcc",
          "-fno-implicit-modules",
          "-Xcc",
          "-fno-implicit-module-maps",
          // Disable debug info in pcm files. This is required to avoid embedding absolute paths
          // and ending up with mismatched pcm file sizes.
          "-Xcc",
          "-Xclang",
          "-Xcc",
          "-fmodule-format=raw",
          // Embed all input files into the PCM so we don't need to include module map files when
          // building remotely.
          // https://github.com/apple/llvm-project/commit/fb1e7f7d1aca7bcfc341e9214bda8b554f5ae9b6
          "-Xcc",
          "-Xclang",
          "-Xcc",
          "-fmodules-embed-all-files",
          // Set the module file root as the cwd, this will make the pcm files match sizes when
          // built on different machines with different repo paths.
          "-Xcc",
          "-Xclang",
          "-Xcc",
          "-fmodule-file-home-is-cwd",
          // Unset the working directory to avoid serializing it as an absolute path.
          "-Xcc",
          "-working-directory=",
          // Once we have an empty working directory the compiler provided headers such as float.h
          // cannot be found, so add . to the header search paths.
          "-Xcc",
          "-I.",
          // We need to disable PCH validation as we cannot pass through the language options
          // correctly (eg -fapplication-extension).
          "-Xcc",
          "-Xclang",
          "-Xcc",
          "-fno-validate-pch");

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

      // If this input is a framework we need to search above the
      // current framework location, otherwise we include the
      // modulemap root.
      Path moduleMapPath = Path.of(modulemapPath.resolve(resolver));
      if (isFrameworkPath(moduleMapPath)) {
        argsBuilder.add(
            "-Xcc",
            "-F",
            "-Xcc",
            moduleMapPath.subpath(0, moduleMapPath.getNameCount() - 3).toString());
      } else {
        argsBuilder.add("-Xcc", "-I", "-Xcc", moduleMapPath.getParent().toString());
      }

      for (ExplicitModuleOutput dep : clangModuleDeps) {
        argsBuilder.addAll(dep.getClangArgs(resolver));
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

    private boolean isFrameworkPath(Path modulemapPath) {
      int nameCount = modulemapPath.getNameCount();
      if (nameCount < 4) {
        return false;
      }

      return modulemapPath.getName(nameCount - 2).toString().equals("Modules")
          && modulemapPath.getName(nameCount - 3).toString().endsWith(".framework");
    }
  }
}
