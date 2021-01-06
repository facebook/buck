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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.PreprocessorFlags;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.PathShortener;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.AddsToRuleKeyFunction;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.swift.toolchain.SwiftTargetTriple;
import com.facebook.buck.util.MoreIterables;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.SortedSet;

/** A build rule which compiles one or more Swift sources into a Swift module. */
public class SwiftCompile extends AbstractBuildRule implements SupportsInputBasedRuleKey {

  private static final String INCLUDE_FLAG = "-I";

  @AddToRuleKey private final Tool swiftCompiler;

  @AddToRuleKey private final String moduleName;

  @AddToRuleKey(stringify = true)
  private final Path outputPath;

  @AddToRuleKey(stringify = true)
  private final Path objectFilePath;
  @AddToRuleKey(stringify = true)
  private final Path modulePath;
  @AddToRuleKey(stringify = true)
  private final Path moduleObjectPath;
  @AddToRuleKey(stringify = true)
  private final ImmutableList<Path> objectPaths;
  private final Optional<AbsPath> swiftFileListPath;

  @AddToRuleKey private final boolean shouldEmitSwiftdocs;
  @AddToRuleKey private final boolean useModulewrap;
  @AddToRuleKey private final boolean compileForceCache;
  @AddToRuleKey(stringify = true)
  private final Path swiftdocPath;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey private final SwiftTargetTriple swiftTarget;
  @AddToRuleKey private final Optional<String> version;
  @AddToRuleKey private final ImmutableList<? extends Arg> compilerFlags;

  @AddToRuleKey(stringify = true)
  private final Path headerPath;
  @AddToRuleKey private final ImmutableSet<FrameworkPath> frameworks;
  @AddToRuleKey private final AddsToRuleKeyFunction<FrameworkPath, Path> frameworkPathToSearchPath;
  @AddToRuleKey(stringify = true)
  private final Flavor flavor;

  @AddToRuleKey private final boolean enableObjcInterop;
  @AddToRuleKey private final Optional<SourcePath> bridgingHeader;

  @AddToRuleKey private final Preprocessor cPreprocessor;

  @AddToRuleKey private final PreprocessorFlags cxxDeps;

  @AddToRuleKey private final boolean importUnderlyingModule;

  private BuildableSupport.DepsSupplier depsSupplier;

  SwiftCompile(
      SwiftBuckConfig swiftBuckConfig,
      BuildTarget buildTarget,
      SwiftTargetTriple swiftTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      Tool swiftCompiler,
      ImmutableSet<FrameworkPath> frameworks,
      AddsToRuleKeyFunction<FrameworkPath, Path> frameworkPathToSearchPath,
      Flavor flavor,
      String moduleName,
      Path outputPath,
      Iterable<SourcePath> srcs,
      Optional<String> version,
      ImmutableList<Arg> compilerFlags,
      Optional<Boolean> enableObjcInterop,
      Optional<SourcePath> bridgingHeader,
      Preprocessor preprocessor,
      PreprocessorFlags cxxDeps,
      boolean importUnderlyingModule) {
    super(buildTarget, projectFilesystem);
    this.frameworks = frameworks;
    this.frameworkPathToSearchPath = frameworkPathToSearchPath;
    this.flavor = flavor;
    this.swiftCompiler = swiftCompiler;
    this.outputPath = outputPath;
    this.importUnderlyingModule = importUnderlyingModule;
    this.headerPath = outputPath.resolve(SwiftDescriptions.toSwiftHeaderName(moduleName) + ".h");

    String escapedModuleName = CxxDescriptionEnhancer.normalizeModuleName(moduleName);
    this.moduleName = escapedModuleName;
    this.objectFilePath = outputPath.resolve(escapedModuleName + ".o");
    this.modulePath = outputPath.resolve(escapedModuleName + ".swiftmodule");
    this.moduleObjectPath = outputPath.resolve(escapedModuleName + ".swiftmodule.o");
    this.objectPaths =
        swiftBuckConfig.getUseModulewrap()
            ? ImmutableList.of(objectFilePath, moduleObjectPath)
            : ImmutableList.of(objectFilePath);
    this.swiftFileListPath =
        swiftBuckConfig.getUseFileList()
            ? Optional.of(
                getProjectFilesystem()
                    .getRootPath()
                    .resolve(
                        BuildTargetPaths.getScratchPath(
                            getProjectFilesystem(), getBuildTarget(), "%s__filelist.txt")))
            : Optional.empty();

    this.shouldEmitSwiftdocs = swiftBuckConfig.getEmitSwiftdocs();
    this.useModulewrap = swiftBuckConfig.getUseModulewrap();
    this.compileForceCache = swiftBuckConfig.getCompileForceCache();
    this.swiftdocPath = outputPath.resolve(escapedModuleName + ".swiftdoc");

    this.srcs = ImmutableSortedSet.copyOf(srcs);
    this.swiftTarget = swiftTarget;
    this.version = version;
    this.compilerFlags =
        new ImmutableList.Builder<Arg>()
            .addAll(StringArg.from(swiftBuckConfig.getCompilerFlags().orElse(ImmutableSet.of())))
            .addAll(compilerFlags)
            .build();
    this.enableObjcInterop = enableObjcInterop.orElse(true);
    this.bridgingHeader = bridgingHeader;
    this.cPreprocessor = preprocessor;
    this.cxxDeps = cxxDeps;
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, graphBuilder);
    performChecks(buildTarget);
  }

  private void performChecks(BuildTarget buildTarget) {
    Preconditions.checkArgument(
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors().getSet()),
        "SwiftCompile %s should not be created with LinkerMapMode flavor (%s)",
        this,
        LinkerMapMode.FLAVOR_DOMAIN);
    Preconditions.checkArgument(
        !buildTarget.getFlavors().contains(CxxDescriptionEnhancer.SHARED_FLAVOR));
  }

  private SwiftCompileStep makeCompileStep(SourcePathResolverAdapter resolver) {
    ImmutableList.Builder<String> compilerCommand = ImmutableList.builder();
    compilerCommand.addAll(swiftCompiler.getCommandPrefix(resolver));

    compilerCommand.add("-target", swiftTarget.getTriple());

    if (bridgingHeader.isPresent()) {
      compilerCommand.add(
          "-import-objc-header", resolver.getRelativePath(bridgingHeader.get()).toString());
    }
    if (importUnderlyingModule) {
      compilerCommand.add("-import-underlying-module");
    }

    compilerCommand.addAll(
        Streams.concat(frameworks.stream(), cxxDeps.getFrameworkPaths().stream())
            .filter(x -> !x.isSDKROOTFrameworkPath())
            .map(frameworkPathToSearchPath)
            .flatMap(searchPath -> ImmutableSet.of("-F", searchPath.toString()).stream())
            .iterator());

    compilerCommand.addAll(
        MoreIterables.zipAndConcat(Iterables.cycle("-Xcc"), getSwiftIncludeArgs(resolver)));
    compilerCommand.addAll(
        MoreIterables.zipAndConcat(
            Iterables.cycle(INCLUDE_FLAG),
            getBuildDeps().stream()
                .filter(SwiftCompile.class::isInstance)
                .map(BuildRule::getSourcePathToOutput)
                .map(input -> resolver.getRelativePath(input).toString())
                .collect(ImmutableSet.toImmutableSet())));

    boolean hasMainEntry =
        srcs.stream()
            .map(input -> resolver.getAbsolutePath(input).getFileName().toString())
            .anyMatch(SwiftDescriptions.SWIFT_MAIN_FILENAME::equalsIgnoreCase);

    compilerCommand.add(
        "-c",
        enableObjcInterop ? "-enable-objc-interop" : "",
        hasMainEntry ? "" : "-parse-as-library",
        "-serialize-debugging-options",
        "-module-name",
        moduleName,
        "-emit-module",
        "-emit-module-path",
        modulePath.toString(),
        "-emit-objc-header-path",
        headerPath.toString(),
        "-o",
        objectFilePath.toString());

    if (shouldEmitSwiftdocs) {
      compilerCommand.add("-emit-module-doc", "-emit-module-doc-path", swiftdocPath.toString());
    }

    version.ifPresent(
        v -> {
          compilerCommand.add("-swift-version", validVersionString(v));
        });

    compilerCommand.addAll(
        Iterables.filter(Arg.stringify(compilerFlags, resolver), arg -> !arg.equals("-Xfrontend")));
    if (swiftFileListPath.isPresent()) {
      compilerCommand.add("-filelist", swiftFileListPath.get().toString());
    } else {
      for (SourcePath sourcePath : srcs) {
        compilerCommand.add(resolver.getRelativePath(sourcePath).toString());
      }
    }

    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    return new SwiftCompileStep(
        projectFilesystem.getRootPath(), ImmutableMap.of(), compilerCommand.build());
  }

  @VisibleForTesting
  static String validVersionString(String originalVersionString) {
    // Swiftc officially only accepts the major version, but it respects the minor
    // version if the version is 4.2.
    String[] versions = originalVersionString.split("\\.");
    if (versions.length > 2) {
      versions = Arrays.copyOfRange(versions, 0, 2);
    }
    if (versions.length == 2) {
      Integer majorVersion = Integer.parseInt(versions[0]);
      Integer minorVersion = Integer.parseInt(versions[1]);

      if (majorVersion > 4 || (majorVersion >= 4 && minorVersion >= 2)) {
        return String.format("%d.%d", majorVersion, minorVersion);
      } else {
        return originalVersionString.length() > 1
            ? originalVersionString.substring(0, 1)
            : originalVersionString;
      }
    } else {
      return originalVersionString.length() > 1
          ? originalVersionString.substring(0, 1)
          : originalVersionString;
    }
  }

  private SwiftCompileStep makeModulewrapStep(SourcePathResolverAdapter resolver) {
    ImmutableList.Builder<String> compilerCommand = ImmutableList.builder();
    ImmutableList<String> commandPrefix = swiftCompiler.getCommandPrefix(resolver);

    // The swift compiler path will be the first element of the command prefix
    compilerCommand.add(commandPrefix.get(0));
    compilerCommand.add("-modulewrap", modulePath.toString(), "-o", moduleObjectPath.toString());
    compilerCommand.add("-target", swiftTarget.getTriple());

    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    return new SwiftCompileStep(
        projectFilesystem.getRootPath(), ImmutableMap.of(), compilerCommand.build());
  }

  @Override
  public boolean isCacheable() {
    // .swiftmodule artifacts are not cacheable because they can contain machine-specific
    // headers. More specifically, all files included in a bridging header will be
    // literally included in the .swiftmodule file. When the Swift compiler encounters
    // `import Module`, it will include the headers from the .swiftmodule and those
    // headers are referenced via an absolute path stored in the .swiftmodule. This
    // means that Obj-C headers can be included multiple times if the machines which
    // populated the cache and the machine which is building have placed the source
    // repository at different paths (usually the case with CI and developer machines).
    return !bridgingHeader.isPresent() || compileForceCache;
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return depsSupplier.get();
  }

  @Override
  public void updateBuildRuleResolver(BuildRuleResolver ruleResolver) {
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, ruleResolver);
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
    swiftFileListPath.map(
        path -> steps.add(makeFileListStep(context.getSourcePathResolver(), path)));
    steps.add(makeCompileStep(context.getSourcePathResolver()));

    if (useModulewrap) {
      steps.add(makeModulewrapStep(context.getSourcePathResolver()));
    }

    return steps.build();
  }

  private Step makeFileListStep(SourcePathResolverAdapter resolver, AbsPath swiftFileListPath) {
    ImmutableList<String> relativePaths =
        srcs.stream()
            .map(sourcePath -> resolver.getRelativePath(sourcePath).toString())
            .collect(ImmutableList.toImmutableList());

    return new Step() {
      @Override
      public StepExecutionResult execute(ExecutionContext context) throws IOException {
        if (Files.notExists(swiftFileListPath.getParent().getPath())) {
          Files.createDirectories(swiftFileListPath.getParent().getPath());
        }
        MostFiles.writeLinesToFile(relativePaths, swiftFileListPath);
        return StepExecutionResults.SUCCESS;
      }

      @Override
      public String getShortName() {
        return "swift-filelist";
      }

      @Override
      public String getDescription(ExecutionContext context) {
        return "swift-filelist";
      }
    };
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputPath);
  }

  /**
   * @return the arguments to add to the preprocessor command line to include the given header packs
   *     in preprocessor search path.
   *     <p>We can't use CxxHeaders.getArgs() because 1. we don't need the system include roots. 2.
   *     swift doesn't like spaces after the "-I" flag.
   */
  @VisibleForTesting
  ImmutableList<String> getSwiftIncludeArgs(SourcePathResolverAdapter resolver) {
    ImmutableList.Builder<String> args = ImmutableList.builder();

    // Arg list can't simply be passed in since the current implementation of toToolFlags drops the
    // dependency information.
    Iterable<Arg> argsFromDeps =
        cxxDeps
            .toToolFlags(
                resolver,
                PathShortener.byRelativizingToWorkingDir(getProjectFilesystem().getRootPath()),
                frameworkPathToSearchPath,
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
                flavor);
        args.add(INCLUDE_FLAG.concat(headerPath.toString()));
      }
    }

    return args.build();
  }

  public ImmutableList<Arg> getAstLinkArgs() {
    if (!useModulewrap) {
      return ImmutableList.<Arg>builder()
          .addAll(StringArg.from("-Xlinker", "-add_ast_path"))
          .add(SourcePathArg.of(ExplicitBuildTargetSourcePath.of(getBuildTarget(), modulePath)))
          .build();
    } else {
      return ImmutableList.<Arg>builder().build();
    }
  }

  ImmutableList<Arg> getFileListLinkArg() {
    return FileListableLinkerInputArg.from(
        objectPaths.stream()
            .map(
                objectPath ->
                    SourcePathArg.of(
                        ExplicitBuildTargetSourcePath.of(getBuildTarget(), objectPath)))
            .collect(ImmutableList.toImmutableList()));
  }

  /** @return The name of the Swift module. */
  public String getModuleName() {
    return moduleName;
  }

  /** @return List of {@link SourcePath} to the output object file(s) (i.e., .o file) */
  public ImmutableList<SourcePath> getObjectPaths() {
    // Ensures that users of the object path can depend on this build target
    return objectPaths.stream()
        .map(objectPath -> ExplicitBuildTargetSourcePath.of(getBuildTarget(), objectPath))
        .collect(ImmutableList.toImmutableList());
  }

  /** @return File name of the Objective-C Generated Interface Header. */
  public String getObjCGeneratedHeaderFileName() {
    return headerPath.getFileName().toString();
  }

  /** @return {@link SourcePath} of the Objective-C Generated Interface Header. */
  public SourcePath getObjCGeneratedHeaderPath() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), headerPath);
  }

  /**
   * @return {@link SourcePath} to the directory containing outputs from the compilation process
   *     (object files, Swift module metadata, etc).
   */
  public SourcePath getOutputPath() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputPath);
  }

  /**
   * @return {@link SourcePath} to the .swiftmodule output from the compilation process. A
   * swiftmodule file contains the public interface for a module, and is basically a binary file
   * format equivalent to header files for a C framework or library.
   *
   * A swiftmodule file contains serialized ASTs (and possibly SIL), it conforms to
   * Swift Binary Serialization Format, more details about this binary format can be found here:
   * https://github.com/apple/swift/blob/7e6d62dae4bae4eb3737a6f76c0e51534c1bcca3/docs/Serialization.rst.
   */
  public SourcePath getSwiftModuleOutputPath() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), modulePath);
  }
}
