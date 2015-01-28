/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImmutableBuildRuleType;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.immutables.value.Value;

@Value.Nested
public class CxxLibraryDescription implements
    Description<CxxLibraryDescription.Arg>,
    ImplicitDepsInferringDescription<CxxLibraryDescription.Arg>,
    Flavored {
  public static enum Type {
    HEADERS,
    SHARED,
    STATIC,
  }

  public static final BuildRuleType TYPE = ImmutableBuildRuleType.of("cxx_library");

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      new FlavorDomain<>(
          "C/C++ Library Type",
          ImmutableMap.of(
              CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR, Type.HEADERS,
              CxxDescriptionEnhancer.SHARED_FLAVOR, Type.SHARED,
              CxxDescriptionEnhancer.STATIC_FLAVOR, Type.STATIC));

  private final CxxBuckConfig cxxBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public CxxLibraryDescription(
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return cxxPlatforms.containsAnyOf(flavors);
  }

  private static final Flavor LEX_YACC_SOURCE_FLAVOR = ImmutableFlavor.of("lex_yacc_sources");

  private BuildTarget createLexYaccSourcesBuildTarget(BuildTarget target) {
    return BuildTarget.builder(target).addFlavors(LEX_YACC_SOURCE_FLAVOR).build();
  }

  private CxxHeaderSourceSpec requireLexYaccSources(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources) {
    BuildTarget lexYaccTarget = createLexYaccSourcesBuildTarget(params.getBuildTarget());

    // Check the cache...
    Optional<BuildRule> rule = ruleResolver.getRuleOptional(lexYaccTarget);
    if (rule.isPresent()) {
      @SuppressWarnings("unchecked")
      ContainerBuildRule<CxxHeaderSourceSpec> containerRule =
          (ContainerBuildRule<CxxHeaderSourceSpec>) rule.get();
      return containerRule.get();
    }

    // Setup the rules to run lex/yacc.
    CxxHeaderSourceSpec lexYaccSources =
        CxxDescriptionEnhancer.createLexYaccBuildRules(
            params,
            ruleResolver,
            cxxPlatform,
            ImmutableList.<String>of(),
            lexSources,
            ImmutableList.<String>of(),
            yaccSources);

    ruleResolver.addToIndex(
        ContainerBuildRule.of(
            params,
            pathResolver,
            lexYaccTarget,
            lexYaccSources));

    return lexYaccSources;
  }

  private SymlinkTree createHeaderSymlinkTree(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMap<Path, SourcePath> headers) {

    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor());
    Path headerSymlinkTreeRoot =
        CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
            params.getBuildTarget(),
            cxxPlatform.getFlavor());

    CxxHeaderSourceSpec lexYaccSources = requireLexYaccSources(
        params,
        ruleResolver,
        pathResolver,
        cxxPlatform,
        lexSources,
        yaccSources);

    return CxxPreprocessables.createHeaderSymlinkTreeBuildRule(
        pathResolver,
        headerSymlinkTreeTarget,
        params,
        headerSymlinkTreeRoot,
        ImmutableMap.<Path, SourcePath>builder()
            .putAll(headers)
            .putAll(lexYaccSources.getCxxHeaders())
            .build());
  }

  /**
   * Make sure all build rules needed to generate the headers symlink tree are added to the action
   * graph.
   *
   * @return the {@link com.facebook.buck.rules.SymlinkTree} rule representing the header tree.
   */
  private SymlinkTree requireHeaderSymlinkTree(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMap<Path, SourcePath> headers) {

    BuildTarget headerTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor());

    // Check the cache...
    Optional<BuildRule> rule = ruleResolver.getRuleOptional(headerTarget);
    if (rule.isPresent()) {
      return (SymlinkTree) rule.get();
    }

    SymlinkTree symlinkTree =
        createHeaderSymlinkTree(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            lexSources,
            yaccSources,
            headers);

    ruleResolver.addToIndex(symlinkTree);

    return symlinkTree;
  }

  private ImmutableList<SourcePath> requireObjects(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableList<String> compilerFlags,
      ImmutableMap<String, CxxSource> sources,
      ImmutableList<Path> frameworkSearchPaths,
      boolean pic) {

    CxxHeaderSourceSpec lexYaccSources =
        requireLexYaccSources(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            lexSources,
            yaccSources);

    SymlinkTree headerSymlinkTree =
        requireHeaderSymlinkTree(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            lexSources,
            yaccSources,
            ImmutableMap.<Path, SourcePath>builder()
                .putAll(headers)
                .putAll(lexYaccSources.getCxxHeaders())
                .build());

    CxxPreprocessorInput cxxPreprocessorInputFromDependencies =
        CxxDescriptionEnhancer.combineCxxPreprocessorInput(
            params,
            cxxPlatform,
            preprocessorFlags,
            headerSymlinkTree,
            frameworkSearchPaths);

    ImmutableMap<String, CxxSource> allSources =
        ImmutableMap.<String, CxxSource>builder()
            .putAll(sources)
            .putAll(lexYaccSources.getCxxSources())
            .build();

    ImmutableMap<String, CxxSource> preprocessed =
        CxxPreprocessables.createPreprocessBuildRules(
            params,
            ruleResolver,
            cxxPlatform,
            cxxPreprocessorInputFromDependencies,
            pic,
            allSources);

    // Create rules for compiling the non-PIC object files.
    return CxxDescriptionEnhancer.createCompileBuildRules(
        params,
        ruleResolver,
        cxxPlatform,
        compilerFlags,
        pic,
        preprocessed);
  }

  /**
   * Create all build rules needed to generate the static library.
   *
   * @return the {@link Archive} rule representing the actual static library.
   */
  private Archive createStaticLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableList<String> compilerFlags,
      ImmutableMap<String, CxxSource> sources,
      ImmutableList<Path> frameworkSearchPaths) {

    // Create rules for compiling the non-PIC object files.
    ImmutableList<SourcePath> objects = requireObjects(
        params,
        ruleResolver,
        pathResolver,
        cxxPlatform,
        lexSources,
        yaccSources,
        preprocessorFlags,
        headers,
        compilerFlags,
        sources,
        frameworkSearchPaths,
        /* pic */ false);

    // Write a build rule to create the archive for this C/C++ library.
    BuildTarget staticTarget =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor());
    Path staticLibraryPath =
        CxxDescriptionEnhancer.getStaticLibraryPath(
            params.getBuildTarget(),
            cxxPlatform.getFlavor());
    Archive staticLibraryBuildRule = Archives.createArchiveRule(
        pathResolver,
        staticTarget,
        params,
        cxxPlatform.getAr(),
        staticLibraryPath,
        objects);

    return staticLibraryBuildRule;
  }

  /**
   * Create all build rules needed to generate the shared library.
   *
   * @return the {@link CxxLink} rule representing the actual shared library.
   */
  private CxxLink createSharedLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableList<String> compilerFlags,
      ImmutableMap<String, CxxSource> sources,
      ImmutableList<String> linkerFlags,
      ImmutableList<Path> frameworkSearchPaths,
      Optional<String> soname) {

    // Create rules for compiling the PIC object files.
    ImmutableList<SourcePath> objects = requireObjects(
        params,
        ruleResolver,
        pathResolver,
        cxxPlatform,
        lexSources,
        yaccSources,
        preprocessorFlags,
        headers,
        compilerFlags,
        sources,
        frameworkSearchPaths,
        /* pic */ true);

    // Setup the rules to link the shared library.
    BuildTarget sharedTarget =
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor());
    String sharedLibrarySoname =
        soname.or(
            CxxDescriptionEnhancer.getSharedLibrarySoname(params.getBuildTarget(), cxxPlatform));
    Path sharedLibraryPath = CxxDescriptionEnhancer.getSharedLibraryPath(
        params.getBuildTarget(),
        cxxPlatform);
    CxxLink sharedLibraryBuildRule =
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            cxxPlatform,
            params,
            pathResolver,
            /* extraCxxLdFlags */ ImmutableList.<String>of(),
            /* extraLdFlags */ linkerFlags,
            sharedTarget,
            Linker.LinkType.SHARED,
            Optional.of(sharedLibrarySoname),
            sharedLibraryPath,
            objects,
            Linker.LinkableDepType.SHARED,
            params.getDeps());

    return sharedLibraryBuildRule;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  public Arg createEmptyConstructorArg() {
    Arg arg = new Arg();
    arg.deps = Optional.absent();
    arg.srcs = Optional.absent();
    arg.headers = Optional.absent();
    arg.compilerFlags = Optional.absent();
    arg.exportedPreprocessorFlags = Optional.absent();
    arg.exportedLangPreprocessorFlags = Optional.absent();
    arg.preprocessorFlags = Optional.absent();
    arg.langPreprocessorFlags = Optional.absent();
    arg.linkerFlags = Optional.absent();
    arg.platformLinkerFlags = Optional.of(ImmutableList.<Pair<String, ImmutableList<String>>>of());
    arg.linkWhole = Optional.absent();
    arg.lexSrcs = Optional.absent();
    arg.yaccSrcs = Optional.absent();
    arg.headerNamespace = Optional.absent();
    arg.soname = Optional.absent();
    arg.frameworkSearchPaths = Optional.of(ImmutableList.<Path>of());
    return arg;
  }

  /**
   * @return a {@link SymlinkTree} for the headers of this C/C++ library.
   */
  public <A extends Arg> SymlinkTree createHeaderSymlinkTreeBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args) {
    return createHeaderSymlinkTree(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        CxxDescriptionEnhancer.parseLexSources(params, resolver, args),
        CxxDescriptionEnhancer.parseYaccSources(params, resolver, args),
        CxxDescriptionEnhancer.parseHeaders(params, resolver, args));
  }

  /**
   * @return a {@link Archive} rule which builds a static library version of this C/C++ library.
   */
  public <A extends Arg> Archive createStaticLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args) {
    return createStaticLibrary(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        CxxDescriptionEnhancer.parseLexSources(params, resolver, args),
        CxxDescriptionEnhancer.parseYaccSources(params, resolver, args),
        ImmutableMultimap.<CxxSource.Type, String>builder()
            .putAll(
                CxxPreprocessorFlags.fromArgs(
                    args.preprocessorFlags,
                    args.langPreprocessorFlags))
            .putAll(
                CxxPreprocessorFlags.fromArgs(
                    args.exportedPreprocessorFlags,
                    args.exportedLangPreprocessorFlags))
            .build(),
        CxxDescriptionEnhancer.parseHeaders(params, resolver, args),
        args.compilerFlags.or(ImmutableList.<String>of()),
        CxxDescriptionEnhancer.parseCxxSources(params, resolver, args),
        args.frameworkSearchPaths.get());
  }

  /**
   * @return a {@link CxxLink} rule which builds a shared library version of this C/C++ library.
   */
  public <A extends Arg> CxxLink createSharedLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args) {
    return createSharedLibrary(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        CxxDescriptionEnhancer.parseLexSources(params, resolver, args),
        CxxDescriptionEnhancer.parseYaccSources(params, resolver, args),
        ImmutableMultimap.<CxxSource.Type, String>builder()
            .putAll(
                CxxPreprocessorFlags.fromArgs(
                    args.preprocessorFlags,
                    args.langPreprocessorFlags))
            .putAll(
                CxxPreprocessorFlags.fromArgs(
                    args.exportedPreprocessorFlags,
                    args.exportedLangPreprocessorFlags))
            .build(),
        CxxDescriptionEnhancer.parseHeaders(params, resolver, args),
        args.compilerFlags.or(ImmutableList.<String>of()),
        CxxDescriptionEnhancer.parseCxxSources(params, resolver, args),
        ImmutableList.<String>builder()
            .addAll(args.linkerFlags.or(ImmutableList.<String>of()))
            .addAll(
                CxxDescriptionEnhancer.getPlatformFlags(
                    args.platformLinkerFlags.get(),
                    cxxPlatform.getFlavor().toString()))
            .build(),
        args.frameworkSearchPaths.get(),
        args.soname);
  }

  @Value.Immutable
  @BuckStyleImmutable
  public static interface TypeAndPlatform {
    @Value.Parameter
    Optional<Map.Entry<Flavor, Type>> getType();

    @Value.Parameter
    Optional<Map.Entry<Flavor, CxxPlatform>> getPlatform();
  }

  public static TypeAndPlatform getTypeAndPlatform(
      BuildTarget buildTarget,
      FlavorDomain<CxxPlatform> platforms) {
    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, Type>> type;
    Optional<Map.Entry<Flavor, CxxPlatform>> platform;
    try {
      type = LIBRARY_TYPE.getFlavorAndValue(
          ImmutableSet.copyOf(buildTarget.getFlavors()));
      platform = platforms.getFlavorAndValue(
          ImmutableSet.copyOf(buildTarget.getFlavors()));
    } catch (FlavorDomainException e) {
      throw new HumanReadableException("%s: %s", buildTarget, e.getMessage());
    }
    return ImmutableCxxLibraryDescription.TypeAndPlatform.of(type, platform);
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    TypeAndPlatform typeAndPlatform = getTypeAndPlatform(
        params.getBuildTarget(),
        cxxPlatforms);
    return createBuildRule(params, resolver, args, typeAndPlatform);
  }

  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args,
      TypeAndPlatform typeAndPlatform) {
    Optional<Map.Entry<Flavor, Type>> type = typeAndPlatform.getType();
    Optional<Map.Entry<Flavor, CxxPlatform>> platform = typeAndPlatform.getPlatform();

    // If we *are* building a specific type of this lib, call into the type specific
    // rule builder methods.
    if (type.isPresent() && platform.isPresent()) {
      Set<Flavor> flavors = Sets.newHashSet(params.getBuildTarget().getFlavors());
      flavors.remove(type.get().getKey());
      BuildTarget target = BuildTarget
          .builder(params.getBuildTarget().getUnflavoredTarget())
          .addAllFlavors(flavors)
          .build();
      BuildRuleParams typeParams =
          params.copyWithChanges(
              params.getBuildRuleType(),
              target,
              Suppliers.ofInstance(params.getDeclaredDeps()),
              Suppliers.ofInstance(params.getExtraDeps()));
      if (type.get().getValue().equals(Type.HEADERS)) {
        return createHeaderSymlinkTreeBuildRule(
            typeParams,
            resolver,
            platform.get().getValue(),
            args);
      } else if (type.get().getValue().equals(Type.SHARED)) {
        return createSharedLibraryBuildRule(
            typeParams,
            resolver,
            platform.get().getValue(),
            args);
      } else {
        return createStaticLibraryBuildRule(
            typeParams,
            resolver,
            platform.get().getValue(),
            args);
      }
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return new CxxLibrary(
        params,
        resolver,
        pathResolver,
        CxxPreprocessorFlags.fromArgs(
            args.exportedPreprocessorFlags,
            args.exportedLangPreprocessorFlags),
        args.linkerFlags.or(ImmutableList.<String>of()),
        args.platformLinkerFlags.get(),
        args.linkWhole.or(false),
        args.soname);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Arg constructorArg) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    if (constructorArg.lexSrcs.isPresent() && !constructorArg.lexSrcs.get().isEmpty()) {
      deps.add(cxxBuckConfig.getLexDep());
    }

    return deps.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends CxxConstructorArg {
    public Optional<ImmutableList<String>> exportedPreprocessorFlags;
    public Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>>
        exportedLangPreprocessorFlags;
    public Optional<String> soname;
    public Optional<Boolean> linkWhole;
  }

}
