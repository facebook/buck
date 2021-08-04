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

package com.facebook.buck.swift;

import com.facebook.buck.apple.common.AppleCompilerTargetTriple;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.PreprocessorFlags;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.AddsToRuleKeyFunction;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;

/** Writes a Swift compilation command to a file in the Clang compilation database format. */
public class SwiftCompilationDatabase extends SwiftCompileBase {

  @AddToRuleKey(stringify = true)
  protected final RelPath outputCommandPath;

  SwiftCompilationDatabase(
      SwiftBuckConfig swiftBuckConfig,
      BuildTarget buildTarget,
      AppleCompilerTargetTriple swiftTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      Tool swiftCompiler,
      ImmutableList<Arg> systemFrameworkSearchPaths,
      ImmutableSet<FrameworkPath> frameworks,
      AddsToRuleKeyFunction<FrameworkPath, Optional<Path>> frameworkPathToSearchPath,
      Flavor flavor,
      String moduleName,
      Path outputPath,
      Iterable<SourcePath> srcs,
      Optional<String> version,
      ImmutableList<Arg> compilerFlags,
      Optional<Boolean> enableObjcInterop,
      boolean enableCxxInterop,
      Optional<SourcePath> bridgingHeader,
      Preprocessor preprocessor,
      PreprocessorFlags cxxDeps,
      ImmutableBiMap<Path, String> debugPrefixMap,
      boolean importUnderlyingModule,
      boolean withDownwardApi,
      boolean hasPrefixSerializedDebugInfo,
      boolean addXCTestImportPaths,
      boolean useSwiftDriver,
      boolean serializeDebuggingOptions,
      AppleSdk appleSdk) {
    super(
        swiftBuckConfig,
        buildTarget,
        swiftTarget,
        projectFilesystem,
        graphBuilder,
        swiftCompiler,
        systemFrameworkSearchPaths,
        frameworks,
        frameworkPathToSearchPath,
        flavor,
        moduleName,
        outputPath,
        srcs,
        version,
        compilerFlags,
        enableObjcInterop,
        enableCxxInterop,
        bridgingHeader,
        preprocessor,
        cxxDeps,
        debugPrefixMap,
        importUnderlyingModule,
        withDownwardApi,
        hasPrefixSerializedDebugInfo,
        addXCTestImportPaths,
        useSwiftDriver,
        serializeDebuggingOptions,
        appleSdk);
    this.outputCommandPath = RelPath.of(this.outputPath.resolve("swift_compile_commands.json"));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(outputCommandPath.getPath());

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                outputCommandPath.getParent())));
    steps.add(makeCommandStep(context.getSourcePathResolver()));

    return steps.build();
  }

  private SwiftCompilationDatabaseStep makeCommandStep(SourcePathResolverAdapter resolver) {
    ImmutableList<String> commandPrefix = swiftCompiler.getCommandPrefix(resolver);
    ImmutableList<String> compilerArgs = constructCompilerArgs(resolver);

    ProjectFilesystem projectFilesystem = getProjectFilesystem();

    return new SwiftCompilationDatabaseStep(
        projectFilesystem.resolve(outputCommandPath),
        projectFilesystem.getRootPath(),
        commandPrefix,
        compilerArgs,
        projectFilesystem,
        resolver,
        srcs,
        withDownwardApi);
  }

  @Override
  public boolean isCacheable() {
    // The output contains absolute paths, so it's not cacheable.
    return false;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputCommandPath);
  }
}
