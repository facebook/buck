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
import com.facebook.buck.apple.ApplePlatforms;
import com.facebook.buck.apple.MultiarchFileInfo;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class SwiftLibraryDescription implements
    Description<SwiftLibraryDescription.Arg>,
    Flavored {
  public static final BuildRuleType TYPE = BuildRuleType.of("swift_library");

  static final Flavor SWIFT_LIBRARY_FLAVOR = ImmutableFlavor.of("swift-lib");
  static final Flavor SWIFT_COMPILE_FLAVOR = ImmutableFlavor.of("swift-compile");

  private static final Set<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      SWIFT_LIBRARY_FLAVOR,
      SWIFT_COMPILE_FLAVOR);

  private static final Predicate<Flavor> IS_SUPPORTED_FLAVOR = new Predicate<Flavor>() {
    @Override
    public boolean apply(Flavor flavor) {
      return SUPPORTED_FLAVORS.contains(flavor);
    }
  };

  private final CxxBuckConfig cxxBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain;
  private final FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain;
  private final CxxPlatform defaultCxxPlatform;

  public SwiftLibraryDescription(
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain,
      CxxPlatform defaultCxxPlatform) {
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatformFlavorDomain = cxxPlatformFlavorDomain;
    this.appleCxxPlatformFlavorDomain = appleCxxPlatformFlavorDomain;
    this.defaultCxxPlatform = defaultCxxPlatform;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public SwiftLibraryDescription.Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    ImmutableSet<Flavor> currentUnsupportedFlavors = ImmutableSet.copyOf(Sets.filter(
        flavors, Predicates.not(IS_SUPPORTED_FLAVOR)));
    if (currentUnsupportedFlavors.isEmpty()) {
      return true;
    }
    return cxxPlatformFlavorDomain.containsAnyOf(flavors);
  }

  @Override
  public <A extends SwiftLibraryDescription.Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    final BuildTarget buildTarget = params.getBuildTarget();
    Optional<Map.Entry<Flavor, CxxPlatform>> platform = cxxPlatformFlavorDomain.getFlavorAndValue(
        buildTarget);
    final ImmutableSortedSet<Flavor> buildFlavors = buildTarget.getFlavors();

    if (buildFlavors.contains(SWIFT_COMPILE_FLAVOR) ||
        !buildFlavors.contains(SWIFT_LIBRARY_FLAVOR) && platform.isPresent()) {
      AppleCxxPlatform appleCxxPlatform = ApplePlatforms.getAppleCxxPlatformForBuildTarget(
          cxxPlatformFlavorDomain,
          defaultCxxPlatform,
          appleCxxPlatformFlavorDomain,
          buildTarget,
          Optional.<MultiarchFileInfo>absent());
      Optional<Tool> swiftCompiler = appleCxxPlatform.getSwift();
      if (!swiftCompiler.isPresent()) {
        throw new HumanReadableException("Platform %s is missing swift compiler", appleCxxPlatform);
      }

      // All swift-compile rules of swift-lib deps are required since we need their swiftmodules
      // during compilation.
      params = params.appendExtraDeps(
          FluentIterable.from(params.getDeps())
              .filter(SwiftLibrary.class)
              .transform(new Function<SwiftLibrary, BuildRule>() {
                @Override
                public BuildRule apply(SwiftLibrary input) {
                  try {
                    return input.requireSwiftCompileRule(
                        Iterables.toArray(buildFlavors, Flavor.class));
                  } catch (NoSuchBuildTargetException e) {
                    throw new HumanReadableException(e,
                        "Could not find SwiftCompile with target %s", buildTarget);
                  }
                }
              })
              .toSortedSet(Ordering.natural()));

      final Linker.LinkType linkType = Linker.LinkType.SHARED;
      SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
      String moduleName = args.moduleName.or(buildTarget.getShortName());
      CxxPlatform cxxPlatform = platform.get().getValue();
      Path sharedLibOutput = CxxDescriptionEnhancer.getSharedLibraryPath(
          params.getProjectFilesystem(),
          buildTarget,
          CxxDescriptionEnhancer.getSharedLibrarySoname(
              Optional.<String>absent(),
              buildTarget.withoutFlavors(SUPPORTED_FLAVORS),
              cxxPlatform));

      CxxLink cxxLink = CxxLinkableEnhancer.createCxxLinkableBuildRule(
          cxxBuckConfig,
          cxxPlatform,
          params,
          resolver,
          sourcePathResolver,
          buildTarget,
          linkType,
          Optional.of(moduleName),
          sharedLibOutput,
          Linker.LinkableDepType.SHARED,
          Iterables.filter(params.getDeps(), NativeLinkable.class),
          Optional.<Linker.CxxRuntimeType>absent(),
          Optional.<SourcePath>absent(),
          ImmutableSet.<BuildTarget>of(),
          NativeLinkableInput.of());

      return new SwiftCompile(
          cxxLink,
          cxxPlatform,
          params,
          new SourcePathResolver(resolver),
          swiftCompiler.get(),
          moduleName,
          BuildTargets.getGenPath(
              params.getProjectFilesystem(),
              buildTarget, "%s"),
          args.srcs.get(),
          Optional.<Boolean>absent(),
          args.bridgingHeader);
    }

    // Otherwise, we return the generic placeholder of this library.
    return new SwiftLibrary(
        params,
        resolver,
        new SourcePathResolver(resolver),
        ImmutableSet.<BuildRule>of(),
        args.frameworks.get(),
        args.libraries.get(),
        appleCxxPlatformFlavorDomain,
        args.supportedPlatformsRegex);
  }

  public <A extends CxxLibraryDescription.Arg> Optional<BuildRule> createCompanionBuildRule(
      final TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    BuildTarget buildTarget = params.getBuildTarget();
    if (!isSwiftTarget(buildTarget)) {
      boolean hasSwiftSource = !SwiftDescriptions.filterSwiftSources(
          new SourcePathResolver(resolver),
          args.srcs.get()).isEmpty();
      return hasSwiftSource ?
          Optional.of(resolver.requireRule(buildTarget.withAppendedFlavors(SWIFT_LIBRARY_FLAVOR)))
          : Optional.<BuildRule>absent();
    }

    final SwiftLibraryDescription.Arg delegateArgs = createUnpopulatedConstructorArg();
    SwiftDescriptions.populateSwiftLibraryDescriptionArg(
        new SourcePathResolver(resolver),
        delegateArgs,
        args,
        buildTarget);
    if (delegateArgs.srcs.isPresent() && !delegateArgs.srcs.get().isEmpty()) {
      return Optional.of(
          resolver.addToIndex(
              createBuildRule(targetGraph, params, resolver, delegateArgs)));
    } else {
      return Optional.absent();
    }
  }

  public static boolean isSwiftTarget(BuildTarget buildTarget) {
    return buildTarget.getFlavors().contains(SWIFT_LIBRARY_FLAVOR) ||
        buildTarget.getFlavors().contains(SWIFT_COMPILE_FLAVOR);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public Optional<String> moduleName;
    public Optional<ImmutableSortedSet<SourcePath>> srcs;
    public Optional<ImmutableList<String>> compilerFlags;
    public Optional<ImmutableSortedSet<FrameworkPath>> frameworks;
    public Optional<ImmutableSortedSet<FrameworkPath>> libraries;
    public Optional<Boolean> enableObjcInterop;
    public Optional<Pattern> supportedPlatformsRegex;
    public Optional<SourcePath> bridgingHeader;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }

}
