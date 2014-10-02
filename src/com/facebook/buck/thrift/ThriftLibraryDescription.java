/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.thrift;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.List;

public class ThriftLibraryDescription
  implements Description<ThriftConstructorArg>, Flavored, ImplicitDepsInferringDescription {

  public static final BuildRuleType TYPE = new BuildRuleType("thrift_library");
  private static final Flavor INCLUDE_SYMLINK_TREE_FLAVOR = new Flavor("include_symlink_tree");
  private static final BuildRuleType INCLUDE_SYMLINK_TREE_TYPE =
      new BuildRuleType("include_symlink_tree");

  private final ThriftBuckConfig thriftBuckConfig;
  private final ImmutableMap<Flavor, ThriftLanguageSpecificEnhancer> enhancers;

  public ThriftLibraryDescription(
      ThriftBuckConfig thriftBuckConfig,
      ImmutableList<ThriftLanguageSpecificEnhancer> enhancers) {

    this.thriftBuckConfig = Preconditions.checkNotNull(thriftBuckConfig);

    // Now build up a map indexing them by their flavor.
    ImmutableMap.Builder<Flavor, ThriftLanguageSpecificEnhancer> enhancerMapBuilder =
        ImmutableMap.builder();
    for (ThriftLanguageSpecificEnhancer enhancer : enhancers) {
      enhancerMapBuilder.put(enhancer.getFlavor(), enhancer);
    }
    this.enhancers = enhancerMapBuilder.build();
  }

  // Get the flavor that specified the language this build target.
  private Optional<Flavor> getLanguageFlavor(BuildTarget flavoredTarget) {
    Sets.SetView<Flavor> flavors = Sets.intersection(
        enhancers.keySet(),
        flavoredTarget.getFlavors());

    if (flavors.size() == 0) {
      return Optional.absent();
    }

    if (flavors.size() > 1) {
      throw new HumanReadableException(String.format(
          "%s: more than one language flavor specified: %s",
          flavoredTarget,
          flavors));
    }

    return Optional.of(flavors.iterator().next());
  }

  /**
   * Return the path to use for the symlink tree we setup for the thrift files to be
   * included by other rules.
   */
  @VisibleForTesting
  protected Path getIncludeRoot(BuildTarget target) {
    return BuildTargets.getBinPath(target, "%s/include-symlink-tree");
  }

  @VisibleForTesting
  protected BuildTarget createThriftIncludeSymlinkTreeTarget(BuildTarget target) {
    return BuildTargets.extendFlavoredBuildTarget(target, INCLUDE_SYMLINK_TREE_FLAVOR);
  }

  /**
   * Create a unique build target to represent the compile rule for this thrift source for
   * the given language.
   */
  private static BuildTarget createThriftCompilerBuildTarget(
      BuildTarget target,
      String name) {
    Preconditions.checkArgument(target.isFlavored());
    return BuildTargets.extendFlavoredBuildTarget(
        target,
        new Flavor(String.format(
            "thrift-compile-%s",
            name.replace('/', '-').replace('.', '-'))));
  }

  /**
   * Get the output directory for the generated sources used when compiling the given
   * thrift source for the given language.
   */
  @VisibleForTesting
  protected Path getThriftCompilerOutputDir(BuildTarget target, String name) {
    Preconditions.checkArgument(target.isFlavored());
    BuildTarget flavoredTarget = createThriftCompilerBuildTarget(target, name);
    return BuildTargets.getGenPath(flavoredTarget, "%s/sources");
  }

  // Find all transitive thrift library dependencies of this rule.
  private ImmutableSortedSet<ThriftLibrary> getTransitiveThriftLibraryDeps(
      Iterable<ThriftLibrary> inputs) {

    final ImmutableSortedSet.Builder<ThriftLibrary> depsBuilder = ImmutableSortedSet.naturalOrder();

    // Build up a graph of the inputs and their transitive dependencies.
    new AbstractDependencyVisitor(inputs) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        ThriftLibrary thriftRule = (ThriftLibrary) rule;
        depsBuilder.add(thriftRule);
        return ImmutableSet.<BuildRule>copyOf(thriftRule.getThriftDeps());
      }
    }.start();

    return depsBuilder.build();
  }

  /**
   * Build rule type to use for the rule which generates the language sources.
   */
  private static final BuildRuleType THRIFT_COMPILE_TYPE = new BuildRuleType("thrift_compile");

  /**
   * Create the build rules which compile the input thrift sources into their respective
   * language specific sources.
   */
  @VisibleForTesting
  protected ImmutableMap<String, ThriftCompiler> createThriftCompilerBuildRules(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ImmutableList<String> flags,
      String language,
      ImmutableSet<String> options,
      ImmutableMap<String, SourcePath> srcs,
      ImmutableSortedSet<ThriftLibrary> deps) {

    SourcePath compiler = thriftBuckConfig.getCompiler(resolver);

    // Build up the include roots to find thrift file deps and also the build rules that
    // generate them.
    ImmutableMap.Builder<Path, SourcePath> includesBuilder = ImmutableMap.builder();
    ImmutableSortedSet.Builder<SymlinkTree> includeTreeRulesBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableList.Builder<Path> includeRootsBuilder = ImmutableList.builder();
    for (ThriftLibrary dep : deps) {
      includesBuilder.putAll(dep.getIncludes());
      includeTreeRulesBuilder.add(dep.getIncludeTreeRule());
      includeRootsBuilder.add(dep.getIncludeTreeRule().getRoot());
    }
    ImmutableMap<Path, SourcePath> includes = includesBuilder.build();
    ImmutableSortedSet<SymlinkTree> includeTreeRules = includeTreeRulesBuilder.build();
    ImmutableList<Path> includeRoots = includeRootsBuilder.build();

    // For each thrift source, add a thrift compile rule to generate it's sources.
    ImmutableMap.Builder<String, ThriftCompiler> compileRules = ImmutableMap.builder();
    for (ImmutableMap.Entry<String, SourcePath> ent : srcs.entrySet()) {
      String name = ent.getKey();
      SourcePath source = ent.getValue();

      BuildTarget target = createThriftCompilerBuildTarget(params.getBuildTarget(), name);
      Path outputDir = getThriftCompilerOutputDir(params.getBuildTarget(), name);

      compileRules.put(
          name,
          new ThriftCompiler(
              params.copyWithChanges(
                  THRIFT_COMPILE_TYPE,
                  target,
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(
                        SourcePaths.filterBuildRuleInputs(
                            ImmutableList.<SourcePath>builder()
                                .add(compiler)
                                .add(source)
                                .addAll(includes.values())
                                .build()))
                      .addAll(includeTreeRules)
                      .build(),
                  ImmutableSortedSet.<BuildRule>of()),
              compiler,
              flags,
              outputDir,
              source,
              language,
              options,
              includeRoots,
              includes));
    }

    return compileRules.build();
  }

  private static Function<BuildTarget, BuildTarget> getFlavorFn(final Flavor flavor) {
    return new Function<BuildTarget, BuildTarget>() {
      @Override
      public BuildTarget apply(BuildTarget input) {
        return BuildTargets.extendFlavoredBuildTarget(input, flavor);
      }
    };
  }

  /**
   * Downcast the given deps to {@link ThriftLibrary} rules, throwing an error if we see an
   * unexpected type.
   */
  private static ImmutableSortedSet<ThriftLibrary> resolveThriftDeps(
      BuildTarget target,
      Iterable<BuildRule> deps) {

    ImmutableSortedSet.Builder<ThriftLibrary> libDepsBuilder = ImmutableSortedSet.naturalOrder();
    for (BuildRule dep : deps) {
      if (!(dep instanceof ThriftLibrary)) {
        throw new HumanReadableException(
            "%s: parameter \"deps\": \"%s\" (%s) is not a thrift_library",
            target,
            dep.getBuildTarget(),
            dep.getType());
      }
      libDepsBuilder.add((ThriftLibrary) dep);
    }

    return libDepsBuilder.build();
  }

  @Override
  public <A extends ThriftConstructorArg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {

    BuildTarget target = params.getBuildTarget();
    ImmutableMap<String, SourcePath> namedSources =
        SourcePaths.getSourcePathNames(target, "srcs", args.srcs.keySet());

    // The dependencies listed in "deps", which should all be of type "ThriftLibrary".
    ImmutableSortedSet<ThriftLibrary> thriftDeps =
        resolveThriftDeps(
            target,
            resolver.getAllRules(args.deps.or(ImmutableSortedSet.<BuildTarget>of())));

    // The unflavored version of this rule is responsible for setting up the the various
    // build rules to facilitate dependents including it's thrift sources.
    Optional<Flavor> flavor = getLanguageFlavor(target);
    if (!flavor.isPresent()) {

      // Namespace the thrift files using our target's base path.
      ImmutableMap.Builder<Path, SourcePath> includesBuilder = ImmutableMap.builder();
      for (ImmutableMap.Entry<String, SourcePath> entry : namedSources.entrySet()) {
        includesBuilder.put(
            target.getBasePath().resolve(entry.getKey()),
            entry.getValue());
      }
      ImmutableMap<Path, SourcePath> includes = includesBuilder.build();

      // Create the symlink tree build rule and add it to the resolver.
      Path includeRoot = getIncludeRoot(target);
      BuildTarget symlinkTreeTarget = createThriftIncludeSymlinkTreeTarget(target);
      SymlinkTree symlinkTree = new SymlinkTree(
          params.copyWithChanges(
              INCLUDE_SYMLINK_TREE_TYPE,
              symlinkTreeTarget,
              ImmutableSortedSet.<BuildRule>of(),
              ImmutableSortedSet.<BuildRule>of()),
          includeRoot,
          includes);
      resolver.addToIndex(symlinkTree);

      // Create a dummy rule that dependents can use to grab the information they need
      // about this rule from the action graph.
      return new ThriftLibrary(
          params,
          thriftDeps,
          symlinkTree,
          includes);
    }

    ThriftLanguageSpecificEnhancer enhancer = enhancers.get(flavor.get());
    String language = enhancer.getLanguage();
    ImmutableSet<String> options = enhancer.getOptions(target, args);
    ImmutableSet<BuildTarget> implicitDeps = enhancer.getImplicitDepsFromArg(target, args);

    // Lookup the thrift library corresponding to this rule.  We add an implicit dep onto
    // this rule in the findImplicitDepsFromParams method, so this should always exist by
    // the time we get here.
    ThriftLibrary thriftLibrary =
        (ThriftLibrary) resolver.getRule(target.getUnflavoredTarget());

    // We implicitly pass the language-specific flavors of your thrift lib dependencies as
    // language specific deps to the language specific enhancer.
    ImmutableSortedSet<BuildRule> languageSpecificDeps = BuildRules.toBuildRulesFor(
        target,
        resolver,
        Iterables.concat(
            FluentIterable.from(thriftDeps)
                .transform(HasBuildTarget.TO_TARGET)
                .transform(getFlavorFn(flavor.get())),
            implicitDeps),
        false);

    // Create a a build rule for thrift source file, to compile the language specific sources.
    // They keys in this map are the logical names of the thrift files (e.g as specific in a BUCK
    // file, such as "test.thrift").
    ImmutableMap<String, ThriftCompiler> compilerRules = createThriftCompilerBuildRules(
        params,
        resolver,
        args.flags.or(ImmutableList.<String>of()),
        language,
        options,
        namedSources,
        ImmutableSortedSet.<ThriftLibrary>naturalOrder()
            .add(thriftLibrary)
            .addAll(getTransitiveThriftLibraryDeps(thriftDeps))
            .build());
    resolver.addAllToIndex(compilerRules.values());

    // Build up the map of {@link ThriftSource} objects to pass the language specific enhancer.
    // They keys in this map are the logical names of the thrift files (e.g as specific in a BUCK
    // file, such as "test.thrift").
    ImmutableMap.Builder<String, ThriftSource> thriftSourceBuilder = ImmutableMap.builder();
    for (ImmutableMap.Entry<String, SourcePath> ent : namedSources.entrySet()) {
      thriftSourceBuilder.put(
          ent.getKey(),
          new ThriftSource(
              Preconditions.checkNotNull(compilerRules.get(ent.getKey())),
              args.srcs.get(ent.getValue()),
              getThriftCompilerOutputDir(target, ent.getKey())));
    }
    ImmutableMap<String, ThriftSource> thriftSources = thriftSourceBuilder.build();

    // Generate language specific rules.
    return enhancer.createBuildRule(
        params,
        resolver,
        args,
        thriftSources,
        languageSpecificDeps);
  }

  @Override
  public ThriftConstructorArg createUnpopulatedConstructorArg() {
    return new ThriftConstructorArg();
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return !Sets.intersection(enhancers.keySet(), flavors).isEmpty() ||
        flavors.equals(ImmutableSet.of(Flavor.DEFAULT));
  }

  /**
   * Collect implicit deps for the thrift compiler and language specific enhancers.
   */
  @Override
  public Iterable<String> findDepsFromParams(BuildRuleFactoryParams params) {
    Optional<Flavor> flavor = getLanguageFlavor(params.target);

    // The unflavored target represents the actual thrift library, which doesn't need
    // any implicit deps.
    if (!flavor.isPresent()) {
        return ImmutableList.of();
    }

    List<BuildTarget> deps = Lists.newArrayList();

    // The flavored versions of this rule must always implicitly depend on the non-flavored
    // version, as it sets up the include rules for dependents.
    deps.add(params.target.getUnflavoredTarget());

    // Convert all the thrift library deps into their flavored counterparts and
    // add them to our list of deps, to make sure they get included in the target graph.
    for (String rawTarget : params.getOptionalListAttribute("deps")) {
      BuildTarget target = params.resolveBuildTarget(rawTarget);
      if (getLanguageFlavor(target).isPresent()) {
        throw new HumanReadableException(String.format(
            "%s: parameter \"deps\": target \"%s\" must not specify a language flavor",
            params.target,
            target));
      }
      BuildTarget flavoredTarget = BuildTargets.extendFlavoredBuildTarget(target, flavor.get());
      deps.add(flavoredTarget);
    }

    // Add the compiler target, if there is one.
    Optional<BuildTarget> thriftTarget = thriftBuckConfig.getCompilerTarget();
    if (thriftTarget.isPresent()) {
      deps.add(thriftTarget.get());
    }

    // Grab the language specific implicit dependencies and add their raw target representations
    // to our list.
    ThriftLanguageSpecificEnhancer enhancer = enhancers.get(flavor.get());
    ImmutableSet<BuildTarget> implicitDeps = enhancer.getImplicitDepsFromParams(params);
    deps.addAll(implicitDeps);

    return Iterables.transform(deps, Functions.toStringFunction());
  }

}
