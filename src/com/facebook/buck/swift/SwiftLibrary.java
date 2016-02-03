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

import com.facebook.buck.apple.AppleCxxPlatform;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Set;

/**
 * An action graph representation of a Swift library from the target graph, providing the
 * various interfaces to make it consumable by C/C native linkable rules.
 */
public class SwiftLibrary
    extends NoopBuildRule
    implements HasRuntimeDeps, NativeLinkable {

  private static final Logger LOG = Logger.get(SwiftLibrary.class);

  private final BuildRuleResolver ruleResolver;
  private final Iterable<? extends BuildRule> exportedDeps;
  private final ImmutableSet<FrameworkPath> frameworks;
  private final ImmutableSet<FrameworkPath> libraries;
  private final ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms;

  public SwiftLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      Iterable<? extends BuildRule> exportedDeps,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries,
      ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms) {
    super(params, pathResolver);
    this.ruleResolver = ruleResolver;
    this.exportedDeps = exportedDeps;
    this.frameworks = frameworks;
    this.libraries = libraries;
    this.platformFlavorsToAppleCxxPlatforms = platformFlavorsToAppleCxxPlatforms;
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
    return FluentIterable.from(exportedDeps)
        .filter(NativeLinkable.class);
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type) throws NoSuchBuildTargetException {
    SwiftCompile rule = requireSwiftCompileRule(cxxPlatform.getFlavor());
    LOG.debug("Required rule: %s", rule);

    NativeLinkableInput.Builder inputBuilder = NativeLinkableInput.builder();

    // Add linker flags.

    AppleCxxPlatform appleCxxPlatform = platformFlavorsToAppleCxxPlatforms.get(
        cxxPlatform.getFlavor());
    Preconditions.checkState(appleCxxPlatform != null);

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
              "-force_load_swift_libs",
              "-lswiftRuntime"));
    }
    for (Path swiftRuntimePath : swiftRuntimePaths) {
      inputBuilder.addAllArgs(StringArg.from("-L", swiftRuntimePath.toString()));
    }
    inputBuilder.addAllArgs(
        StringArg.fromWithDeps(
            ImmutableList.of(
                "-Xlinker",
                "-add_ast_path",
                rule.getModulePath().toString(),
                rule.getObjectPath().toString()),
            ImmutableList.<BuildRule>of(rule)));
    inputBuilder.addAllFrameworks(frameworks);
    inputBuilder.addAllLibraries(libraries);
    return inputBuilder.build();
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
    return ImmutableMap.of();
  }

  public SwiftCompile requireSwiftCompileRule(Flavor... flavors)
      throws NoSuchBuildTargetException {
    BuildTarget requiredBuildTarget =
        BuildTarget.builder(getBuildTarget())
            .addFlavors(flavors)
            .build();
    BuildRule rule = ruleResolver.requireRule(requiredBuildTarget);
    if (!(rule instanceof SwiftCompile)) {
      throw new RuntimeException(
          String.format("Could not find SwiftCompile with target %s", requiredBuildTarget));
    }
    return (SwiftCompile) rule;
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

}
