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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableCacheKey;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.concurrent.Parallelizer;
import com.facebook.buck.util.function.QuadFunction;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * An action graph representation of a C/C++ library from the target graph, providing the various
 * interfaces to make it consumable by C/C++ preprocessing and native linkable rules.
 */
public class CxxLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements AbstractCxxLibrary, HasRuntimeDeps, NativeTestable, NativeLinkTarget {

  private final CxxDeps deps;
  private final CxxDeps exportedDeps;
  private final Predicate<CxxPlatform> headerOnly;
  private final BiFunction<? super CxxPlatform, ActionGraphBuilder, Iterable<? extends Arg>>
      exportedLinkerFlags;
  private final BiFunction<? super CxxPlatform, ActionGraphBuilder, Iterable<? extends Arg>>
      postExportedLinkerFlags;
  private final QuadFunction<
          ? super CxxPlatform,
          ActionGraphBuilder,
          SourcePathResolver,
          SourcePathRuleFinder,
          NativeLinkableInput>
      linkTargetInput;
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

  private final Cache<NativeLinkableCacheKey, NativeLinkableInput> nativeLinkableCache =
      CacheBuilder.newBuilder().build();

  private final TransitiveCxxPreprocessorInputCache transitiveCxxPreprocessorInputCache;

  public CxxLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Parallelizer preprocessorInputCacheParallelizer,
      CxxDeps deps,
      CxxDeps exportedDeps,
      Predicate<CxxPlatform> headerOnly,
      BiFunction<? super CxxPlatform, ActionGraphBuilder, Iterable<? extends Arg>>
          exportedLinkerFlags,
      BiFunction<? super CxxPlatform, ActionGraphBuilder, Iterable<? extends Arg>>
          postExportedLinkerFlags,
      QuadFunction<
              ? super CxxPlatform,
              ActionGraphBuilder,
              SourcePathResolver,
              SourcePathRuleFinder,
              NativeLinkableInput>
          linkTargetInput,
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
    this.deps = deps;
    this.exportedDeps = exportedDeps;
    this.headerOnly = headerOnly;
    this.exportedLinkerFlags = exportedLinkerFlags;
    this.postExportedLinkerFlags = postExportedLinkerFlags;
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
        new TransitiveCxxPreprocessorInputCache(this, preprocessorInputCacheParallelizer);
  }

  private boolean isPlatformSupported(CxxPlatform cxxPlatform) {
    return !supportedPlatformsRegex.isPresent()
        || supportedPlatformsRegex.get().matcher(cxxPlatform.getFlavor().toString()).find();
  }

  @Override
  public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
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
      CxxPlatform cxxPlatform, HeaderVisibility headerVisibility, ActionGraphBuilder graphBuilder) {
    // Handle via metadata query.
    return CxxLibraryDescription.queryMetadataCxxPreprocessorInput(
            graphBuilder, getBuildTarget(), cxxPlatform, headerVisibility)
        .orElseThrow(IllegalStateException::new);
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    CxxPreprocessorInput publicHeaders =
        getPublicCxxPreprocessorInputExcludingDelegate(cxxPlatform, graphBuilder);
    Optional<CxxPreprocessorInput> pluginHeaders =
        delegate.flatMap(p -> p.getPreprocessorInput(getBuildTarget(), graphBuilder, cxxPlatform));

    if (pluginHeaders.isPresent()) {
      return CxxPreprocessorInput.concat(ImmutableList.of(publicHeaders, pluginHeaders.get()));
    }

    return publicHeaders;
  }

  /**
   * Returns public headers excluding contribution from any {@link CxxLibraryDescriptionDelegate}.
   */
  public CxxPreprocessorInput getPublicCxxPreprocessorInputExcludingDelegate(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PUBLIC, graphBuilder);
  }

  @Override
  public CxxPreprocessorInput getPrivateCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    CxxPreprocessorInput privateInput =
        getCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PRIVATE, graphBuilder);
    Optional<CxxPreprocessorInput> delegateInput =
        delegate.flatMap(
            p -> p.getPrivatePreprocessorInput(getBuildTarget(), graphBuilder, cxxPlatform));

    if (delegateInput.isPresent()) {
      return CxxPreprocessorInput.concat(ImmutableList.of(privateInput, delegateInput.get()));
    }

    return privateInput;
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform, graphBuilder);
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDeps(BuildRuleResolver ruleResolver) {
    if (!propagateLinkables) {
      return ImmutableList.of();
    }
    return RichStream.from(deps.getForAllPlatforms(ruleResolver))
        .filter(NativeLinkable.class)
        .toImmutableList();
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDepsForPlatform(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
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
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(
      BuildRuleResolver ruleResolver) {
    if (!propagateLinkables) {
      return ImmutableList.of();
    }
    return RichStream.from(exportedDeps.getForAllPlatforms(ruleResolver))
        .filter(NativeLinkable.class)
        .toImmutableList();
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDepsForPlatform(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    if (!propagateLinkables) {
      return ImmutableList.of();
    }
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }

    ImmutableList<NativeLinkable> delegateLinkables =
        delegate
            .flatMap(
                d -> d.getNativeLinkableExportedDeps(getBuildTarget(), graphBuilder, cxxPlatform))
            .orElse(ImmutableList.of());

    return RichStream.from(exportedDeps.get(graphBuilder, cxxPlatform))
        .filter(NativeLinkable.class)
        .concat(RichStream.from(delegateLinkables))
        .toImmutableList();
  }

  private NativeLinkableInput computeNativeLinkableInputUncached(
      NativeLinkableCacheKey key, ActionGraphBuilder graphBuilder) {
    CxxPlatform cxxPlatform = key.getCxxPlatform();

    if (!isPlatformSupported(cxxPlatform)) {
      return NativeLinkableInput.of();
    }

    Linker.LinkableDepType type = key.getType();
    boolean forceLinkWhole = key.getForceLinkWhole();

    // Build up the arguments used to link this library.  If we're linking the
    // whole archive, wrap the library argument in the necessary "ld" flags.
    ImmutableList.Builder<Arg> linkerArgsBuilder = ImmutableList.builder();
    linkerArgsBuilder.addAll(
        Preconditions.checkNotNull(exportedLinkerFlags.apply(cxxPlatform, graphBuilder)));

    boolean delegateWantsArtifact =
        delegate
            .map(d -> d.getShouldProduceLibraryArtifact(getBuildTarget(), graphBuilder))
            .orElse(false);
    boolean headersOnly = headerOnly.test(cxxPlatform);
    boolean shouldProduceArtifact = (!headersOnly || delegateWantsArtifact) && propagateLinkables;

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
                    graphBuilder,
                    cxxPlatform.getFlavor(),
                    type == Linker.LinkableDepType.STATIC
                        ? CxxDescriptionEnhancer.STATIC_FLAVOR
                        : CxxDescriptionEnhancer.STATIC_PIC_FLAVOR);
        if (linkWhole || forceLinkWhole) {
          SourcePathResolver pathResolver =
              DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
          Linker linker = cxxPlatform.getLd().resolve(graphBuilder);
          linkerArgsBuilder.addAll(linker.linkWhole(archive.toArg(), pathResolver));
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
                graphBuilder,
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

    // Add the postExportedLinkerFlags.
    linkerArgsBuilder.addAll(
        Preconditions.checkNotNull(postExportedLinkerFlags.apply(cxxPlatform, graphBuilder)));

    ImmutableList<Arg> linkerArgs = linkerArgsBuilder.build();

    return NativeLinkableInput.of(
        linkerArgs, Preconditions.checkNotNull(frameworks), Preconditions.checkNotNull(libraries));
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ActionGraphBuilder graphBuilder) {
    NativeLinkableCacheKey key =
        NativeLinkableCacheKey.of(cxxPlatform.getFlavor(), type, forceLinkWhole, cxxPlatform);
    try {
      return nativeLinkableCache.get(
          key, () -> computeNativeLinkableInputUncached(key, graphBuilder));
    } catch (ExecutionException e) {
      throw new UncheckedExecutionException(e.getCause());
    }
  }

  /** Require a flavored version of this build rule */
  public BuildRule requireBuildRule(ActionGraphBuilder graphBuilder, Flavor... flavors) {
    return graphBuilder.requireRule(getBuildTarget().withAppendedFlavors(flavors));
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return linkage;
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables(BuildRuleResolver ruleResolver) {
    // Both iterables we are concatting are ImmutableSets, so the returned iterator does not support
    // remove
    return AndroidPackageableCollector.getPackageableRules(
        Iterables.concat(
            deps.getForAllPlatforms(ruleResolver), exportedDeps.getForAllPlatforms(ruleResolver)));
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
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
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
        requireBuildRule(
            graphBuilder, cxxPlatform.getFlavor(), CxxDescriptionEnhancer.SHARED_FLAVOR);
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
  public Iterable<? extends NativeLinkable> getNativeLinkTargetDeps(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return Iterables.concat(
        getNativeLinkableDepsForPlatform(cxxPlatform, graphBuilder),
        getNativeLinkableExportedDepsForPlatform(cxxPlatform, graphBuilder));
  }

  @Override
  public NativeLinkableInput getNativeLinkTargetInput(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder) {
    if (!isPlatformSupported(cxxPlatform)) {
      return NativeLinkableInput.of();
    }
    return linkTargetInput.apply(cxxPlatform, graphBuilder, pathResolver, ruleFinder);
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
        // Make sure to use the rule ruleFinder that's passed in rather than the field we're
        // holding, since we need to access nodes already created by the previous build rule
        // ruleFinder in the incremental action graph scenario, and {@see #updateBuildRuleResolver}
        // may already have been called.
        .concat(exportedDeps.getForAllPlatforms(ruleFinder.getRuleResolver()).stream())
        .map(BuildRule::getBuildTarget);
  }

  public Iterable<? extends Arg> getExportedLinkerFlags(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return exportedLinkerFlags.apply(cxxPlatform, graphBuilder);
  }

  public Iterable<? extends Arg> getExportedPostLinkerFlags(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return postExportedLinkerFlags.apply(cxxPlatform, graphBuilder);
  }
}
