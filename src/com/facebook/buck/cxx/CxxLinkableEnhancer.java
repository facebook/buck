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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.EnumSet;

public class CxxLinkableEnhancer {

  private static final EnumSet<Linker.LinkType> SONAME_REQUIRED_LINK_TYPES = EnumSet.of(
      Linker.LinkType.SHARED,
      Linker.LinkType.MACH_O_BUNDLE
  );

  // Utility class doesn't instantiate.
  private CxxLinkableEnhancer() {}

  /**
   * Construct a {@link CxxLink} rule that builds a native linkable from top-level input objects
   * and a dependency tree of {@link NativeLinkable} dependencies.
   */
  public static CxxLink createCxxLinkableBuildRule(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableList<String> extraLdFlags,
      BuildTarget target,
      Linker.LinkType linkType,
      Optional<String> soname,
      Path output,
      Iterable<SourcePath> inputs,
      Iterable<SourcePath> extraInputs,
      Linker.LinkableDepType depType,
      Iterable<? extends BuildRule> nativeLinkableDeps,
      Optional<Linker.CxxRuntimeType> cxxRuntimeType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildRule> blacklist) {

    // Soname should only ever be set when linking a "shared" library.
    Preconditions.checkState(!soname.isPresent() || SONAME_REQUIRED_LINK_TYPES.contains(linkType));

    // Bundle loaders are only supported for Mach-O bundle libraries
    Preconditions.checkState(
        !bundleLoader.isPresent() || linkType == Linker.LinkType.MACH_O_BUNDLE);

    Linker linker = cxxPlatform.getLd();

    // Collect and topologically sort our deps that contribute to the link.
    NativeLinkableInput linkableInput =
        NativeLinkables.getTransitiveNativeLinkableInput(
            targetGraph,
            cxxPlatform,
            nativeLinkableDeps,
            depType,
            blacklist,
            /* reverse */ true);
    ImmutableList<SourcePath> allInputs =
        ImmutableList.<SourcePath>builder()
            .addAll(inputs)
            .addAll(extraInputs)
            .addAll(linkableInput.getInputs())
            .build();

    // Construct our link build rule params.  The important part here is combining the build rules
    // that construct our object file inputs and also the deps that build our dependencies.
    ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();
    deps.addAll(resolver.filterBuildRuleInputs(allInputs));

    // If this is being linked against a bundle loader created by another rule,
    // add that rule to our deps.
    if (bundleLoader.isPresent()) {
      Optional<BuildRule> bundleLoaderRule = resolver.getRule(bundleLoader.get());
      if (bundleLoaderRule.isPresent()) {
        deps.add(bundleLoaderRule.get());
      }
    }

    BuildRuleParams linkParams = params.copyWithChanges(
        target,
        // Add dependencies for build rules generating the object files and inputs from
        // dependencies.
        Suppliers.ofInstance(deps.build()),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));

    // Build up the arguments to pass to the linker.
    ImmutableList.Builder<String> argsBuilder = ImmutableList.builder();

    // Pass any platform specific or extra linker flags.
    argsBuilder.addAll(cxxPlatform.getLdflags());
    argsBuilder.addAll(extraLdFlags);

    // If we're doing a shared build, pass the necessary flags to the linker, including setting
    // the soname.
    if (linkType == Linker.LinkType.SHARED) {
      argsBuilder.add("-shared");
    } else if (linkType == Linker.LinkType.MACH_O_BUNDLE) {
      argsBuilder.add("-bundle");
      // It's possible to build a Mach-O bundle without a bundle loader (logic tests, for example).
      if (bundleLoader.isPresent()) {
        argsBuilder.add("-bundle_loader", resolver.getPath(bundleLoader.get()).toString());
      }
    }
    if (soname.isPresent()) {
      argsBuilder.addAll(linker.soname(soname.get()));
    }

    // Add all the top-level inputs.  We wrap these in the --whole-archive since any top-level
    // inputs, even if archives, should be fully linked in.
    for (SourcePath input : inputs) {
      argsBuilder.addAll(linker.linkWhole(resolver.getPath(input).toString()));
    }

    // Add all arguments from our dependencies.
    argsBuilder.addAll(linkableInput.getArgs());

    // Add all arguments needed to link in the C/C++ platform runtime.
    Linker.LinkableDepType runtimeDepType = depType;
    if (cxxRuntimeType.or(Linker.CxxRuntimeType.DYNAMIC) == Linker.CxxRuntimeType.STATIC) {
      runtimeDepType = Linker.LinkableDepType.STATIC;
    }
    argsBuilder.addAll(cxxPlatform.getRuntimeLdflags().get(runtimeDepType));

    ImmutableList<String> args = argsBuilder.build();

    // Build the C/C++ link step.
    return new CxxLink(
        linkParams,
        resolver,
        cxxPlatform.getLd(),
        output,
        allInputs,
        args,
        CxxDescriptionEnhancer.getFrameworkSearchPaths(
            Optional.of(ImmutableSortedSet.copyOf(linkableInput.getFrameworks())),
            cxxPlatform,
            resolver),
        CxxDescriptionEnhancer.getFrameworkSearchPaths(
            Optional.of(ImmutableSortedSet.copyOf(linkableInput.getLibraries())),
            cxxPlatform,
            resolver),
        cxxPlatform.getDebugPathSanitizer());
  }

}
