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

import static com.facebook.buck.swift.SwiftLibraryDescription.SWIFT_COMPANION_FLAVOR;
import static com.facebook.buck.swift.SwiftLibraryDescription.SWIFT_COMPILE_FLAVOR;

import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.CxxBridgingHeaders;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxHeadersDir;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.TransitiveCxxPreprocessorInputCache;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * An action graph representation of a Swift library from the target graph, providing the various
 * interfaces to make it consumable by C/C native linkable rules.
 */
class SwiftLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements HasRuntimeDeps, NativeLinkable, CxxPreprocessorDep {

  private final TransitiveCxxPreprocessorInputCache transitiveCxxPreprocessorInputCache =
      new TransitiveCxxPreprocessorInputCache(this);

  private final ActionGraphBuilder graphBuilder;

  private final Collection<? extends BuildRule> exportedDeps;
  private final Optional<SourcePath> bridgingHeader;
  private final ImmutableSet<FrameworkPath> frameworks;
  private final ImmutableSet<FrameworkPath> libraries;
  private final FlavorDomain<SwiftPlatform> swiftPlatformFlavorDomain;
  private final Optional<Pattern> supportedPlatformsRegex;
  private final Linkage linkage;

  SwiftLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      Collection<? extends BuildRule> exportedDeps,
      FlavorDomain<SwiftPlatform> swiftPlatformFlavorDomain,
      Optional<SourcePath> bridgingHeader,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries,
      Optional<Pattern> supportedPlatformsRegex,
      Linkage linkage) {
    super(buildTarget, projectFilesystem, params);
    this.graphBuilder = graphBuilder;
    this.exportedDeps = exportedDeps;
    this.bridgingHeader = bridgingHeader;
    this.frameworks = frameworks;
    this.libraries = libraries;
    this.swiftPlatformFlavorDomain = swiftPlatformFlavorDomain;
    this.supportedPlatformsRegex = supportedPlatformsRegex;
    this.linkage = linkage;
  }

  private boolean isPlatformSupported(CxxPlatform cxxPlatform) {
    return !supportedPlatformsRegex.isPresent()
        || supportedPlatformsRegex.get().matcher(cxxPlatform.getFlavor().toString()).find();
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDeps(BuildRuleResolver ruleResolver) {
    // TODO(beng, markwang): Use pseudo targets to represent the Swift
    // runtime library's linker args here so NativeLinkables can
    // deduplicate the linker flags on the build target (which would be the same for
    // all libraries).
    return RichStream.from(getDeclaredDeps())
        .filter(NativeLinkable.class)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(
      BuildRuleResolver ruleResolver) {
    throw new RuntimeException(
        "SwiftLibrary does not support getting linkable exported deps "
            + "without a specific platform.");
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDepsForPlatform(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    SwiftRuntimeNativeLinkable swiftRuntimeNativeLinkable =
        new SwiftRuntimeNativeLinkable(swiftPlatformFlavorDomain.getValue(cxxPlatform.getFlavor()));
    return RichStream.from(exportedDeps)
        .filter(NativeLinkable.class)
        .concat(RichStream.of(swiftRuntimeNativeLinkable))
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ImmutableSet<LanguageExtensions> languageExtensions,
      ActionGraphBuilder graphBuilder) {
    SwiftCompile rule = requireSwiftCompileRule(cxxPlatform.getFlavor());
    NativeLinkableInput.Builder inputBuilder = NativeLinkableInput.builder();
    inputBuilder
        .addAllArgs(rule.getAstLinkArgs())
        .addAllFrameworks(frameworks)
        .addAllLibraries(libraries);
    boolean isDynamic;
    Linkage preferredLinkage = getPreferredLinkage(cxxPlatform, graphBuilder);
    switch (preferredLinkage) {
      case STATIC:
        isDynamic = false;
        break;
      case SHARED:
        isDynamic = true;
        break;
      case ANY:
        isDynamic = type == Linker.LinkableDepType.SHARED;
        break;
      default:
        throw new IllegalStateException("unhandled linkage type: " + preferredLinkage);
    }

    if (isDynamic) {
      CxxLink swiftLinkRule = requireSwiftLinkRule(cxxPlatform.getFlavor());
      inputBuilder.addArgs(
          FileListableLinkerInputArg.withSourcePathArg(
              SourcePathArg.of(swiftLinkRule.getSourcePathToOutput())));
    } else {
      inputBuilder.addAllArgs(rule.getFileListLinkArg());
    }
    return inputBuilder.build();
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, SourcePath> libs = ImmutableMap.builder();
    BuildRule sharedLibraryBuildRule = requireSwiftLinkRule(cxxPlatform.getFlavor());
    String sharedLibrarySoname =
        CxxDescriptionEnhancer.getSharedLibrarySoname(
            Optional.empty(), sharedLibraryBuildRule.getBuildTarget(), cxxPlatform);
    libs.put(sharedLibrarySoname, sharedLibraryBuildRule.getSourcePathToOutput());
    return libs.build();
  }

  SwiftCompile requireSwiftCompileRule(Flavor... flavors) {
    BuildTarget requiredBuildTarget =
        getBuildTarget()
            .withAppendedFlavors(flavors)
            .withoutFlavors(ImmutableSet.of(CxxDescriptionEnhancer.SHARED_FLAVOR))
            .withoutFlavors(ImmutableSet.of(SWIFT_COMPANION_FLAVOR))
            .withoutFlavors(LinkerMapMode.FLAVOR_DOMAIN.getFlavors())
            .withAppendedFlavors(SWIFT_COMPILE_FLAVOR);

    // Find the correct rule. Since the SwiftCompile rules are generated by buck itself, any
    // failures in finding the rule is a buck internal error.
    BuildRule rule = graphBuilder.requireRule(requiredBuildTarget);
    try {
      return (SwiftCompile) rule;
    } catch (ClassCastException e) {
      throw new IllegalStateException(
          String.format(
              "Failed to load swift compile rule from swift library meta-rule, "
                  + "the retrieved rule was not a SwiftCompile. Target: %s",
              requiredBuildTarget.toString()),
          e);
    }
  }

  private CxxLink requireSwiftLinkRule(Flavor... flavors) {
    BuildTarget requiredBuildTarget =
        getBuildTarget()
            .withoutFlavors(SWIFT_COMPANION_FLAVOR)
            .withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .withAppendedFlavors(flavors);
    BuildRule rule = graphBuilder.requireRule(requiredBuildTarget);
    if (!(rule instanceof CxxLink)) {
      throw new RuntimeException(
          String.format("Could not find CxxLink with target %s", requiredBuildTarget));
    }
    return (CxxLink) rule;
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    // don't create dylib for swift companion target.
    if (getBuildTarget().getFlavors().contains(SWIFT_COMPANION_FLAVOR)) {
      return Linkage.STATIC;
    } else {
      return linkage;
    }
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    // We export all declared deps as runtime deps, to setup a transitive runtime dep chain which
    // will pull in runtime deps (e.g. other binaries) or transitive C/C++ libraries.  Since the
    // `CxxLibrary` rules themselves are noop meta rules, they shouldn't add any unnecessary
    // overhead.
    return Stream.concat(
            getDeclaredDeps().stream(), StreamSupport.stream(exportedDeps.spliterator(), false))
        .map(BuildRule::getBuildTarget);
  }

  @Override
  public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return getBuildDeps()
        .stream()
        .filter(CxxPreprocessorDep.class::isInstance)
        .map(CxxPreprocessorDep.class::cast)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    if (!isPlatformSupported(cxxPlatform)) {
      return CxxPreprocessorInput.of();
    }

    BuildRule rule = requireSwiftCompileRule(cxxPlatform.getFlavor());

    CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();
    builder.addIncludes(
        CxxHeadersDir.of(CxxPreprocessables.IncludeType.LOCAL, rule.getSourcePathToOutput()));
    if (bridgingHeader.isPresent()) {
      builder.addIncludes(CxxBridgingHeaders.from(bridgingHeader.get()));
    }
    return builder.build();
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    if (getBuildTarget().getFlavors().contains(SWIFT_COMPANION_FLAVOR)) {
      return ImmutableMap.of(getBuildTarget(), getCxxPreprocessorInput(cxxPlatform, graphBuilder));
    } else {
      return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform, graphBuilder);
    }
  }
}
