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

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableCacheKey;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * An action graph representation of a C/C++ library from the target graph, providing the various
 * interfaces to make it consumable by C/C++ preprocessing and native linkable rules.
 */
public class CxxLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements AbstractCxxLibrary, HasRuntimeDeps, NativeTestable, NativeLinkTarget {

  private final BuildRuleResolver ruleResolver;
  private final CxxDeps deps;
  private final CxxDeps exportedDeps;
  private final Predicate<CxxPlatform> headerOnly;
  private final Function<? super CxxPlatform, Iterable<? extends Arg>> exportedLinkerFlags;
  private final Function<? super CxxPlatform, NativeLinkableInput> linkTargetInput;
  private final Optional<Pattern> supportedPlatformsRegex;
  private final ImmutableSet<FrameworkPath> frameworks;
  private final ImmutableSet<FrameworkPath> libraries;
  private final Linkage linkage;
  private final boolean linkWhole;
  private final Optional<String> soname;
  private final ImmutableSortedSet<BuildTarget> tests;
  private final boolean canBeAsset;
  private final boolean reexportAllHeaderDependencies;
  private final Optional<CxxLibraryDescriptionDelegate> delegate;
  /**
   * Whether Native Linkable dependencies should be propagated for the purpose of computing objects
   * to link at link time. Setting this to false makes this library invisible to linking, so it and
   * its link-time dependencies are ignored.
   */
  private final boolean propagateLinkables;

  private final LoadingCache<NativeLinkableCacheKey, NativeLinkableInput> nativeLinkableCache =
      NativeLinkable.getNativeLinkableInputCache(this::getNativeLinkableInputUncached);

  private final LoadingCache<CxxPlatform, ImmutableMap<BuildTarget, CxxPreprocessorInput>>
      transitiveCxxPreprocessorInputCache;

  public CxxLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      CxxDeps deps,
      CxxDeps exportedDeps,
      Predicate<CxxPlatform> headerOnly,
      Function<? super CxxPlatform, Iterable<? extends Arg>> exportedLinkerFlags,
      Function<? super CxxPlatform, NativeLinkableInput> linkTargetInput,
      Optional<Pattern> supportedPlatformsRegex,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries,
      Linkage linkage,
      boolean linkWhole,
      Optional<String> soname,
      ImmutableSortedSet<BuildTarget> tests,
      boolean canBeAsset,
      boolean propagateLinkables,
      boolean reexportAllHeaderDependencies,
      Optional<CxxLibraryDescriptionDelegate> delegate) {
    super(buildTarget, projectFilesystem, params);
    this.ruleResolver = ruleResolver;
    this.deps = deps;
    this.exportedDeps = exportedDeps;
    this.headerOnly = headerOnly;
    this.exportedLinkerFlags = exportedLinkerFlags;
    this.linkTargetInput = linkTargetInput;
    this.supportedPlatformsRegex = supportedPlatformsRegex;
    this.frameworks = frameworks;
    this.libraries = libraries;
    this.linkage = linkage;
    this.linkWhole = linkWhole;
    this.soname = soname;
    this.tests = tests;
    this.canBeAsset = canBeAsset;
    this.propagateLinkables = propagateLinkables;
    this.reexportAllHeaderDependencies = reexportAllHeaderDependencies;
    this.delegate = delegate;
    this.transitiveCxxPreprocessorInputCache =
        CxxPreprocessables.getTransitiveCxxPreprocessorInputCache(
            this, ruleResolver.getParallelizer());
  }

  private boolean isPlatformSupported(CxxPlatform cxxPlatform) {
    return !supportedPlatformsRegex.isPresent()
        || supportedPlatformsRegex.get().matcher(cxxPlatform.getFlavor().toString()).find();
  }

  @Override
  public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(CxxPlatform cxxPlatform) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return RichStream.from(exportedDeps.get(ruleResolver, cxxPlatform))
        .concat(
            this.reexportAllHeaderDependencies
                ? RichStream.from(deps.get(ruleResolver, cxxPlatform))
                : Stream.empty())
        .filter(CxxPreprocessorDep.class)
        .toImmutableList();
  }

  private CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform, HeaderVisibility headerVisibility) {
    // Handle via metadata query.
    return CxxLibraryDescription.queryMetadataCxxPreprocessorInput(
            ruleResolver, getBuildTarget(), cxxPlatform, headerVisibility)
        .orElseThrow(IllegalStateException::new);
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(CxxPlatform cxxPlatform) {
    CxxPreprocessorInput publicHeaders =
        getPublicCxxPreprocessorInputExcludingDelegate(cxxPlatform);
    Optional<CxxPreprocessorInput> pluginHeaders =
        delegate.flatMap(p -> p.getPreprocessorInput(getBuildTarget(), ruleResolver, cxxPlatform));

    if (pluginHeaders.isPresent()) {
      return CxxPreprocessorInput.concat(ImmutableList.of(publicHeaders, pluginHeaders.get()));
    }

    return publicHeaders;
  }

  /**
   * Returns public headers excluding contribution from any {@link CxxLibraryDescriptionDelegate}.
   */
  public CxxPreprocessorInput getPublicCxxPreprocessorInputExcludingDelegate(
      CxxPlatform cxxPlatform) {
    return getCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PUBLIC);
  }

  @Override
  public CxxPreprocessorInput getPrivateCxxPreprocessorInput(CxxPlatform cxxPlatform) {
    CxxPreprocessorInput privateInput =
        getCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PRIVATE);
    Optional<CxxPreprocessorInput> delegateInput =
        delegate.flatMap(
            p -> p.getPrivatePreprocessorInput(getBuildTarget(), ruleResolver, cxxPlatform));

    if (delegateInput.isPresent()) {
      return CxxPreprocessorInput.concat(ImmutableList.of(privateInput, delegateInput.get()));
    }

    return privateInput;
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform) {
    return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform);
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDeps() {
    if (!propagateLinkables) {
      return ImmutableList.of();
    }
    return RichStream.from(deps.getForAllPlatforms(ruleResolver))
        .filter(NativeLinkable.class)
        .toImmutableList();
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDepsForPlatform(CxxPlatform cxxPlatform) {
    if (!propagateLinkables) {
      return ImmutableList.of();
    }
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return RichStream.from(deps.get(ruleResolver, cxxPlatform))
        .filter(NativeLinkable.class)
        .toImmutableList();
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps() {
    if (!propagateLinkables) {
      return ImmutableList.of();
    }
    return RichStream.from(exportedDeps.getForAllPlatforms(ruleResolver))
        .filter(NativeLinkable.class)
        .toImmutableList();
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDepsForPlatform(
      CxxPlatform cxxPlatform) {
    if (!propagateLinkables) {
      return ImmutableList.of();
    }
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }

    ImmutableList<NativeLinkable> delegateLinkables =
        delegate
            .flatMap(
                d -> d.getNativeLinkableExportedDeps(getBuildTarget(), ruleResolver, cxxPlatform))
            .orElse(ImmutableList.of());

    return RichStream.from(exportedDeps.get(ruleResolver, cxxPlatform))
        .filter(NativeLinkable.class)
        .concat(RichStream.from(delegateLinkables))
        .toImmutableList();
  }

  private NativeLinkableInput getNativeLinkableInputUncached(NativeLinkableCacheKey key) {
    CxxPlatform cxxPlatform = key.getCxxPlatform();

    if (!isPlatformSupported(cxxPlatform)) {
      return NativeLinkableInput.of();
    }

    Linker.LinkableDepType type = key.getType();
    boolean forceLinkWhole = key.getForceLinkWhole();

    // Build up the arguments used to link this library.  If we're linking the
    // whole archive, wrap the library argument in the necessary "ld" flags.
    ImmutableList.Builder<Arg> linkerArgsBuilder = ImmutableList.builder();
    linkerArgsBuilder.addAll(Preconditions.checkNotNull(exportedLinkerFlags.apply(cxxPlatform)));

    final boolean delegateWantsArtifact =
        delegate
            .map(
                d ->
                    d.getShouldProduceLibraryArtifact(
                        getBuildTarget(), ruleResolver, cxxPlatform, type, forceLinkWhole))
            .orElse(false);
    final boolean headersOnly = headerOnly.test(cxxPlatform);
    final boolean shouldProduceArtifact =
        (!headersOnly || delegateWantsArtifact) && propagateLinkables;

    Preconditions.checkState(
        shouldProduceArtifact || !delegateWantsArtifact,
        "Delegate wants artifact but will not produce one");

    if (shouldProduceArtifact) {
      boolean isStatic;
      switch (linkage) {
        case STATIC:
          isStatic = true;
          break;
        case SHARED:
          isStatic = false;
          break;
        case ANY:
          isStatic = type != Linker.LinkableDepType.SHARED;
          break;
        default:
          throw new IllegalStateException("unhandled linkage type: " + linkage);
      }
      if (isStatic) {
        Archive archive =
            (Archive)
                requireBuildRule(
                    cxxPlatform.getFlavor(),
                    type == Linker.LinkableDepType.STATIC
                        ? CxxDescriptionEnhancer.STATIC_FLAVOR
                        : CxxDescriptionEnhancer.STATIC_PIC_FLAVOR);
        if (linkWhole || forceLinkWhole) {
          Linker linker = cxxPlatform.getLd().resolve(ruleResolver);
          linkerArgsBuilder.addAll(linker.linkWhole(archive.toArg()));
        } else {
          Arg libraryArg = archive.toArg();
          if (libraryArg instanceof SourcePathArg) {
            linkerArgsBuilder.add(
                FileListableLinkerInputArg.withSourcePathArg((SourcePathArg) libraryArg));
          } else {
            linkerArgsBuilder.add(libraryArg);
          }
        }
      } else {
        BuildRule rule =
            requireBuildRule(
                cxxPlatform.getFlavor(),
                cxxPlatform.getSharedLibraryInterfaceParams().isPresent()
                    ? CxxLibraryDescription.Type.SHARED_INTERFACE.getFlavor()
                    : CxxLibraryDescription.Type.SHARED.getFlavor());
        SourcePath sourcePathForLinking =
            rule instanceof CxxLink
                ? ((CxxLink) rule).getSourcePathToOutputForLinking()
                : rule.getSourcePathToOutput();
        linkerArgsBuilder.add(SourcePathArg.of(Preconditions.checkNotNull(sourcePathForLinking)));
      }
    }

    final ImmutableList<Arg> linkerArgs = linkerArgsBuilder.build();

    return NativeLinkableInput.of(
        linkerArgs, Preconditions.checkNotNull(frameworks), Preconditions.checkNotNull(libraries));
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ImmutableSet<LanguageExtensions> languageExtensions) {
    return nativeLinkableCache.getUnchecked(
        NativeLinkableCacheKey.of(cxxPlatform.getFlavor(), type, forceLinkWhole, cxxPlatform));
  }

  public BuildRule requireBuildRule(Flavor... flavors) {
    return ruleResolver.requireRule(getBuildTarget().withAppendedFlavors(flavors));
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return linkage;
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(
        RichStream.from(
                Iterables.concat(
                    deps.getForAllPlatforms(ruleResolver),
                    exportedDeps.getForAllPlatforms(ruleResolver)))
            .toImmutableList());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    if (canBeAsset) {
      collector.addNativeLinkableAsset(this);
    } else {
      collector.addNativeLinkable(this);
    }
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
    if (headerOnly.test(cxxPlatform)) {
      return ImmutableMap.of();
    }
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, SourcePath> libs = ImmutableMap.builder();
    String sharedLibrarySoname =
        CxxDescriptionEnhancer.getSharedLibrarySoname(soname, getBuildTarget(), cxxPlatform);
    BuildRule sharedLibraryBuildRule =
        requireBuildRule(cxxPlatform.getFlavor(), CxxDescriptionEnhancer.SHARED_FLAVOR);
    libs.put(sharedLibrarySoname, sharedLibraryBuildRule.getSourcePathToOutput());
    return libs.build();
  }

  @Override
  public boolean isTestedBy(BuildTarget testTarget) {
    return tests.contains(testTarget);
  }

  @Override
  public NativeLinkTargetMode getNativeLinkTargetMode(CxxPlatform cxxPlatform) {
    return NativeLinkTargetMode.library(
        CxxDescriptionEnhancer.getSharedLibrarySoname(soname, getBuildTarget(), cxxPlatform));
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkTargetDeps(CxxPlatform cxxPlatform) {
    return Iterables.concat(
        getNativeLinkableDepsForPlatform(cxxPlatform),
        getNativeLinkableExportedDepsForPlatform(cxxPlatform));
  }

  @Override
  public NativeLinkableInput getNativeLinkTargetInput(CxxPlatform cxxPlatform) {
    return linkTargetInput.apply(cxxPlatform);
  }

  @Override
  public Optional<Path> getNativeLinkTargetOutputPath(CxxPlatform cxxPlatform) {
    return Optional.empty();
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    // We export all declared deps as runtime deps, to setup a transitive runtime dep chain which
    // will pull in runtime deps (e.g. other binaries) or transitive C/C++ libraries.  Since the
    // `CxxLibrary` rules themselves are noop meta rules, they shouldn't add any unnecessary
    // overhead.
    return RichStream.from(getDeclaredDeps().stream())
        .concat(exportedDeps.getForAllPlatforms(ruleResolver).stream())
        .map(BuildRule::getBuildTarget);
  }

  public Iterable<? extends Arg> getExportedLinkerFlags(CxxPlatform cxxPlatform) {
    return exportedLinkerFlags.apply(cxxPlatform);
  }
}
