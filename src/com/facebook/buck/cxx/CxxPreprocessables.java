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

package com.facebook.buck.cxx;

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.ImmutableBuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Map;

public class CxxPreprocessables {

  private CxxPreprocessables() {}

  private static final BuildRuleType HEADER_SYMLINK_TREE_TYPE =
      ImmutableBuildRuleType.of("header_symlink_tree");

  private static final BuildRuleType PREPROCESS_TYPE = ImmutableBuildRuleType.of("preprocess");

  /**
   * Resolve the map of name to {@link SourcePath} to a map of full header name to
   * {@link SourcePath}.
   */
  public static ImmutableMap<Path, SourcePath> resolveHeaderMap(
      Path basePath,
      ImmutableMap<String, SourcePath> headers) {

    ImmutableMap.Builder<Path, SourcePath> headerMap = ImmutableMap.builder();

    // Resolve the "names" of the headers to actual paths by prepending the base path
    // specified by the build target.
    for (ImmutableMap.Entry<String, SourcePath> ent : headers.entrySet()) {
      Path path = basePath.resolve(ent.getKey());
      headerMap.put(path, ent.getValue());
    }

    return headerMap.build();
  }

  /**
   * Find and return the {@link CxxPreprocessorInput} objects from {@link CxxPreprocessorDep}
   * found while traversing the dependencies starting from the {@link BuildRule} objects given.
   */
  public static CxxPreprocessorInput getTransitiveCxxPreprocessorInput(
      final CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs,
      final Predicate<Object> traverse) {

    // We don't really care about the order we get back here, since headers shouldn't
    // conflict.  However, we want something that's deterministic, so sort by build
    // target.
    final Map<BuildTarget, CxxPreprocessorInput> deps = Maps.newTreeMap();

    // Build up the map of all C/C++ preprocessable dependencies.
    AbstractBreadthFirstTraversal<BuildRule> visitor =
        new AbstractBreadthFirstTraversal<BuildRule>(inputs) {
          @Override
          public ImmutableSet<BuildRule> visit(BuildRule rule) {
            if (rule instanceof CxxPreprocessorDep) {
              CxxPreprocessorDep dep = (CxxPreprocessorDep) rule;
              Preconditions.checkState(!deps.containsKey(rule.getBuildTarget()));
              deps.put(rule.getBuildTarget(), dep.getCxxPreprocessorInput(cxxPlatform));
            }
            return traverse.apply(rule) ? rule.getDeps() : ImmutableSet.<BuildRule>of();
          }
        };
    visitor.start();

    // Grab the cxx preprocessor inputs and return them.
    return CxxPreprocessorInput.concat(deps.values());
  }

  public static CxxPreprocessorInput getTransitiveCxxPreprocessorInput(
      final CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs) {
    return getTransitiveCxxPreprocessorInput(
        cxxPlatform,
        inputs,
        Predicates.instanceOf(CxxPreprocessorDep.class));
  }

  /**
   * Build the {@link SymlinkTree} rule using the original build params from a target node.
   * In particular, make sure to drop all dependencies from the original build rule params,
   * as these are modeled via {@link CxxCompile}.
   */
  public static SymlinkTree createHeaderSymlinkTreeBuildRule(
      SourcePathResolver resolver,
      BuildTarget target,
      BuildRuleParams params,
      Path root,
      ImmutableMap<Path, SourcePath> links) {

    return new SymlinkTree(
        params.copyWithChanges(
            HEADER_SYMLINK_TREE_TYPE,
            target,
            // Symlink trees never need to depend on anything.
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        resolver,
        root,
        links);
  }

  /**
   * @return the preprocessed file name for the given source name.
   */
  private static String getOutputName(CxxSource.Type type, String name) {

    String extension;
    switch (type) {
      case ASSEMBLER_WITH_CPP:
        extension = "s";
        break;
      case C:
        extension = "i";
        break;
      case CXX:
        extension = "ii";
        break;
      case OBJC:
        extension = "mi";
        break;
      case OBJCXX:
        extension = "mii";
        break;
      // $CASES-OMITTED$
      default:
        throw new IllegalStateException(String.format("unexpected type: %s", type));
    }

    return name + "." + extension;
  }

  /**
   * @return a {@link BuildTarget} used for the rule that preprocesses the source by the given
   *     name and type.
   */
  public static BuildTarget createPreprocessBuildTarget(
      BuildTarget target,
      Flavor platform,
      CxxSource.Type type,
      boolean pic,
      String name) {
    return BuildTarget
        .builder(target)
        .addFlavors(platform)
        .addFlavors(
            ImmutableFlavor.of(
                String.format(
                    "preprocess-%s%s",
                    pic ? "pic-" : "",
                    getOutputName(type, name)
                        .replace('/', '-')
                        .replace('.', '-')
                        .replace('+', '-')
                        .replace(' ', '-'))))
        .build();
  }

  /**
   * @return the output path for an object file compiled from the source with the given name.
   */
  public static Path getPreprocessOutputPath(BuildTarget target, CxxSource.Type type, String name) {
    return BuildTargets.getBinPath(target, "%s").resolve(getOutputName(type, name));
  }

  /**
   * Generate a build rule that preprocesses the given source.
   *
   * @return a pair of the output name and source.
   */
  public static Map.Entry<String, CxxSource> createPreprocessBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      CxxPreprocessorInput preprocessorInput,
      boolean pic,
      String name,
      CxxSource source) {

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    ImmutableSortedSet.Builder<BuildRule> dependencies = ImmutableSortedSet.naturalOrder();

    // If a build rule generates our input source, add that as a dependency.
    dependencies.addAll(pathResolver.filterBuildRuleInputs(ImmutableList.of(source.getPath())));

    // Depend on the rule that generates the sources and headers we're compiling.
    dependencies.addAll(
        pathResolver.filterBuildRuleInputs(
            ImmutableList.<SourcePath>builder()
                .add(source.getPath())
                .addAll(preprocessorInput.getIncludes().getNameToPathMap().values())
                .build()));

    // Also add in extra deps from the preprocessor input, such as the symlink tree
    // rules.
    dependencies.addAll(
        BuildRules.toBuildRulesFor(
            params.getBuildTarget(),
            resolver,
            preprocessorInput.getRules(),
            false));

    Tool preprocessor;
    ImmutableList.Builder<String> args = ImmutableList.builder();
    CxxSource.Type outputType;

    // We explicitly identify our source rather then let the compiler guess based on the
    // extension.
    args.add("-x", source.getType().getLanguage());

    // Pick the compiler to use.  Basically, if we're dealing with C++ sources, use the C++
    // compiler, and the C compiler for everything.
    switch (source.getType()) {
      case ASSEMBLER_WITH_CPP:
        preprocessor = cxxPlatform.getAspp();
        args.addAll(cxxPlatform.getAsppflags());
        args.addAll(
            preprocessorInput.getPreprocessorFlags().get(CxxSource.Type.ASSEMBLER_WITH_CPP));
        outputType = CxxSource.Type.ASSEMBLER;
        break;
      case C:
        preprocessor = cxxPlatform.getCpp();
        args.addAll(cxxPlatform.getCppflags());
        args.addAll(preprocessorInput.getPreprocessorFlags().get(CxxSource.Type.C));
        outputType = CxxSource.Type.C_CPP_OUTPUT;
        break;
      case CXX:
        preprocessor = cxxPlatform.getCxxpp();
        args.addAll(cxxPlatform.getCxxppflags());
        args.addAll(preprocessorInput.getPreprocessorFlags().get(CxxSource.Type.CXX));
        outputType = CxxSource.Type.CXX_CPP_OUTPUT;
        break;
      case OBJC:
        preprocessor = cxxPlatform.getCpp();
        args.addAll(cxxPlatform.getCppflags());
        args.addAll(preprocessorInput.getPreprocessorFlags().get(CxxSource.Type.OBJC));
        outputType = CxxSource.Type.OBJC_CPP_OUTPUT;
        break;
      case OBJCXX:
        preprocessor = cxxPlatform.getCxxpp();
        args.addAll(cxxPlatform.getCxxppflags());
        args.addAll(preprocessorInput.getPreprocessorFlags().get(CxxSource.Type.OBJCXX));
        outputType = CxxSource.Type.OBJCXX_CPP_OUTPUT;
        break;
      // $CASES-OMITTED$
      default:
        throw new IllegalStateException(String.format("unexpected type: %s", source.getType()));
    }

    // Add dependencies on any build rules used to create the preprocessor.
    dependencies.addAll(preprocessor.getBuildRules(pathResolver));

    // If we're using pic, add in the appropriate flag.
    if (pic) {
      args.add("-fPIC");
    }

    // Build the CxxCompile rule and add it to our sorted set of build rules.
    BuildTarget target =
        createPreprocessBuildTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor(),
            source.getType(),
            pic,
            name);
    CxxPreprocess cxxPreprocess = new CxxPreprocess(
        params.copyWithChanges(
            PREPROCESS_TYPE,
            target,
            Suppliers.ofInstance(dependencies.build()),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        pathResolver,
        preprocessor,
        args.build(),
        getPreprocessOutputPath(target, source.getType(), name),
        source.getPath(),
        ImmutableList.copyOf(preprocessorInput.getIncludeRoots()),
        ImmutableList.copyOf(preprocessorInput.getSystemIncludeRoots()),
        ImmutableList.copyOf(preprocessorInput.getFrameworkRoots()),
        preprocessorInput.getIncludes(),
        cxxPlatform.getDebugPathSanitizer());
    resolver.addToIndex(cxxPreprocess);

    // Return the output name and source pair.
    return new AbstractMap.SimpleEntry<String, CxxSource>(
        name,
        ImmutableCxxSource.of(
            outputType,
            new BuildTargetSourcePath(cxxPreprocess.getBuildTarget())));
  }

  /**
   * Generate build rules which preprocess the given input sources.
   */
  public static ImmutableMap<String, CxxSource> createPreprocessBuildRules(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform config,
      CxxPreprocessorInput cxxPreprocessorInput,
      boolean pic,
      ImmutableMap<String, CxxSource> sources) {

    ImmutableMap.Builder<String, CxxSource> preprocessedSources = ImmutableMap.builder();

    for (Map.Entry<String, CxxSource> entry : sources.entrySet()) {
      if (CxxSourceTypes.isPreprocessableType(entry.getValue().getType())) {
        entry = CxxPreprocessables.createPreprocessBuildRule(
            params,
            resolver,
            config,
            cxxPreprocessorInput,
            pic,
            entry.getKey(),
            entry.getValue());
      }
      preprocessedSources.put(entry);
    }

    return preprocessedSources.build();
  }

}
