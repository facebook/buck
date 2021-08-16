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
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
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
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;

/** A build rule which compiles one or more Swift sources into a Swift module. */
public class SwiftCompile extends SwiftCompileBase {
  @AddToRuleKey private final boolean transformErrorsToAbsolutePaths;

  SwiftCompile(
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
      boolean enableCxxInterop,
      Optional<SourcePath> bridgingHeader,
      Preprocessor preprocessor,
      PreprocessorFlags cxxDeps,
      ImmutableBiMap<Path, String> debugPrefixMap,
      boolean importUnderlyingModule,
      boolean withDownwardApi,
      boolean hasPrefixSerializedDebugInfo,
      boolean addXCTestImportPaths,
      boolean serializeDebuggingOptions) {
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
        enableCxxInterop,
        bridgingHeader,
        preprocessor,
        cxxDeps,
        debugPrefixMap,
        importUnderlyingModule,
        withDownwardApi,
        hasPrefixSerializedDebugInfo,
        addXCTestImportPaths,
        serializeDebuggingOptions);

    transformErrorsToAbsolutePaths = swiftBuckConfig.getTransformErrorsToAbsolutePaths();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(outputPath);

    Builder<Step> steps = ImmutableList.builder();
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), outputPath)));
    steps.add(makeCompileStep(context.getSourcePathResolver()));

    return steps.build();
  }

  /** Creates the step that compiles the Swift code. */
  private SwiftCompileStep makeCompileStep(SourcePathResolverAdapter resolver) {
    ImmutableList<String> commandPrefix = swiftCompiler.getCommandPrefix(resolver);
    ImmutableList<String> compilerArgs = constructCompilerArgs(resolver);

    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    return new SwiftCompileStep(
        projectFilesystem.getRootPath(),
        ImmutableMap.of(),
        commandPrefix,
        compilerArgs,
        projectFilesystem,
        argfilePath,
        withDownwardApi,
        transformErrorsToAbsolutePaths);
  }

  @Override
  public boolean isCacheable() {
    // .swiftmodule artifacts are not cacheable because they can contain machine-specific
    // absolute paths due to:
    //
    // - Bridging Headers: All files included in a bridging header will be
    //     literally included in the .swiftmodule file. When the Swift compiler encounters
    //     `import Module`, it will include the headers from the .swiftmodule and those
    //     headers are referenced via an absolute path stored in the .swiftmodule. This
    //     means that Obj-C headers can be included multiple times if the machines which
    //     populated the cache and the machine which is building have placed the source
    //     repository at different paths (usually the case with CI and developer machines).
    //
    // - Debugging Options: In order for lldb to find and compile all the Obj-C modules, it
    //     needs to know the flags passed to the Clang importer and the paths where to find them.
    //     Those paths are passed as absolute. When "-prefix-serialized-debug-info" is passed
    //     the paths used in this debugging info have debug prefixes applied which allows the
    //     swiftmodule files to be relocateable. The inverse map has to be applied in LLDB for
    //     the debugger to be able to recreate the clang modules successfully.
    //
    // See https://github.com/bazelbuild/rules_swift/tree/c29e0a07b4253047f9b3021db2bb5349bb7cff1f
    return !bridgingHeader.isPresent()
        && !shouldEmitClangModuleBreadcrumbs
        && prefixSerializedDebugInfo
        && useDebugPrefixMap;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputPath);
  }
}
