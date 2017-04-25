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
import com.facebook.buck.cxx.CxxHeaders;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
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
import java.util.LinkedHashSet;
import java.util.Optional;

/** A build rule which compiles one or more Swift sources into a Swift module. */
class SwiftCompile extends AbstractBuildRule {

  private static final String INCLUDE_FLAG = "-I";

  @AddToRuleKey private final Tool swiftCompiler;

  @AddToRuleKey private final String moduleName;

  @AddToRuleKey(stringify = true)
  private final Path outputPath;

  private final Path modulePath;
  private final Path objectPath;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> srcs;

  @AddToRuleKey private final ImmutableList<String> compilerFlags;

  private final Path headerPath;
  private final CxxPlatform cxxPlatform;
  private final ImmutableSet<FrameworkPath> frameworks;

  private final boolean enableObjcInterop;
  private final Optional<SourcePath> bridgingHeader;
  private final SwiftBuckConfig swiftBuckConfig;

  private final Iterable<CxxPreprocessorInput> cxxPreprocessorInputs;

  SwiftCompile(
      CxxPlatform cxxPlatform,
      SwiftBuckConfig swiftBuckConfig,
      BuildRuleParams params,
      Tool swiftCompiler,
      ImmutableSet<FrameworkPath> frameworks,
      String moduleName,
      Path outputPath,
      Iterable<SourcePath> srcs,
      ImmutableList<String> compilerFlags,
      Optional<Boolean> enableObjcInterop,
      Optional<SourcePath> bridgingHeader)
      throws NoSuchBuildTargetException {
    super(params);
    this.cxxPlatform = cxxPlatform;
    this.frameworks = frameworks;
    this.swiftBuckConfig = swiftBuckConfig;
    this.cxxPreprocessorInputs =
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(cxxPlatform, params.getBuildDeps());
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
    performChecks(params);
  }

  private void performChecks(BuildRuleParams params) {
    Preconditions.checkArgument(
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(params.getBuildTarget().getFlavors()),
        "SwiftCompile %s should not be created with LinkerMapMode flavor (%s)",
        this,
        LinkerMapMode.FLAVOR_DOMAIN);
    Preconditions.checkArgument(
        !params.getBuildTarget().getFlavors().contains(CxxDescriptionEnhancer.SHARED_FLAVOR));
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
    compilerCommand.addAll(compilerFlags);
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
        MkdirStep.of(getProjectFilesystem(), outputPath),
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

    // Collect the header maps and roots into buckets organized by include type, so that we can:
    // 1) Apply the header maps first (so that they work properly).
    // 2) De-duplicate redundant include paths.
    LinkedHashSet<String> headerMaps = new LinkedHashSet<String>();
    LinkedHashSet<String> roots = new LinkedHashSet<String>();

    for (CxxPreprocessorInput cxxPreprocessorInput : cxxPreprocessorInputs) {
      Iterable<CxxHeaders> cxxHeaderses = cxxPreprocessorInput.getIncludes();
      for (CxxHeaders cxxHeaders : cxxHeaderses) {
        // Swift doesn't need to reference anything from system headers
        if (cxxHeaders.getIncludeType() == CxxPreprocessables.IncludeType.SYSTEM) {
          continue;
        }
        Optional<SourcePath> headerMap = cxxHeaders.getHeaderMap();
        if (headerMap.isPresent()) {
          headerMaps.add(resolver.getAbsolutePath(headerMap.get()).toString());
        }
        roots.add(resolver.getAbsolutePath(cxxHeaders.getIncludeRoot()).toString());
      }
    }

    if (bridgingHeader.isPresent()) {
      for (HeaderVisibility headerVisibility : HeaderVisibility.values()) {
        Path headerPath =
            CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
                getProjectFilesystem(),
                BuildTarget.builder(getBuildTarget().getUnflavoredBuildTarget()).build(),
                headerVisibility,
                cxxPlatform.getFlavor());

        headerMaps.add(headerPath.toString());
      }
    }

    // Apply the header maps first, so that headers that matching there avoid falling back to
    // stat'ing files in the normal include roots.
    args.addAll(Iterables.transform(headerMaps, INCLUDE_FLAG::concat));

    // Apply the regular includes last.
    args.addAll(Iterables.transform(roots, INCLUDE_FLAG::concat));

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
