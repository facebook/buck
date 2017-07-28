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

package com.facebook.buck.swift;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.cxx.PathShortener;
import com.facebook.buck.cxx.PreprocessorFlags;
import com.facebook.buck.cxx.platform.CxxPlatform;
import com.facebook.buck.cxx.platform.Preprocessor;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreIterables;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;

/** A build rule which compiles one or more Swift sources into a Swift module. */
class SwiftCompile extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  private static final String INCLUDE_FLAG = "-I";

  @AddToRuleKey private final Tool swiftCompiler;

  @AddToRuleKey private final String moduleName;

  @AddToRuleKey(stringify = true)
  private final Path outputPath;

  private final Path modulePath;
  private final Path objectPath;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> srcs;

  @AddToRuleKey private final ImmutableList<? extends Arg> compilerFlags;

  private final Path headerPath;
  private final CxxPlatform cxxPlatform;
  private final ImmutableSet<FrameworkPath> frameworks;

  private final boolean enableObjcInterop;
  private final Optional<SourcePath> bridgingHeader;
  private final SwiftBuckConfig swiftBuckConfig;
  @AddToRuleKey private final Preprocessor cPreprocessor;

  private final PreprocessorFlags cxxDeps;

  SwiftCompile(
      CxxPlatform cxxPlatform,
      SwiftBuckConfig swiftBuckConfig,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Tool swiftCompiler,
      ImmutableSet<FrameworkPath> frameworks,
      String moduleName,
      Path outputPath,
      Iterable<SourcePath> srcs,
      ImmutableList<Arg> compilerFlags,
      Optional<Boolean> enableObjcInterop,
      Optional<SourcePath> bridgingHeader,
      Preprocessor preprocessor,
      PreprocessorFlags cxxDeps)
      throws NoSuchBuildTargetException {
    super(buildTarget, projectFilesystem, params);
    this.cxxPlatform = cxxPlatform;
    this.frameworks = frameworks;
    this.swiftBuckConfig = swiftBuckConfig;
    this.swiftCompiler = swiftCompiler;
    this.outputPath = outputPath;
    this.headerPath = outputPath.resolve(SwiftDescriptions.toSwiftHeaderName(moduleName) + ".h");

    String escapedModuleName = CxxDescriptionEnhancer.normalizeModuleName(moduleName);
    this.moduleName = escapedModuleName;
    this.modulePath = outputPath.resolve(escapedModuleName + ".swiftmodule");
    this.objectPath = outputPath.resolve(escapedModuleName + ".o");

    this.srcs = ImmutableSortedSet.copyOf(srcs);
    this.compilerFlags = compilerFlags;
    this.enableObjcInterop = enableObjcInterop.orElse(true);
    this.bridgingHeader = bridgingHeader;
    this.cPreprocessor = preprocessor;
    this.cxxDeps = cxxDeps;
    performChecks(buildTarget);
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    super.appendToRuleKey(sink);
    cxxDeps.appendToRuleKey(sink);
  }

  private void performChecks(BuildTarget buildTarget) {
    Preconditions.checkArgument(
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "SwiftCompile %s should not be created with LinkerMapMode flavor (%s)",
        this,
        LinkerMapMode.FLAVOR_DOMAIN);
    Preconditions.checkArgument(
        !buildTarget.getFlavors().contains(CxxDescriptionEnhancer.SHARED_FLAVOR));
  }

  private SwiftCompileStep makeCompileStep(SourcePathResolver resolver) {
    ImmutableList.Builder<String> compilerCommand = ImmutableList.builder();
    compilerCommand.addAll(swiftCompiler.getCommandPrefix(resolver));

    if (bridgingHeader.isPresent()) {
      compilerCommand.add(
          "-import-objc-header", resolver.getRelativePath(bridgingHeader.get()).toString());
    }

    final Function<FrameworkPath, Path> frameworkPathToSearchPath =
        CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, resolver);

    compilerCommand.addAll(
        frameworks
            .stream()
            .map(frameworkPathToSearchPath::apply)
            .flatMap(searchPath -> ImmutableSet.of("-F", searchPath.toString()).stream())
            .iterator());

    compilerCommand.addAll(
        MoreIterables.zipAndConcat(Iterables.cycle("-Xcc"), getSwiftIncludeArgs(resolver)));
    compilerCommand.addAll(
        MoreIterables.zipAndConcat(
            Iterables.cycle(INCLUDE_FLAG),
            getBuildDeps()
                .stream()
                .filter(SwiftCompile.class::isInstance)
                .map(BuildRule::getSourcePathToOutput)
                .map(input -> resolver.getAbsolutePath(input).toString())
                .collect(MoreCollectors.toImmutableSet())));

    Optional<Iterable<String>> configFlags = swiftBuckConfig.getFlags();
    if (configFlags.isPresent()) {
      compilerCommand.addAll(configFlags.get());
    }
    boolean hasMainEntry =
        srcs.stream()
            .map(input -> resolver.getAbsolutePath(input).getFileName().toString())
            .anyMatch(SwiftDescriptions.SWIFT_MAIN_FILENAME::equalsIgnoreCase);

    compilerCommand.add(
        "-c",
        enableObjcInterop ? "-enable-objc-interop" : "",
        hasMainEntry ? "" : "-parse-as-library",
        "-module-name",
        moduleName,
        "-emit-module",
        "-emit-module-path",
        modulePath.toString(),
        "-o",
        objectPath.toString(),
        "-emit-objc-header-path",
        headerPath.toString());
    compilerCommand.addAll(Arg.stringify(compilerFlags, resolver));
    for (SourcePath sourcePath : srcs) {
      compilerCommand.add(resolver.getRelativePath(sourcePath).toString());
    }

    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    return new SwiftCompileStep(
        projectFilesystem.getRootPath(), ImmutableMap.of(), compilerCommand.build());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(outputPath);
    return ImmutableList.of(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), outputPath)),
        makeCompileStep(context.getSourcePathResolver()));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), outputPath);
  }

  /**
   * @return the arguments to add to the preprocessor command line to include the given header packs
   *     in preprocessor search path.
   *     <p>We can't use CxxHeaders.getArgs() because 1. we don't need the system include roots. 2.
   *     swift doesn't like spaces after the "-I" flag.
   */
  @VisibleForTesting
  ImmutableList<String> getSwiftIncludeArgs(SourcePathResolver resolver) {
    ImmutableList.Builder<String> args = ImmutableList.builder();

    // Arg list can't simply be passed in since the current implementation of toToolFlags drops the
    // dependency information.
    Iterable<Arg> argsFromDeps =
        cxxDeps
            .toToolFlags(
                resolver,
                PathShortener.byRelativizingToWorkingDir(getProjectFilesystem().getRootPath()),
                CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, resolver),
                cPreprocessor,
                Optional.empty())
            .getAllFlags();
    args.addAll(Arg.stringify(argsFromDeps, resolver));

    if (bridgingHeader.isPresent()) {
      for (HeaderVisibility headerVisibility : HeaderVisibility.values()) {
        // We should probably pass in the correct symlink trees instead of guessing.
        Path headerPath =
            CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
                getProjectFilesystem(),
                getBuildTarget().withFlavors(),
                headerVisibility,
                cxxPlatform.getFlavor());
        args.add(INCLUDE_FLAG.concat(headerPath.toString()));
      }
    }

    return args.build();
  }

  ImmutableList<Arg> getAstLinkArgs() {
    return ImmutableList.<Arg>builder()
        .addAll(StringArg.from("-Xlinker", "-add_ast_path"))
        .add(SourcePathArg.of(new ExplicitBuildTargetSourcePath(getBuildTarget(), modulePath)))
        .build();
  }

  Arg getFileListLinkArg() {
    return FileListableLinkerInputArg.withSourcePathArg(
        SourcePathArg.of(new ExplicitBuildTargetSourcePath(getBuildTarget(), objectPath)));
  }
}
