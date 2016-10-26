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

import static com.facebook.buck.swift.SwiftUtil.Constants.SWIFT_MAIN_FILENAME;
import static com.facebook.buck.swift.SwiftUtil.normalizeSwiftModuleName;
import static com.facebook.buck.swift.SwiftUtil.toSwiftHeaderName;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxFlavorSanitizer;
import com.facebook.buck.cxx.CxxHeaders;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
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
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;

/**
 * A build rule which compiles one or more Swift sources into a Swift module.
 */
class SwiftCompile
    extends AbstractBuildRule {

  private static final String INCLUDE_FLAG = "-I";
  private static final String COMPILE_FLAVOR_PREFIX = "compile-swift-";
  private static final Flavor PREPARE_FLAVOR = ImmutableFlavor.of("prepare-compile");
  private static final java.util.function.Predicate<SwiftCompile> IS_COMPILE_INDIVIDUAL =
      input -> input.getBuildTarget().getFlavors()
          .stream()
          .anyMatch(flavor -> flavor.getName().startsWith(COMPILE_FLAVOR_PREFIX));

  // Prepend "-I" before the input with no space (this is required by swift).
  private static final Function<String, String> PREPEND_INCLUDE_FLAG = INCLUDE_FLAG::concat;

  @AddToRuleKey
  private final Tool swiftCompiler;

  @AddToRuleKey
  private final String moduleName;

  @AddToRuleKey(stringify = true)
  private final Path outputPath;

  private final Path modulePath;
  private final Path objectPath;

  @AddToRuleKey
  private final Optional<SourcePath> src;

  @AddToRuleKey
  private final Optional<SourcePath> bridgingHeader;

  private final Path headerPath;
  private final CxxPlatform cxxPlatform;
  private final ImmutableSet<FrameworkPath> frameworks;

  private final boolean hasMainEntry;
  private final boolean enableObjcInterop;
  private final SwiftBuckConfig swiftBuckConfig;
  private final Optional<Path> fileListPath;
  private final Iterable<CxxPreprocessorInput> cxxPreprocessorInputs;

  private SwiftCompile(
      CxxPlatform cxxPlatform,
      SwiftBuckConfig swiftBuckConfig,
      BuildRuleParams params,
      SourcePathResolver sourcePathResolver,
      Tool swiftCompiler,
      ImmutableSet<FrameworkPath> frameworks,
      String moduleName,
      Optional<SourcePath> src,
      Optional<Boolean> enableObjcInterop,
      Optional<SourcePath> bridgingHeader,
      Optional<Path> fileListPath) throws NoSuchBuildTargetException {
    super(params, sourcePathResolver);
    this.cxxPlatform = cxxPlatform;
    this.frameworks = frameworks;
    this.swiftBuckConfig = swiftBuckConfig;
    this.fileListPath = fileListPath;
    this.cxxPreprocessorInputs =
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(cxxPlatform, params.getDeps());
    this.swiftCompiler = swiftCompiler;
    this.outputPath = BuildTargets.getGenPath(
        params.getProjectFilesystem(), params.getBuildTarget(), "%s");

    String partialModuleName = src.isPresent() ?
        MorePaths.getNameWithoutExtension(sourcePathResolver.getAbsolutePath(src.get())) :
        moduleName;
    this.headerPath = this.outputPath.resolve(toSwiftHeaderName(partialModuleName) + ".h");

    String escapedModuleName = normalizeSwiftModuleName(partialModuleName);
    this.moduleName = normalizeSwiftModuleName(moduleName);
    this.modulePath = this.outputPath.resolve(escapedModuleName + ".swiftmodule");
    this.objectPath = this.outputPath.resolve(escapedModuleName + ".o");

    this.src = src;
    this.enableObjcInterop = enableObjcInterop.orElse(true);
    this.bridgingHeader = bridgingHeader;
    this.hasMainEntry = src.isPresent() && SWIFT_MAIN_FILENAME.equalsIgnoreCase(
        getResolver().getAbsolutePath(src.get()).getFileName().toString());
  }

  private SwiftCompileStep makeCompileStep() {
    ImmutableList.Builder<String> compilerCommand = ImmutableList.builder();
    compilerCommand.addAll(swiftCompiler.getCommandPrefix(getResolver()));

    String[] moduleLists = getDeps().stream()
        .filter(SwiftCompile.class::isInstance)
        .map(SwiftCompile.class::cast)
        .filter(IS_COMPILE_INDIVIDUAL)
        .map(input -> input.modulePath.toString())
        .toArray(String[]::new);

    compilerCommand.add(
        "-enable-testing",
        "-c",
        enableObjcInterop ? "-enable-objc-interop" : "",
        hasMainEntry ? "" : "-parse-as-library",
        "-module-name",
        moduleName,
        "-emit-objc-header-path",
        headerPath.toString());

    compilerCommand.add("-emit-module");
    compilerCommand.add(moduleLists);

    if (fileListPath.isPresent()) {
      compilerCommand.add("-filelist", fileListPath.get().toString());
    }

    final Function<FrameworkPath, Path> frameworkPathToSearchPath =
        CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, getResolver());

    compilerCommand.addAll(
        frameworks.stream()
            .map(frameworkPathToSearchPath::apply)
            .flatMap(searchPath -> ImmutableSet.of("-F", searchPath.toString()).stream())
            .iterator());

    compilerCommand.addAll(
        MoreIterables.zipAndConcat(
            Iterables.cycle("-Xcc"),
            getSwiftIncludeArgs()));

    compilerCommand.addAll(MoreIterables.zipAndConcat(
        Iterables.cycle(INCLUDE_FLAG),
        FluentIterable.from(getDeps())
            .filter(SwiftCompile.class)
            .transform(SourcePaths.getToBuildTargetSourcePath())
            .transform(input -> getResolver().getAbsolutePath(input).toString())
            .toSet()));

    Optional<Iterable<String>> configFlags = swiftBuckConfig.getFlags();
    if (configFlags.isPresent()) {
      compilerCommand.addAll(configFlags.get());
    }

    if (bridgingHeader.isPresent()) {
      compilerCommand.add(
          "-import-objc-header",
          getResolver().getRelativePath(bridgingHeader.get()).toString());

      // bridging header needs exported headers for imports
      for (HeaderVisibility headerVisibility : HeaderVisibility.values()) {
        Path headerPath = CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
            getProjectFilesystem(),
            BuildTarget.builder(getBuildTarget().getUnflavoredBuildTarget()).build(),
            cxxPlatform.getFlavor(),
            headerVisibility);

        compilerCommand.add(INCLUDE_FLAG, headerPath.toString());
      }
    }

    if (src.isPresent()) {
      compilerCommand.add(
          "-primary-file",
          getResolver().getAbsolutePath(src.get()).toString(),
          "-emit-module-path",
          modulePath.toString(),
          "-o",
          objectPath.toString());
    } else {
      compilerCommand.add(
          "-o",
          modulePath.toString());
    }

    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    return new SwiftCompileStep(
        projectFilesystem.getRootPath(),
        ImmutableMap.of(),
        compilerCommand.build());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(outputPath);
    ImmutableList.Builder<Step> builder = ImmutableList.<Step>builder()
        .add(new MkdirStep(getProjectFilesystem(), outputPath))
        .add(makeCompileStep());
    return builder.build();
  }

  @Override
  public Path getPathToOutput() {
    return outputPath;
  }

  /**
   * @return the arguments to add to the preprocessor command line to include the given header packs
   * in preprocessor search path.
   * <p>
   * We can't use CxxHeaders.getArgs() because
   * 1. we don't need the system include roots.
   * 2. swift doesn't like spaces after the "-I" flag.
   */
  @VisibleForTesting
  ImmutableList<String> getSwiftIncludeArgs() {
    SourcePathResolver resolver = getResolver();
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

    // Apply the header maps first, so that headers that matching there avoid falling back to
    // stat'ing files in the normal include roots.
    args.addAll(Iterables.transform(headerMaps, INCLUDE_FLAG::concat));

    // Apply the regular includes last.
    args.addAll(Iterables.transform(roots, INCLUDE_FLAG::concat));

    return args.build();
  }

  ImmutableSet<Arg> getAstLinkArgs() {
    return ImmutableSet.<Arg>builder()
        .addAll(StringArg.from("-Xlinker", "-add_ast_path"))
        .add(new SourcePathArg(
            getResolver(),
            new BuildTargetSourcePath(getBuildTarget(), modulePath)))
        .build();
  }

  ImmutableList<Arg> getFileListLinkArgs() {
    return FileListableLinkerInputArg.from(
        SourcePathArg.from(getResolver(), getObjectLists()).stream()
            .filter(SourcePathArg.class::isInstance)
            .map(SourcePathArg.class::cast)
            .collect(MoreCollectors.toImmutableList()));
  }

  private ImmutableList<SourcePath> getObjectLists() {
    return getDeclaredDeps().stream()
        .filter(SwiftCompile.class::isInstance)
        .map(SwiftCompile.class::cast)
        .filter(IS_COMPILE_INDIVIDUAL)
        .map(input -> new BuildTargetSourcePath(getBuildTarget(), input.objectPath))
        .collect(MoreCollectors.toImmutableList());
  }

  public static BuildRule of(
      CxxPlatform cxxPlatform,
      SwiftBuckConfig swiftBuckConfig,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver sourcePathResolver,
      Tool swiftCompiler,
      ImmutableSortedSet<FrameworkPath> frameworkPaths,
      String moduleName,
      ImmutableSet<SourcePath> srcs,
      Optional<Boolean> enableObjcInterop,
      Optional<SourcePath> bridgingHeader) throws NoSuchBuildTargetException {

    if (srcs.size() > 1) {
      BuildRuleParams prepareForCompileParams = params.withFlavor(PREPARE_FLAVOR);
      SwiftPrepareForCompile prepareForCompile = new SwiftPrepareForCompile(
          prepareForCompileParams,
          sourcePathResolver,
          srcs);
      params = params.appendExtraDeps(resolver.addToIndex(prepareForCompile));

      ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();
      deps.addAll(params.getDeclaredDeps().get());
      // create swift compile for every single file
      for (SourcePath src : srcs) {
        String outputName = CxxFlavorSanitizer.sanitize(
            sourcePathResolver.getAbsolutePath(src).getFileName() + ".o");
        BuildRuleParams newParams = params.withFlavor(
            ImmutableFlavor.of(
                COMPILE_FLAVOR_PREFIX + outputName));
        BuildRule compileSingleFile =
            new SwiftCompile(
                cxxPlatform,
                swiftBuckConfig,
                newParams,
                sourcePathResolver,
                swiftCompiler,
                frameworkPaths,
                moduleName,
                Optional.of(src),
                enableObjcInterop,
                bridgingHeader,
                Optional.ofNullable(prepareForCompile.getPathToOutput()));
        resolver.addToIndex(compileSingleFile);
        deps.add(compileSingleFile);
      }

      // for merging all single file build outputs
      return new SwiftCompile(
          cxxPlatform,
          swiftBuckConfig,
          params.copyWithDeps(
              Suppliers.ofInstance(deps.build()),
              params.getExtraDeps()),
          sourcePathResolver,
          swiftCompiler,
          frameworkPaths,
          moduleName,
          Optional.empty(),
          enableObjcInterop,
          bridgingHeader,
          Optional.empty());
    } else {
      return new SwiftCompile(
          cxxPlatform,
          swiftBuckConfig,
          params,
          sourcePathResolver,
          swiftCompiler,
          frameworkPaths,
          moduleName,
          Optional.of(srcs.iterator().next()),
          enableObjcInterop,
          bridgingHeader,
          Optional.empty());
    }
  }

}
