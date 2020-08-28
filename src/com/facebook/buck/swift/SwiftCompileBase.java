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
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
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
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.PathShortener;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.AddsToRuleKeyFunction;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.MoreIterables;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
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
public abstract class SwiftCompileBase extends AbstractBuildRule
    implements SupportsInputBasedRuleKey {

  private static final String INCLUDE_FLAG = "-I";

  @AddToRuleKey protected final Tool swiftCompiler;

  @AddToRuleKey private final String moduleName;

  @AddToRuleKey(stringify = true)
  protected final Path outputPath;

  @AddToRuleKey(stringify = true)
  private final Path objectFilePath;

  @AddToRuleKey(stringify = true)
  private final Path modulePath;

  @AddToRuleKey(stringify = true)
  private final ImmutableList<Path> objectPaths;

  @AddToRuleKey(stringify = true)
  private final Path swiftdocPath;

  @AddToRuleKey(stringify = true)
  private final Path headerPath;

  @AddToRuleKey private final boolean shouldEmitSwiftdocs;

  @AddToRuleKey protected final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey private final AppleCompilerTargetTriple swiftTarget;
  @AddToRuleKey private final Optional<String> version;
  @AddToRuleKey private final ImmutableList<? extends Arg> compilerFlags;

  @AddToRuleKey private final ImmutableSet<FrameworkPath> frameworks;
  @AddToRuleKey private final AddsToRuleKeyFunction<FrameworkPath, Path> frameworkPathToSearchPath;

  @AddToRuleKey(stringify = true)
  private final Flavor flavor;

  @AddToRuleKey private final boolean enableObjcInterop;
  @AddToRuleKey protected final Optional<SourcePath> bridgingHeader;

  @AddToRuleKey private final Preprocessor cPreprocessor;

  @AddToRuleKey private final PreprocessorFlags cxxDeps;

  @AddToRuleKey private final boolean importUnderlyingModule;

  @AddToRuleKey private final boolean useArgfile;

  @AddToRuleKey protected final boolean withDownwardApi;

  @AddToRuleKey private final boolean inputBasedEnabled;

  // The following fields do not have to be part of the rulekey, all the other must.

  private BuildableSupport.DepsSupplier depsSupplier;
  protected final Optional<AbsPath> swiftFileListPath; // internal scratch temp path
  protected final Optional<AbsPath> argfilePath; // internal scratch temp path

  SwiftCompileBase(
      SwiftBuckConfig swiftBuckConfig,
      BuildTarget buildTarget,
      AppleCompilerTargetTriple swiftTarget,
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
      boolean importUnderlyingModule,
      boolean withDownwardApi) {
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
    this.objectPaths = ImmutableList.of(objectFilePath);

    RelPath scratchDir =
        BuildTargetPaths.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s");
    this.swiftFileListPath =
        swiftBuckConfig.getUseFileList()
            ? Optional.of(
                getProjectFilesystem().getRootPath().resolve(scratchDir.resolve("filelist.txt")))
            : Optional.empty();

    this.useArgfile = swiftBuckConfig.getUseArgfile();
    this.argfilePath =
        (this.useArgfile
            ? Optional.of(
                getProjectFilesystem().getRootPath().resolve(scratchDir.resolve("swiftc.argfile")))
            : Optional.empty());

    this.shouldEmitSwiftdocs = swiftBuckConfig.getEmitSwiftdocs();
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
    this.withDownwardApi = withDownwardApi;
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, graphBuilder);
    this.inputBasedEnabled = swiftBuckConfig.getInputBasedCompileEnabled();
    performChecks(buildTarget);
  }

  @Override
  public boolean inputBasedRuleKeyIsEnabled() {
    return inputBasedEnabled;
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

  /** Creates the list of arguments to pass to the Swift compiler */
  protected ImmutableList<String> constructCompilerArgs(SourcePathResolverAdapter resolver) {
    ImmutableList.Builder<String> compilerArgs = ImmutableList.builder();
    compilerArgs.add("-target", swiftTarget.getTriple());

    if (bridgingHeader.isPresent()) {
      compilerArgs.add(
          "-import-objc-header", resolver.getCellUnsafeRelPath(bridgingHeader.get()).toString());
    }
    if (importUnderlyingModule) {
      compilerArgs.add("-import-underlying-module");
    }

    compilerArgs.addAll(
        Streams.concat(frameworks.stream(), cxxDeps.getFrameworkPaths().stream())
            .filter(x -> !x.isSDKROOTFrameworkPath())
            .map(frameworkPathToSearchPath)
            .flatMap(searchPath -> ImmutableSet.of("-F", searchPath.toString()).stream())
            .iterator());

    compilerArgs.addAll(
        MoreIterables.zipAndConcat(Iterables.cycle("-Xcc"), getSwiftIncludeArgs(resolver)));
    compilerArgs.addAll(
        MoreIterables.zipAndConcat(
            Iterables.cycle(INCLUDE_FLAG),
            getBuildDeps().stream()
                .filter(SwiftCompile.class::isInstance)
                .map(BuildRule::getSourcePathToOutput)
                .map(input -> resolver.getCellUnsafeRelPath(input).toString())
                .collect(ImmutableSet.toImmutableSet())));

    boolean hasMainEntry =
        srcs.stream()
            .map(input -> resolver.getAbsolutePath(input).getFileName().toString())
            .anyMatch(SwiftDescriptions.SWIFT_MAIN_FILENAME::equalsIgnoreCase);

    compilerArgs.add(
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
      compilerArgs.add("-emit-module-doc", "-emit-module-doc-path", swiftdocPath.toString());
    }

    version.ifPresent(
        v -> {
          compilerArgs.add("-swift-version", validVersionString(v));
        });

    compilerArgs.addAll(
        Iterables.filter(Arg.stringify(compilerFlags, resolver), arg -> !arg.equals("-Xfrontend")));
    if (swiftFileListPath.isPresent()) {
      compilerArgs.add("-filelist", swiftFileListPath.get().toString());
    } else {
      for (SourcePath sourcePath : srcs) {
        compilerArgs.add(resolver.getCellUnsafeRelPath(sourcePath).toString());
      }
    }

    return compilerArgs.build();
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

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return depsSupplier.get();
  }

  @Override
  public void updateBuildRuleResolver(BuildRuleResolver ruleResolver) {
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, ruleResolver);
  }

  /** Returns the step that creates a filelist file to be passed to the compiler */
  protected Step makeFileListStep(SourcePathResolverAdapter resolver, AbsPath swiftFileListPath) {
    ImmutableList<String> relativePaths =
        srcs.stream()
            .map(sourcePath -> resolver.getCellUnsafeRelPath(sourcePath).toString())
            .collect(ImmutableList.toImmutableList());

    return new Step() {
      @Override
      public StepExecutionResult execute(StepExecutionContext context) throws IOException {
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
      public String getDescription(StepExecutionContext context) {
        return "swift-filelist";
      }
    };
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
        RelPath headerPath =
            CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
                getProjectFilesystem(), getBuildTarget().withFlavors(), headerVisibility, flavor);
        args.add(INCLUDE_FLAG.concat(headerPath.toString()));
      }
    }

    return args.build();
  }

  public ImmutableList<Arg> getAstLinkArgs() {
    return ImmutableList.<Arg>builder()
        .addAll(StringArg.from("-Xlinker", "-add_ast_path"))
        .add(StringArg.of("-Xlinker"))
        // NB: The paths to the .swiftmodule files will be relative to the cell, not absolute.
        //     This makes it non-machine specific but if we change the behavior, the OSO
        //     rewriting code needs to adjusted to also fix-up N_AST entries.
        .add(SourcePathArg.of(ExplicitBuildTargetSourcePath.of(getBuildTarget(), modulePath)))
        .build();
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
   *     swiftmodule file contains the public interface for a module, and is basically a binary file
   *     format equivalent to header files for a C framework or library.
   */
  public SourcePath getSwiftModuleOutputPath() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), modulePath);
  }
}
