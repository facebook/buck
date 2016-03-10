/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.halide;

import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxBinary;
import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLinkAndCompileRules;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessMode;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class HalideLibraryDescription
    implements Description<HalideLibraryDescription.Arg>, Flavored {

  public static final Flavor HALIDE_COMPILER_FLAVOR = ImmutableFlavor.of("halide-compiler");

  public static final BuildRuleType TYPE = BuildRuleType.of("halide_library");

  public static final Flavor HALIDE_COMPILE_FLAVOR = ImmutableFlavor.of("halide-compile");

  private final CxxPlatform defaultCxxPlatform;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final CxxPreprocessMode preprocessMode;
  private final HalideBuckConfig halideBuckConfig;

  public HalideLibraryDescription(
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxPreprocessMode preprocessMode,
      HalideBuckConfig halideBuckConfig) {
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.cxxPlatforms = cxxPlatforms;
    this.preprocessMode = preprocessMode;
    this.halideBuckConfig = halideBuckConfig;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return cxxPlatforms.containsAnyOf(flavors) ||
        flavors.contains(HALIDE_COMPILE_FLAVOR) ||
        flavors.contains(HALIDE_COMPILER_FLAVOR);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  public static BuildTarget createHalideCompilerBuildTarget(BuildTarget target) {
    return target.withFlavors(HALIDE_COMPILER_FLAVOR);
  }

  private CxxBinary createHalideCompiler(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<SourceWithFlags> halideSources,
      Optional<ImmutableList<String>> compilerFlags,
      Optional<PatternMatchedCollection<ImmutableList<String>>> platformCompilerFlags,
      Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>> langCompilerFlags,
      Optional<ImmutableList<String>> linkerFlags,
      Optional<PatternMatchedCollection<ImmutableList<String>>> platformLinkerFlags)
      throws NoSuchBuildTargetException {
    ImmutableMap<String, CxxSource> srcs = CxxDescriptionEnhancer.parseCxxSources(
        params.getBuildTarget(),
        pathResolver,
        cxxPlatform,
        halideSources,
        PatternMatchedCollection.<ImmutableSortedSet<SourceWithFlags>>of());

    Optional<ImmutableList<String>> preprocessorFlags = Optional.absent();
    Optional<PatternMatchedCollection<ImmutableList<String>>>
        platformPreprocessorFlags = Optional.absent();
    Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>>
        langPreprocessorFlags = Optional.absent();
    Optional<ImmutableSortedSet<FrameworkPath>>
        frameworks = Optional.of(ImmutableSortedSet.<FrameworkPath>of());
    Optional<ImmutableSortedSet<FrameworkPath>>
        libraries = Optional.of(ImmutableSortedSet.<FrameworkPath>of());
    Optional<SourcePath> prefixHeader = Optional.absent();
    Optional<Linker.CxxRuntimeType> cxxRuntimeType = Optional.absent();

    CxxLinkAndCompileRules cxxLinkAndCompileRules =
        CxxDescriptionEnhancer.createBuildRulesForCxxBinary(
            params,
            ruleResolver,
            cxxPlatform,
            srcs,
            /* headers */ ImmutableMap.<Path, SourcePath>of(),
            preprocessMode,
            Linker.LinkableDepType.STATIC,
            preprocessorFlags,
            platformPreprocessorFlags,
            langPreprocessorFlags,
            frameworks,
            libraries,
            compilerFlags,
            langCompilerFlags,
            platformCompilerFlags,
            prefixHeader,
            linkerFlags,
            platformLinkerFlags,
            cxxRuntimeType);

    CxxBinary cxxBinary = new CxxBinary(
        params.appendExtraDeps(cxxLinkAndCompileRules.executable.getDeps(pathResolver)),
        ruleResolver,
        pathResolver,
        cxxLinkAndCompileRules.cxxLink.getOutput(),
        cxxLinkAndCompileRules.cxxLink,
        cxxLinkAndCompileRules.executable,
        ImmutableSortedSet.<FrameworkPath>of(),
        ImmutableSortedSet.<BuildTarget>of());
    ruleResolver.addToIndex(cxxBinary);
    return cxxBinary;
  }

  private BuildRule createHalideStaticLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform platform) throws NoSuchBuildTargetException {
    BuildRule halideCompile = ruleResolver.requireRule(
        params.getBuildTarget().withFlavors(HALIDE_COMPILE_FLAVOR, platform.getFlavor()));
    BuildTarget buildTarget = halideCompile.getBuildTarget();
    return new Archive(
        params.copyWithExtraDeps(Suppliers.ofInstance(ImmutableSortedSet.of(halideCompile))),
        pathResolver,
        platform.getAr(),
        platform.getRanlib(),
        CxxDescriptionEnhancer.getStaticLibraryPath(
            params.getBuildTarget(),
            platform.getFlavor(),
            CxxSourceRuleFactory.PicType.PIC),
        ImmutableList.<SourcePath>of(
            new BuildTargetSourcePath(buildTarget, HalideCompile.objectOutputPath(buildTarget))));
  }

  private BuildRule createHalideCompile(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform platform) throws NoSuchBuildTargetException {
    CxxBinary halideCompiler = (CxxBinary) resolver.requireRule(
        params.getBuildTarget().withFlavors(HALIDE_COMPILER_FLAVOR));
    return new HalideCompile(
        params.copyWithExtraDeps(
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(halideCompiler))),
        pathResolver,
        halideCompiler.getExecutableCommand(),
        halideBuckConfig.getHalideTargetForPlatform(platform));
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    BuildTarget target = params.getBuildTarget();
    ImmutableSet<Flavor> flavors = ImmutableSet.copyOf(params.getBuildTarget().getFlavors());
    CxxPlatform cxxPlatform = cxxPlatforms.getValue(flavors).or(defaultCxxPlatform);
    if (flavors.contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)) {
      ImmutableMap.Builder<Path, SourcePath> headersBuilder = ImmutableMap.builder();
      BuildTarget compileTarget = resolver
          .requireRule(target.withFlavors(HALIDE_COMPILE_FLAVOR, cxxPlatform.getFlavor()))
          .getBuildTarget();
      Path outputPath = HalideCompile.headerOutputPath(compileTarget);
      headersBuilder.put(
          outputPath.getFileName(),
          new BuildTargetSourcePath(compileTarget, outputPath));
      return CxxDescriptionEnhancer.createHeaderSymlinkTree(
          params,
          new SourcePathResolver(resolver),
          cxxPlatform,
          headersBuilder.build(),
          HeaderVisibility.PUBLIC);
    } else if (flavors.contains(HALIDE_COMPILER_FLAVOR)) {
      // We always want to build the halide "compiler" for the host platform, so
      // we use the "default" flavor here, regardless of the flavors on the build
      // target.
      CxxPlatform hostCxxPlatform = cxxPlatforms.getValue(ImmutableFlavor.of("default"));
      Preconditions.checkState(args.srcs.isPresent());
      final ImmutableSortedSet<BuildTarget> compilerDeps =
          args.compilerDeps.or(ImmutableSortedSet.<BuildTarget>of());
      return createHalideCompiler(
          params.copyWithChanges(
              params.getBuildTarget().withFlavors(HALIDE_COMPILER_FLAVOR),
              Suppliers.ofInstance(resolver.getAllRules(compilerDeps)),
              Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
          resolver,
          new SourcePathResolver(resolver),
          hostCxxPlatform,
          args.srcs.get(),
          args.compilerFlags,
          args.platformCompilerFlags,
          args.langCompilerFlags,
          args.linkerFlags,
          args.platformLinkerFlags);
    } else if (
        flavors.contains(CxxDescriptionEnhancer.STATIC_FLAVOR) ||
        flavors.contains(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR)) {
      // Halide always output PIC, so it's output can be used for both cases.
      // See: https://github.com/halide/Halide/blob/e3c301f3/src/LLVM_Output.cpp#L152
      return createHalideStaticLibrary(
          params.copyWithDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>of()),
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>of())),
          resolver,
          new SourcePathResolver(resolver),
          cxxPlatform);
    } else if (flavors.contains(CxxDescriptionEnhancer.SHARED_FLAVOR)) {
      throw new HumanReadableException(
          "halide_library '%s' does not support shared libraries as output",
          params.getBuildTarget());
    } else if (flavors.contains(HALIDE_COMPILE_FLAVOR)) {
      return createHalideCompile(
          params.copyWithDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>of()),
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>of())),
          resolver,
          new SourcePathResolver(resolver),
          cxxPlatform);
    }

    return new HalideLibrary(
        params,
        resolver,
        new SourcePathResolver(resolver));
  }

  @SuppressFieldNotInitialized
  public class Arg extends CxxBinaryDescription.Arg {
    public Optional<ImmutableSortedSet<BuildTarget>> compilerDeps;
    public Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> configs;
  }
}
