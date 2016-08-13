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

import com.facebook.buck.apple.AppleCxxPlatform;
import com.facebook.buck.cxx.CxxHeadersDir;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HeaderSymlinkTree;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.ImmutableCxxPreprocessorInputCacheKey;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * An action graph representation of a Swift library from the target graph, providing the
 * various interfaces to make it consumable by C/C native linkable rules.
 */
class SwiftLibrary
    extends AbstractBuildRule
    implements HasRuntimeDeps, NativeLinkable, CxxPreprocessorDep {

  private final LoadingCache<
      CxxPreprocessables.CxxPreprocessorInputCacheKey,
      ImmutableMap<BuildTarget, CxxPreprocessorInput>
      > transitiveCxxPreprocessorInputCache =
      CxxPreprocessables.getTransitiveCxxPreprocessorInputCache(this);

  @AddToRuleKey
  private final Tool swiftCompiler;

  private final Iterable<? extends BuildRule> exportedDeps;
  private final ImmutableSet<FrameworkPath> frameworks;
  private final ImmutableSet<FrameworkPath> libraries;
  private final FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain;

  @AddToRuleKey(stringify = true)
  private final Path outputPath;

  @AddToRuleKey
  private final String moduleName;

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> srcs;

  private final boolean enableObjcInterop;
  private final Optional<Pattern> supportedPlatformsRegex;

  private final Path headerPath;
  private final Path modulePath;
  private final Path objectPath;
  private final boolean hasMainEntry;

  SwiftLibrary(
      Tool swiftCompiler,
      BuildRuleParams params,
      final SourcePathResolver pathResolver,
      Iterable<? extends BuildRule> exportedDeps,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain,
      Path outputPath,
      String moduleName,
      Iterable<SourcePath> srcs,
      Optional<Boolean> enableObjcInterop,
      Optional<Pattern> supportedPlatformsRegex) {
    super(params, pathResolver);
    this.swiftCompiler = swiftCompiler;
    this.exportedDeps = exportedDeps;
    this.frameworks = frameworks;
    this.libraries = libraries;
    this.appleCxxPlatformFlavorDomain = appleCxxPlatformFlavorDomain;
    this.outputPath = outputPath;
    this.enableObjcInterop = enableObjcInterop.or(true);
    this.supportedPlatformsRegex = supportedPlatformsRegex;
    this.headerPath = outputPath.resolve(toSwiftHeaderName(moduleName) + ".h");

    String escapedModuleName = normalizeSwiftModuleName(moduleName);
    this.moduleName = escapedModuleName;
    this.modulePath = outputPath.resolve(escapedModuleName + ".swiftmodule");
    this.objectPath = outputPath.resolve(escapedModuleName + ".o");
    this.srcs = ImmutableSortedSet.copyOf(srcs);
    this.hasMainEntry = FluentIterable.from(srcs).firstMatch(new Predicate<SourcePath>() {
      @Override
      public boolean apply(SourcePath input) {
        return SWIFT_MAIN_FILENAME.equalsIgnoreCase(
            getResolver().getAbsolutePath(input).getFileName().toString());
      }
    }).isPresent();
  }

  private boolean isPlatformSupported(CxxPlatform cxxPlatform) {
    return !supportedPlatformsRegex.isPresent() ||
        supportedPlatformsRegex.get()
            .matcher(cxxPlatform.getFlavor().toString())
            .find();
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDeps(CxxPlatform cxxPlatform) {
    // TODO(bhamiltoncx, ryu2): Use pseudo targets to represent the Swift
    // runtime library's linker args here so NativeLinkables can
    // deduplicate the linker flags on the build target (which would be the same for
    // all libraries).
    return FluentIterable.from(getDeclaredDeps())
        .filter(NativeLinkable.class);
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(CxxPlatform cxxPlatform) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return FluentIterable.from(exportedDeps)
        .filter(NativeLinkable.class);
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type) throws NoSuchBuildTargetException {
    NativeLinkableInput.Builder inputBuilder = NativeLinkableInput.builder();

    // Add linker flags.

    AppleCxxPlatform appleCxxPlatform =
        appleCxxPlatformFlavorDomain.getValue(cxxPlatform.getFlavor());

    // TODO(ryu2): Many of these args need to be deduplicated using a pseudo
    // target to represent the Swift runtime library's linker args.
    Set<Path> swiftRuntimePaths = ImmutableSet.of();
    boolean sharedRequested = false;
    switch (type) {
      case STATIC:
        // Fall through.
      case STATIC_PIC:
        swiftRuntimePaths = appleCxxPlatform.getSwiftStaticRuntimePaths();
        break;
      case SHARED:
        sharedRequested = true;
        break;
    }

    // Fall back to shared if static isn't supported on this platform.
    if (sharedRequested || swiftRuntimePaths.isEmpty()) {
      inputBuilder.addAllArgs(
          StringArg.from(
              "-Xlinker",
              "-rpath",
              "-Xlinker",
              "@executable_path/Frameworks"));
      swiftRuntimePaths = appleCxxPlatform.getSwiftRuntimePaths();
    } else {
      // Static linking requires force-loading Swift libs, since the dependency
      // discovery mechanism is disabled otherwise.
      inputBuilder.addAllArgs(
          StringArg.from(
              "-Xlinker",
              "-force_load_swift_libs"));
    }
    for (Path swiftRuntimePath : swiftRuntimePaths) {
      inputBuilder.addAllArgs(StringArg.from("-L", swiftRuntimePath.toString()));
    }
    inputBuilder
        .addAllArgs(StringArg.from("-Xlinker", "-add_ast_path"))
        .addArgs(
            new SourcePathArg(
                getResolver(),
                new BuildTargetSourcePath(getBuildTarget(), modulePath)),
            new SourcePathArg(
                getResolver(),
                new BuildTargetSourcePath(getBuildTarget(), objectPath)));
    inputBuilder.addAllFrameworks(frameworks);
    inputBuilder.addAllLibraries(libraries);
    return inputBuilder.build();
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
    return ImmutableMap.of();
  }

  private SwiftCompileStep makeCompileStep() {
    ImmutableList.Builder<String> compilerCommand = ImmutableList.builder();
    compilerCommand.addAll(swiftCompiler.getCommandPrefix(getResolver()));
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
    for (SourcePath sourcePath : srcs) {
      compilerCommand.add(getResolver().getRelativePath(sourcePath).toString());
    }

    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    return new SwiftCompileStep(
        projectFilesystem.getRootPath(),
        ImmutableMap.<String, String>of(),
        compilerCommand.build());
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return Linkage.ANY;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    // We export all declared deps as runtime deps, to setup a transitive runtime dep chain which
    // will pull in runtime deps (e.g. other binaries) or transitive C/C++ libraries.  Since the
    // `CxxLibrary` rules themselves are noop meta rules, they shouldn't add any unnecessary
    // overhead.
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(getDeclaredDeps())
        .addAll(exportedDeps)
        .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(outputPath);
    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), outputPath),
        makeCompileStep());
  }

  @Nullable
  @Override
  public Path getPathToOutput() {
    return outputPath;
  }

  @Override
  public Iterable<? extends CxxPreprocessorDep> getCxxPreprocessorDeps(CxxPlatform cxxPlatform) {
    return FluentIterable.from(getDeps())
        .filter(CxxPreprocessorDep.class);
  }

  @Override
  public Optional<HeaderSymlinkTree> getExportedHeaderSymlinkTree(
      CxxPlatform cxxPlatform) {
    return Optional.absent();
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) throws NoSuchBuildTargetException {
    return CxxPreprocessorInput.builder()
        .addIncludes(CxxHeadersDir.of(
            CxxPreprocessables.IncludeType.LOCAL,
            new BuildTargetSourcePath(getBuildTarget())))
        .build();
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) throws NoSuchBuildTargetException {
    return transitiveCxxPreprocessorInputCache.getUnchecked(
        ImmutableCxxPreprocessorInputCacheKey.of(cxxPlatform, headerVisibility));
  }
}
