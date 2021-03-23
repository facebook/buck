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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDepsQuery;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.stream.Stream;
import org.immutables.value.Value;

public class HaskellIdeDescription
    implements DescriptionWithTargetGraph<HaskellIdeDescriptionArg>,
        ImplicitDepsInferringDescription<HaskellIdeDescription.AbstractHaskellIdeDescriptionArg>,
        Flavored,
        VersionRoot<HaskellIdeDescriptionArg> {

  private final ToolchainProvider toolchainProvider;

  public HaskellIdeDescription(ToolchainProvider toolchainProvider) {
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Class<HaskellIdeDescriptionArg> getConstructorArgType() {
    return HaskellIdeDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      HaskellIdeDescriptionArg args) {

    HaskellPlatform platform =
        getPlatform(buildTarget, args)
            .resolve(context.getActionGraphBuilder(), buildTarget.getTargetConfiguration());

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();

    ImmutableSortedSet.Builder<BuildTarget> projectsBuilder = ImmutableSortedSet.naturalOrder();
    projectsBuilder.addAll(args.getDeps());
    args.getDepsQuery().ifPresent(q -> projectsBuilder.addAll(q.getResolvedQuery()));
    ImmutableSortedSet<BuildTarget> projects = projectsBuilder.build();

    ImmutableSortedSet.Builder<BuildRule> depsBuilder =
        ImmutableSortedSet.<BuildRule>naturalOrder();
    ImmutableList.Builder<HaskellSources> srcsBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> flagsBuilder = ImmutableList.builder();

    Iterable<BuildRule> deps =
        CxxDeps.builder().addDeps(projects).build().get(graphBuilder, platform.getCxxPlatform());
    for (BuildRule project : deps) {
      if (project instanceof HaskellIdeDep) {
        HaskellIdeDep ideDep = (HaskellIdeDep) project;
        depsBuilder.addAll(ideDep.getIdeDeps(platform));

        flagsBuilder.addAll(filterRTSFlags(ideDep.getIdeCompilerFlags()));

        HaskellSources srcs =
            HaskellSources.from(
                project.getBuildTarget(), graphBuilder, platform, "srcs", ideDep.getIdeSources());
        srcsBuilder.add(srcs);
      }
    }

    HaskellSources srcs = HaskellSources.concat(srcsBuilder.build());
    validateSources(args.getSrcs(), srcs);

    return HaskellDescriptionUtils.requireIdeRule(
        buildTarget,
        projects,
        context.getProjectFilesystem(),
        params,
        graphBuilder,
        platform,
        depsBuilder.build(),
        srcs,
        deduplicateFlags(flagsBuilder.build()),
        args.getLinkStyle(),
        args.getExtraScriptTemplates());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractHaskellIdeDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {

    targetGraphOnlyDepsBuilder.addAll(
        getPlatform(buildTarget, constructorArg)
            .getParseTimeDeps(buildTarget.getTargetConfiguration()));

    constructorArg
        .getDepsQuery()
        .ifPresent(
            depsQuery ->
                QueryUtils.extractParseTimeTargets(buildTarget, cellRoots, depsQuery)
                    .forEach(targetGraphOnlyDepsBuilder::add));
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    return getHaskellPlatformsProvider(toolchainTargetConfiguration)
        .getHaskellPlatforms()
        .containsAnyOf(flavors);
  }

  // check that the all the elements in the srcs input are covered
  private void validateSources(SourceSortedSet srcs, HaskellSources actualSrcs) {
    ImmutableSet<SourcePath> actualSrcSet = ImmutableSet.copyOf(actualSrcs.getSourcePaths());
    ImmutableSet<SourcePath> missedPaths =
        srcs.getPaths().stream()
            .filter(p -> !actualSrcSet.contains(p))
            .collect(ImmutableSet.toImmutableSet());

    if (missedPaths.isEmpty()) return;

    StringBuilder msg = new StringBuilder();
    msg.append("Invalid haskell_ide project definition, ");
    msg.append("the 'srcs' clause contains uncovered entries:\n");
    missedPaths.forEach(p -> msg.append("  - ").append(p).append("\n"));
    msg.append("\n");
    msg.append("Exclude these sources or add dependencies that cover them");
    throw new HumanReadableException(msg.toString());
  }

  // Return the C/C++ platform to build against.
  private UnresolvedHaskellPlatform getPlatform(
      BuildTarget target, AbstractHaskellIdeDescriptionArg arg) {
    HaskellPlatformsProvider haskellPlatformsProvider =
        getHaskellPlatformsProvider(target.getTargetConfiguration());
    FlavorDomain<UnresolvedHaskellPlatform> platforms =
        haskellPlatformsProvider.getHaskellPlatforms();

    Optional<UnresolvedHaskellPlatform> flavorPlatform = platforms.getValue(target);
    if (flavorPlatform.isPresent()) {
      return flavorPlatform.get();
    }

    if (arg.getPlatform().isPresent()) {
      return platforms.getValue(arg.getPlatform().get());
    }

    return haskellPlatformsProvider.getDefaultHaskellPlatform();
  }

  private HaskellPlatformsProvider getHaskellPlatformsProvider(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider.getByName(
        HaskellPlatformsProvider.DEFAULT_NAME,
        toolchainTargetConfiguration,
        HaskellPlatformsProvider.class);
  }

  // filter out sections of RTS arguments
  public static ImmutableList<String> filterRTSFlags(Collection<String> flags) {
    ImmutableList.Builder<String> filteredFlags = ImmutableList.builder();
    boolean insideRTSsection = false;

    for (String flag : flags) {
      if (flag.equals("+RTS")) {
        insideRTSsection = true;
      } else if (flag.equals("-RTS")) {
        insideRTSsection = false;
      } else if (flag.startsWith("+RTS")) {
        continue; // catch qouted RTS argument sets, e.g. "+RTS foo"
      } else if (!insideRTSsection) {
        filteredFlags.add(flag);
      }
    }
    return filteredFlags.build();
  }

  public static ImmutableList<String> deduplicateFlags(Collection<String> flags) {
    // A set of single flags, e.g. -Wall
    ImmutableSet.Builder<String> singleFlagsBuilder = ImmutableSet.builder();
    // A set of flag arrays, e.g. [-package, foo]
    ImmutableSet.Builder<ImmutableList<String>> multiFlagsBuilder = ImmutableSet.builder();

    // pairwise iteration to detect paired flags
    Iterator<String> iter = flags.iterator();
    // A mutable var to hold, on every loop iteration,
    // either the value of the previous flag or null if it was an argument
    String previousFlag = iter.hasNext() ? iter.next() : null;
    // Iterate over all the flag, acting on the previous flag and replacing it with the current one
    while (iter.hasNext()) {
      final String currentFlag = iter.next();
      // check if the current item is a flag
      if (currentFlag.startsWith("-")) {
        // it is a flag - add the previous flag, if any, to the singleFlagsBuilder
        if (previousFlag != null) singleFlagsBuilder.add(previousFlag);
        // replace the previous flag with the current one
        previousFlag = currentFlag;
      } else {
        // if it's not a flag then it is an argument preceded by a flag
        // otherwise this would not be a valid set of flags for GHC
        // add the pair to the multi flags builder and clear the previous flag
        multiFlagsBuilder.add(ImmutableList.of(previousFlag, currentFlag));
        previousFlag = null;
      }
    }
    // When the loop is done process the last flag, which is stored in previousFlag
    if (previousFlag != null) singleFlagsBuilder.add(previousFlag);

    // Concatenate both sets in a list and return the result
    return Stream.concat(
            singleFlagsBuilder.build().stream(),
            multiFlagsBuilder.build().stream().flatMap(ImmutableList::stream))
        .collect(ImmutableList.toImmutableList());
  }

  @RuleArg
  interface AbstractHaskellIdeDescriptionArg extends BuildRuleArg, HasDepsQuery {

    Optional<Flavor> getPlatform();

    // Not used TODO: remove
    ImmutableList<String> getCompilerFlags();

    // Not used TODO: remove
    ImmutableList<StringWithMacros> getLinkerFlags();

    // Not used TODO: remove
    PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps();

    // Ignored but needed for owner query resolution
    SourceSortedSet getSrcs();

    Linker.LinkableDepType getLinkStyle();

    @Value.Default
    default ImmutableList<SourcePath> getExtraScriptTemplates() {
      return ImmutableList.of();
    }

    @Override
    default HaskellIdeDescriptionArg withDepsQuery(Query query) {
      if (getDepsQuery().equals(Optional.of(query))) {
        return (HaskellIdeDescriptionArg) this;
      }
      return HaskellIdeDescriptionArg.builder().from(this).setDepsQuery(query).build();
    }
  }
}
