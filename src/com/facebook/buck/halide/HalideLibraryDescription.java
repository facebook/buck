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

// TODO(user): Handle building the pipeline for different targets (e.g.
// GPU) using flavors.

package com.facebook.buck.halide;

import com.facebook.buck.cxx.CxxBinary;
import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLinkAndCompileRules;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessMode;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class HalideLibraryDescription implements
    Description<HalideLibraryDescription.Arg>,
    ImplicitDepsInferringDescription<HalideLibraryDescription.Arg>,
    Flavored {

  private enum Type { EXPORTED_HEADERS, HALIDE_COMPILER };

  private static final Flavor HALIDE_COMPILER_FLAVOR =
    ImmutableFlavor.of("halide-compiler");

  private static final FlavorDomain<Type> LIBRARY_TYPE =
    new FlavorDomain<>(
      "Halide Library Type",
      ImmutableMap.<Flavor, Type>builder()
        .put(
          CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
          Type.EXPORTED_HEADERS)
        .put(HALIDE_COMPILER_FLAVOR, Type.HALIDE_COMPILER)
        .build());

  public static final BuildRuleType TYPE = BuildRuleType.of("halide_library");

  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final CxxPreprocessMode preprocessMode;

  public HalideLibraryDescription(
    FlavorDomain<CxxPlatform> cxxPlatforms,
    CxxPreprocessMode preprocessMode) {
    this.cxxPlatforms = cxxPlatforms;
    this.preprocessMode = preprocessMode;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return cxxPlatforms.containsAnyOf(flavors) ||
      flavors.contains(HALIDE_COMPILER_FLAVOR);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Function<Optional<String>, Path> cellRoots,
      Arg constructorArg) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
    if (buildTarget.getFlavors().contains(HALIDE_COMPILER_FLAVOR)) {
      deps.addAll(constructorArg.compilerDeps.or(
        ImmutableSortedSet.<BuildTarget>of()));
    } else {
      deps.add(createHalideCompilerBuildTarget(buildTarget));
    }
    return deps.build();
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  public static BuildTarget createHalideCompilerBuildTarget(BuildTarget target) {
    return BuildTarget
      .builder(target.getUnflavoredBuildTarget())
      .addFlavors(ImmutableFlavor.of("halide-compiler"))
      .build();
  }

  private final CxxBinary requireHalideCompiler(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<SourceWithFlags> halideSources,
      Optional<ImmutableList<String>> compilerFlags,
      Optional<PatternMatchedCollection<ImmutableList<String>>> platformCompilerFlags,
      Optional<ImmutableList<String>> linkerFlags,
      Optional<PatternMatchedCollection<ImmutableList<String>>> platformLinkerFlags) {
    BuildTarget target = createHalideCompilerBuildTarget(params.getBuildTarget());

    // Check the cache for the halide compiler rule.
    Optional<BuildRule> rule = ruleResolver.getRuleOptional(target);
    if (rule.isPresent()) {
      CxxBinary compilerRule = (CxxBinary) rule.get();
      return compilerRule;
    }

    // Otherwise create the rule and record it in the rule resolver.
    ImmutableMap<String, CxxSource> srcs = CxxDescriptionEnhancer.parseCxxSources(
      params,
      ruleResolver,
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
    Optional<SourcePath> prefixHeader = Optional.absent();
    Optional<Linker.CxxRuntimeType> cxxRuntimeType = Optional.absent();

    CxxLinkAndCompileRules cxxLinkAndCompileRules =
      CxxDescriptionEnhancer.createBuildRulesForCxxBinary(
        targetGraph,
        params.copyWithBuildTarget(target),
        ruleResolver,
        cxxPlatform,
        srcs,
        /* headers */ ImmutableMap.<Path, SourcePath>of(),
        /* lexSrcs */ ImmutableMap.<String, SourcePath>of(),
        /* yaccSrcs */ ImmutableMap.<String, SourcePath>of(),
        preprocessMode,
        Linker.LinkableDepType.STATIC,
        preprocessorFlags,
        platformPreprocessorFlags,
        langPreprocessorFlags,
        frameworks,
        compilerFlags,
        platformCompilerFlags,
        prefixHeader,
        linkerFlags,
        platformLinkerFlags,
        cxxRuntimeType);

    BuildRuleParams binParams = params.copyWithBuildTarget(target);
    binParams.appendExtraDeps(cxxLinkAndCompileRules.executable.getDeps(pathResolver));
    CxxBinary cxxBinary = new CxxBinary(
      binParams,
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

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      final A args) {
    BuildTarget target = params.getBuildTarget();
    Optional<Map.Entry<Flavor, Type>> type;
    Optional<Map.Entry<Flavor, CxxPlatform>> cxxPlatform;
    try {
      type = LIBRARY_TYPE.getFlavorAndValue(
        ImmutableSet.copyOf(target.getFlavors()));
      cxxPlatform = cxxPlatforms.getFlavorAndValue(
        ImmutableSet.copyOf(target.getFlavors()));
    } catch (FlavorDomainException e) {
      throw new HumanReadableException("%s: %s", params.getBuildTarget(), e.getMessage());
    }

    final SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    if (type.isPresent()) {
      if (type.get().getValue() == Type.EXPORTED_HEADERS) {
        Preconditions.checkState(cxxPlatform.isPresent());
        ImmutableMap.Builder<Path, SourcePath> headersBuilder = ImmutableMap.builder();
        BuildTarget unflavoredTarget = BuildTarget
          .builder(target.getUnflavoredBuildTarget())
          .build();
        String headerName = unflavoredTarget.getShortName() + ".h";
        Path outputPath = BuildTargets.getGenPath(
          unflavoredTarget,
          "%s/" + headerName);
        headersBuilder.put(
          Paths.get(headerName),
          new BuildTargetSourcePath(unflavoredTarget, outputPath));
        return CxxDescriptionEnhancer.createHeaderSymlinkTree(
          params,
          resolver,
          new SourcePathResolver(resolver),
          cxxPlatform.get().getValue(),
          /* includeLexYaccHeaders */ false,
          ImmutableMap.<String, SourcePath>of(),
          ImmutableMap.<String, SourcePath>of(),
          headersBuilder.build(),
          HeaderVisibility.PUBLIC);
      } else if (type.get().getValue() == Type.HALIDE_COMPILER) {
        // We always want to build the halide "compiler" for the host platform, so
        // we use the "default" flavor here, regardless of the flavors on the build
        // target.
        CxxPlatform hostCxxPlatform;
        try {
          hostCxxPlatform = cxxPlatforms.getValue(ImmutableFlavor.of("default"));
        } catch (FlavorDomainException e) {
          throw new HumanReadableException(
            "%s: %s",
            params.getBuildTarget(),
            e.getMessage());
        }

        Preconditions.checkState(args.srcs.isPresent());
        final ImmutableSortedSet<BuildTarget> compilerDeps =
          args.compilerDeps.or(ImmutableSortedSet.<BuildTarget>of());
        CxxBinary halideCompiler = requireHalideCompiler(
          targetGraph,
          params.copyWithDeps(
            /* declared deps */ Suppliers.ofInstance(resolver.getAllRules(compilerDeps)),
            /* extra deps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
          resolver,
          pathResolver,
          hostCxxPlatform,
          args.srcs.get(),
          args.compilerFlags,
          args.platformCompilerFlags,
          args.linkerFlags,
          args.platformLinkerFlags);
        return halideCompiler;
      }
    }

    // We implicitly depend on the #halide-compiler version of the rule (via
    // findDepsForTargetFromConstructorArgs) so it should always exist by the
    // time we get here.
    BuildTarget compilerTarget = createHalideCompilerBuildTarget(target);
    Optional<BuildRule> rule = resolver.getRuleOptional(compilerTarget);
    Preconditions.checkState(rule.isPresent());
    CxxBinary halideCompiler = (CxxBinary) rule.get();
    return new HalideLibrary(
      // params.copyWithExtraDeps(
      //   Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(halideCompiler))),
      params,
      resolver,
      pathResolver,
      args.srcs.get(),
      halideCompiler.getExecutableCommand(),
      /* outputDir */ BuildTargets.getGenPath(params.getBuildTarget(), "%s"));
  }

  @SuppressFieldNotInitialized
  public class Arg extends CxxBinaryDescription.Arg {
    @Hint(isDep = false)
    public Optional<ImmutableSortedSet<BuildTarget>> compilerDeps;
  }
}
