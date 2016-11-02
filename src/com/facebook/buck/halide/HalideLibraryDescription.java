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
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxFlags;
import com.facebook.buck.cxx.CxxLinkAndCompileRules;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.StripStyle;
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
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.RuleKeyAppendableFunction;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

public class HalideLibraryDescription
    implements Description<HalideLibraryDescription.Arg>, Flavored {

  public static final Flavor HALIDE_COMPILER_FLAVOR = ImmutableFlavor.of("halide-compiler");

  public static final BuildRuleType TYPE = BuildRuleType.of("halide_library");

  public static final Flavor HALIDE_COMPILE_FLAVOR = ImmutableFlavor.of("halide-compile");

  private final CxxPlatform defaultCxxPlatform;
  private final CxxBuckConfig cxxBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final HalideBuckConfig halideBuckConfig;

  public HalideLibraryDescription(
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      HalideBuckConfig halideBuckConfig) {
    this.cxxBuckConfig = cxxBuckConfig;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.cxxPlatforms = cxxPlatforms;
    this.halideBuckConfig = halideBuckConfig;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return cxxPlatforms.containsAnyOf(flavors) ||
        flavors.contains(HALIDE_COMPILE_FLAVOR) ||
        flavors.contains(HALIDE_COMPILER_FLAVOR) ||
        StripStyle.FLAVOR_DOMAIN.containsAnyOf(flavors);
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

  public static boolean isPlatformSupported(Arg arg, CxxPlatform cxxPlatform) {
    return !arg.supportedPlatformsRegex.isPresent() ||
        arg.supportedPlatformsRegex.get()
            .matcher(cxxPlatform.getFlavor().toString())
            .find();
  }

  private CxxBinary createHalideCompiler(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<SourceWithFlags> halideSources,
      ImmutableList<String> compilerFlags,
      PatternMatchedCollection<ImmutableList<String>> platformCompilerFlags,
      ImmutableMap<CxxSource.Type, ImmutableList<String>> langCompilerFlags,
      ImmutableList<String> linkerFlags,
      PatternMatchedCollection<ImmutableList<String>> platformLinkerFlags)
      throws NoSuchBuildTargetException {

    Optional<StripStyle> flavoredStripStyle =
        StripStyle.FLAVOR_DOMAIN.getValue(params.getBuildTarget());
    params = CxxStrip.removeStripStyleFlavorInParams(params, flavoredStripStyle);

    ImmutableMap<String, CxxSource> srcs = CxxDescriptionEnhancer.parseCxxSources(
        params.getBuildTarget(),
        pathResolver,
        cxxPlatform,
        halideSources,
        PatternMatchedCollection.of());

    ImmutableList<String> preprocessorFlags = ImmutableList.of();
    PatternMatchedCollection<ImmutableList<String>>
        platformPreprocessorFlags = PatternMatchedCollection.of();
    ImmutableMap<CxxSource.Type, ImmutableList<String>>
        langPreprocessorFlags = ImmutableMap.of();
    ImmutableSortedSet<FrameworkPath> frameworks = ImmutableSortedSet.of();
    ImmutableSortedSet<FrameworkPath> libraries = ImmutableSortedSet.of();
    Optional<SourcePath> prefixHeader = Optional.empty();
    Optional<Linker.CxxRuntimeType> cxxRuntimeType = Optional.empty();

    CxxLinkAndCompileRules cxxLinkAndCompileRules =
        CxxDescriptionEnhancer.createBuildRulesForCxxBinary(
            params,
            ruleResolver,
            cxxBuckConfig,
            cxxPlatform,
            srcs,
            /* headers */ ImmutableMap.of(),
            flavoredStripStyle,
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

    params = CxxStrip.restoreStripStyleFlavorInParams(params, flavoredStripStyle);
    CxxBinary cxxBinary = new CxxBinary(
        params.appendExtraDeps(cxxLinkAndCompileRules.executable.getDeps(pathResolver)),
        ruleResolver,
        pathResolver,
        cxxLinkAndCompileRules.getBinaryRule(),
        cxxLinkAndCompileRules.executable,
        ImmutableSortedSet.of(),
        ImmutableSortedSet.of(),
        params.getBuildTarget().withoutFlavors(cxxPlatforms.getFlavors()));
    ruleResolver.addToIndex(cxxBinary);
    return cxxBinary;
  }

  private BuildRule createHalideStaticLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform platform,
      Arg args) throws NoSuchBuildTargetException {

    if (!isPlatformSupported(args, platform)) {
      return new NoopBuildRule(
          params,
          pathResolver);
    }

    BuildRule halideCompile = ruleResolver.requireRule(
        params.getBuildTarget().withFlavors(HALIDE_COMPILE_FLAVOR, platform.getFlavor()));
    BuildTarget buildTarget = halideCompile.getBuildTarget();

    return Archive.from(
        params.getBuildTarget(),
        params,
        pathResolver,
        platform,
        cxxBuckConfig.getArchiveContents(),
        CxxDescriptionEnhancer.getStaticLibraryPath(
            params.getProjectFilesystem(),
            params.getBuildTarget(),
            platform.getFlavor(),
            CxxSourceRuleFactory.PicType.PIC,
            platform.getStaticLibraryExtension()),
        ImmutableList.of(
            new BuildTargetSourcePath(
                buildTarget,
                HalideCompile.objectOutputPath(
                    buildTarget,
                    params.getProjectFilesystem(),
                    args.functionName))));
  }

  private Optional<ImmutableList<String>> expandInvocationFlags(
      Optional<ImmutableList<String>> optionalFlags,
      CxxPlatform platform) {
    if (optionalFlags.isPresent()) {
      RuleKeyAppendableFunction<String, String> macroMapper =
          CxxFlags.getTranslateMacrosFn(platform);
      ImmutableList<String> flags = optionalFlags.get();
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      for (String flag : flags) {
        builder.add(macroMapper.apply(flag));
      }
      optionalFlags = Optional.of(builder.build());
    }
    return optionalFlags;
  }

  private BuildRule createHalideCompile(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform platform,
      Optional<ImmutableList<String>> compilerInvocationFlags,
      Optional<String> functionName) throws NoSuchBuildTargetException {
    CxxBinary halideCompiler = (CxxBinary) resolver.requireRule(
        params.getBuildTarget().withFlavors(HALIDE_COMPILER_FLAVOR));

    return new HalideCompile(
        params.copyWithExtraDeps(
            Suppliers.ofInstance(ImmutableSortedSet.of(halideCompiler))),
        pathResolver,
        halideCompiler.getExecutableCommand(),
        halideBuckConfig.getHalideTargetForPlatform(platform),
        expandInvocationFlags(compilerInvocationFlags, platform),
        functionName);
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    BuildTarget target = params.getBuildTarget();
    ImmutableSet<Flavor> flavors = ImmutableSet.copyOf(target.getFlavors());
    CxxPlatform cxxPlatform = cxxPlatforms.getValue(flavors).orElse(defaultCxxPlatform);

    if (flavors.contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)) {
      ImmutableMap.Builder<Path, SourcePath> headersBuilder = ImmutableMap.builder();
      BuildTarget compileTarget = resolver
          .requireRule(target.withFlavors(HALIDE_COMPILE_FLAVOR, cxxPlatform.getFlavor()))
          .getBuildTarget();
      Path outputPath = HalideCompile.headerOutputPath(
          compileTarget,
          params.getProjectFilesystem(),
          args.functionName);
      headersBuilder.put(
          outputPath.getFileName(),
          new BuildTargetSourcePath(compileTarget, outputPath));
      return CxxDescriptionEnhancer.createHeaderSymlinkTree(
          params,
          resolver,
          new SourcePathResolver(resolver),
          cxxPlatform,
          headersBuilder.build(),
          HeaderVisibility.PUBLIC);
    } else if (flavors.contains(HALIDE_COMPILER_FLAVOR)) {
      // We always want to build the halide "compiler" for the host platform, so
      // we use the host flavor here, regardless of the flavors on the build
      // target.
      CxxPlatform hostCxxPlatform = cxxPlatforms.getValue(CxxPlatforms.getHostFlavor());
      final ImmutableSortedSet<BuildTarget> compilerDeps =
          args.compilerDeps;
      return createHalideCompiler(
          params.copyWithChanges(
              params.getBuildTarget().withFlavors(HALIDE_COMPILER_FLAVOR),
              Suppliers.ofInstance(resolver.getAllRules(compilerDeps)),
              Suppliers.ofInstance(ImmutableSortedSet.of())),
          resolver,
          new SourcePathResolver(resolver),
          hostCxxPlatform,
          args.srcs,
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
          params,
          resolver,
          new SourcePathResolver(resolver),
          cxxPlatform,
          args);
    } else if (flavors.contains(CxxDescriptionEnhancer.SHARED_FLAVOR)) {
      throw new HumanReadableException(
          "halide_library '%s' does not support shared libraries as output",
          params.getBuildTarget());
    } else if (flavors.contains(HALIDE_COMPILE_FLAVOR)) {
      return createHalideCompile(
          params.copyWithDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.of()),
              Suppliers.ofInstance(
                  ImmutableSortedSet.of())),
          resolver,
          new SourcePathResolver(resolver),
          cxxPlatform,
          Optional.of(args.compilerInvocationFlags),
          args.functionName);
    }

    return new HalideLibrary(
        params,
        resolver,
        new SourcePathResolver(resolver),
        args.supportedPlatformsRegex);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends CxxBinaryDescription.Arg {
    public ImmutableSortedSet<BuildTarget> compilerDeps = ImmutableSortedSet.of();
    public ImmutableSortedMap<String, ImmutableMap<String, String>> configs =
        ImmutableSortedMap.of();
    public Optional<Pattern> supportedPlatformsRegex;
    public ImmutableList<String> compilerInvocationFlags = ImmutableList.of();
    public Optional<String> functionName;
  }
}
