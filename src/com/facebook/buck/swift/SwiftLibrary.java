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

import static com.facebook.buck.swift.SwiftLibraryDescription.SWIFT_COMPILE_FLAVOR;
import static com.facebook.buck.swift.SwiftLibraryDescription.SWIFT_LIBRARY_FLAVOR;

import com.facebook.buck.apple.AppleCxxPlatform;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
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
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.MoreStrings;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An action graph representation of a Swift library from the target graph, providing the
 * various interfaces to make it consumable by C/C native linkable rules.
 */
class SwiftLibrary
    extends NoopBuildRule
    implements HasRuntimeDeps, NativeLinkable, CxxPreprocessorDep {

  private static final Logger LOG = Logger.get(SwiftLibrary.class);

  private final LoadingCache<
      CxxPreprocessables.CxxPreprocessorInputCacheKey,
      ImmutableMap<BuildTarget, CxxPreprocessorInput>
      > transitiveCxxPreprocessorInputCache =
      CxxPreprocessables.getTransitiveCxxPreprocessorInputCache(this);

  private final BuildRuleResolver ruleResolver;

  private final Iterable<? extends BuildRule> exportedDeps;
  private final ImmutableSet<FrameworkPath> frameworks;
  private final ImmutableSet<FrameworkPath> libraries;
  private final FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain;
  private final Optional<Pattern> supportedPlatformsRegex;

  SwiftLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      final SourcePathResolver pathResolver,
      Iterable<? extends BuildRule> exportedDeps,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain,
      Optional<Pattern> supportedPlatformsRegex) {
    super(params, pathResolver);
    this.ruleResolver = ruleResolver;
    this.exportedDeps = exportedDeps;
    this.frameworks = frameworks;
    this.libraries = libraries;
    this.appleCxxPlatformFlavorDomain = appleCxxPlatformFlavorDomain;
    this.supportedPlatformsRegex = supportedPlatformsRegex;
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
    SwiftCompile rule = requireSwiftCompileRule(cxxPlatform.getFlavor());
    LOG.debug("Required rule: %s", rule);

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

    BuildTarget ruleTarget = rule.getBuildTarget();
    inputBuilder
        .addAllArgs(StringArg.from("-Xlinker", "-add_ast_path"))
        .addArgs(
            new SourcePathArg(
                getResolver(),
                new BuildTargetSourcePath(ruleTarget, rule.getModulePath())))
        .addArgs(
          new SourcePathArg(
              getResolver(),
              new BuildTargetSourcePath(ruleTarget, rule.getObjectPath())));
    if (sharedRequested) {
      ImmutableMap<String, SourcePath> sharedLibs = getSharedLibraries(cxxPlatform);
      inputBuilder.addAllArgs(
          FluentIterable.from(sharedLibs.entrySet())
              .transformAndConcat(new Function<Map.Entry<String, SourcePath>, Iterable<Arg>>() {
                @Override
                public Iterable<Arg> apply(Map.Entry<String, SourcePath> input) {
                  return StringArg.from(
                      "-L",
                      getResolver().getAbsolutePath(input.getValue()).toString(),
                      "-l",
                      MoreStrings.stripPrefix(
                          Files.getNameWithoutExtension(input.getKey()), "lib").get());
                }
              }));
    }
    inputBuilder.addAllFrameworks(frameworks);
    inputBuilder.addAllLibraries(libraries);
    return inputBuilder.build();
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, SourcePath> libs = ImmutableMap.builder();
    String sharedLibrarySoname = CxxDescriptionEnhancer.getSharedLibrarySoname(
        Optional.<String>absent(),
        getBuildTarget(),
        cxxPlatform);
    BuildRule sharedLibraryBuildRule = requireSwiftCompileRule(cxxPlatform.getFlavor());
    libs.put(
        sharedLibrarySoname,
        new BuildTargetSourcePath(sharedLibraryBuildRule.getBuildTarget()));
    return libs.build();
  }

  SwiftCompile requireSwiftCompileRule(Flavor... flavors)
      throws NoSuchBuildTargetException {
    BuildTarget requiredBuildTarget = getBuildTarget()
        .withAppendedFlavors(flavors)
        .withoutFlavors(ImmutableSet.of(SWIFT_LIBRARY_FLAVOR))
        .withAppendedFlavors(SWIFT_COMPILE_FLAVOR);
    BuildRule rule = ruleResolver.requireRule(requiredBuildTarget);
    if (!(rule instanceof SwiftCompile)) {
      throw new RuntimeException(
          String.format("Could not find SwiftCompile with target %s", requiredBuildTarget));
    }
    return (SwiftCompile) rule;
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    if (getBuildTarget().getFlavors().contains(SWIFT_LIBRARY_FLAVOR)) {
      return Linkage.STATIC;
    } else {
      return Linkage.ANY;
    }
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
    if (!isPlatformSupported(cxxPlatform)) {
      return CxxPreprocessorInput.EMPTY;
    }

    BuildRule rule = requireSwiftCompileRule(cxxPlatform.getFlavor());

    return CxxPreprocessorInput.builder()
        .addIncludes(
            CxxHeadersDir.of(
                CxxPreprocessables.IncludeType.LOCAL,
                new BuildTargetSourcePath(rule.getBuildTarget())))
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
