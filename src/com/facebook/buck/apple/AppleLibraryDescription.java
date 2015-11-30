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

package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.TypeAndPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.DependencyMode;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Map;
import java.util.Set;

public class AppleLibraryDescription implements
    Description<AppleLibraryDescription.Arg>,
    Flavored,
    MetadataProvidingDescription<AppleLibraryDescription.Arg> {
  public static final BuildRuleType TYPE = BuildRuleType.of("apple_library");

  private static final Set<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      CxxCompilationDatabase.COMPILATION_DATABASE,
      CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR,
      CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
      CxxDescriptionEnhancer.STATIC_FLAVOR,
      CxxDescriptionEnhancer.SHARED_FLAVOR,
      AppleDescriptions.FRAMEWORK_FLAVOR,
      AppleDescriptions.FRAMEWORK_SHALLOW_FLAVOR,
      AppleBundle.DEBUG_INFO_FORMAT_DWARF_AND_DSYM_FLAVOR,
      AppleBundle.DEBUG_INFO_FORMAT_NONE_FLAVOR,
      ImmutableFlavor.of("default"));

  private static final Predicate<Flavor> IS_SUPPORTED_FLAVOR = new Predicate<Flavor>() {
    @Override
    public boolean apply(Flavor flavor) {
      return SUPPORTED_FLAVORS.contains(flavor);
    }
  };

  private enum Type {
    HEADERS,
    EXPORTED_HEADERS,
    SHARED,
    STATIC_PIC,
    STATIC,
    MACH_O_BUNDLE,
    FRAMEWORK {
      @Override
      public boolean isFramework() {
        return true;
      }

      @Override
      public Optional<DependencyMode> getDependencyMode() {
        return Optional.of(DependencyMode.TRANSITIVE);
      }
    },
    FRAMEWORK_SHALLOW {
      @Override
      public boolean isFramework() {
        return true;
      }

      @Override
      public Optional<DependencyMode> getDependencyMode() {
        return Optional.of(DependencyMode.FIRST_ORDER);
      }
    }
    ;

    public boolean isFramework() {
      return false;
    }

    public Optional<DependencyMode> getDependencyMode() {
      return Optional.absent();
    }
  }

  public static final FlavorDomain<Type> LIBRARY_TYPE =
      new FlavorDomain<>(
          "C/C++ Library Type",
          ImmutableMap.<Flavor, Type>builder()
              .put(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR, Type.HEADERS)
              .put(
                  CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
                  Type.EXPORTED_HEADERS)
              .put(CxxDescriptionEnhancer.SHARED_FLAVOR, Type.SHARED)
              .put(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR, Type.STATIC_PIC)
              .put(CxxDescriptionEnhancer.STATIC_FLAVOR, Type.STATIC)
              .put(CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR, Type.MACH_O_BUNDLE)
              .put(AppleDescriptions.FRAMEWORK_FLAVOR, Type.FRAMEWORK)
              .put(AppleDescriptions.FRAMEWORK_SHALLOW_FLAVOR, Type.FRAMEWORK_SHALLOW)
              .build());

  private final CxxLibraryDescription delegate;
  private final FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain;
  private final ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms;
  private final CxxPlatform defaultCxxPlatform;
  private final CodeSignIdentityStore codeSignIdentityStore;
  private final ProvisioningProfileStore provisioningProfileStore;
  private final AppleBundle.DebugInfoFormat debugInfoFormat;

  public AppleLibraryDescription(
      CxxLibraryDescription delegate,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms,
      CxxPlatform defaultCxxPlatform,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      AppleBundle.DebugInfoFormat debugInfoFormat) {
    this.delegate = delegate;
    this.cxxPlatformFlavorDomain = cxxPlatformFlavorDomain;
    this.platformFlavorsToAppleCxxPlatforms = platformFlavorsToAppleCxxPlatforms;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.codeSignIdentityStore = codeSignIdentityStore;
    this.provisioningProfileStore = provisioningProfileStore;
    this.debugInfoFormat = debugInfoFormat;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public AppleLibraryDescription.Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return FluentIterable.from(flavors).allMatch(IS_SUPPORTED_FLAVOR) ||
        delegate.hasFlavors(flavors);
  }

  @Override
  public <A extends AppleLibraryDescription.Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    Optional<Map.Entry<Flavor, Type>> type;
    Optional<AppleBundle.DebugInfoFormat> flavoredDebugInfoFormat;
    try {
      type = LIBRARY_TYPE.getFlavorAndValue(
          ImmutableSet.copyOf(params.getBuildTarget().getFlavors()));
      flavoredDebugInfoFormat = AppleBundle.DEBUG_INFO_FORMAT_FLAVOR_DOMAIN.getValue(
          ImmutableSet.copyOf(params.getBuildTarget().getFlavors()));
    } catch (FlavorDomainException e) {
      throw new HumanReadableException("%s: %s", params.getBuildTarget(), e.getMessage());
    }

    if (type.isPresent() && type.get().getValue().isFramework()) {
      if (!args.infoPlist.isPresent()) {
        throw new HumanReadableException(
            "Cannot create framework for apple_library '%s':\n",
            "No value specified for 'info_plist' attribute.",
            params.getBuildTarget().getUnflavoredBuildTarget());
      }
      Optional<DependencyMode> dependencyMode = type.get().getValue().getDependencyMode();
      if (!dependencyMode.isPresent()) {
        throw new RuntimeException(
            String.format(
                "Invalid framework type '%s', missing dependency mode.",
                type.get().getValue()));
      }

      return AppleDescriptions.createAppleBundle(
          cxxPlatformFlavorDomain,
          defaultCxxPlatform,
          platformFlavorsToAppleCxxPlatforms,
          targetGraph,
          params,
          resolver,
          codeSignIdentityStore,
          provisioningProfileStore,
          params.getBuildTarget(),
          Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.FRAMEWORK),
          Optional.<String>absent(),
          args.infoPlist.get(),
          args.infoPlistSubstitutions,
          args.deps.get(),
          args.getTests(),
          flavoredDebugInfoFormat.or(debugInfoFormat));
    }

    return createBuildRule(
        params,
        resolver,
        args,
        args.linkStyle,
        Optional.<SourcePath>absent(),
        ImmutableSet.<BuildTarget>of());
  }

  public <A extends AppleNativeTargetDescriptionArg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args,
      Optional<Linker.LinkableDepType> linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist) throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    CxxLibraryDescription.Arg delegateArg = delegate.createUnpopulatedConstructorArg();
    TypeAndPlatform typeAndPlatform =
        CxxLibraryDescription.getTypeAndPlatform(
            params.getBuildTarget(),
            cxxPlatformFlavorDomain);
    AppleDescriptions.populateCxxLibraryDescriptionArg(
        pathResolver,
        delegateArg,
        args,
        params.getBuildTarget());

    return delegate.createBuildRule(
        params,
        resolver,
        delegateArg,
        typeAndPlatform,
        linkableDepType,
        bundleLoader,
        blacklist);
  }

  @Override
  public <A extends Arg, U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      A args,
      Class<U> metadataClass) throws NoSuchBuildTargetException {
    if (!metadataClass.isAssignableFrom(FrameworkDependencies.class)) {
      return Optional.absent();
    }
    if (!buildTarget.getFlavors().contains(AppleDescriptions.FRAMEWORK_SHALLOW_FLAVOR)) {
      return Optional.absent();
    }
    Optional<Flavor> cxxPlatformFlavor;
    try {
      cxxPlatformFlavor = cxxPlatformFlavorDomain.getFlavor(buildTarget.getFlavors());
    } catch (FlavorDomainException e) {
      throw new RuntimeException(e);
    }
    Preconditions.checkState(
        cxxPlatformFlavor.isPresent(),
        "Could not find cxx platform in:\n%s",
        Joiner.on(", ").join(buildTarget.getFlavors()));
    ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();
    for (BuildTarget dep : args.deps.get()) {
      Optional<FrameworkDependencies> frameworks =
          resolver.requireMetadata(
              BuildTarget.builder(dep)
                  .addFlavors(AppleDescriptions.FRAMEWORK_SHALLOW_FLAVOR)
                  .addFlavors(cxxPlatformFlavor.get())
                  .build(),
              FrameworkDependencies.class);
      if (frameworks.isPresent()) {
        sourcePaths.addAll(frameworks.get().getSourcePaths());
      }
    }
    BuildTarget shallowTarget =
        BuildTarget.builder(buildTarget)
            .addFlavors(AppleDescriptions.FRAMEWORK_SHALLOW_FLAVOR)
            .build();
    // Not all parts of Buck use require yet, so require the rule here so it's available in the
    // resolver for the parts that don't.
    resolver.requireRule(shallowTarget);
    sourcePaths.add(new BuildTargetSourcePath(shallowTarget));
    return Optional.of(metadataClass.cast(FrameworkDependencies.of(sourcePaths.build())));
  }

  public static boolean isSharedLibraryTarget(BuildTarget target) {
    return target.getFlavors().contains(CxxDescriptionEnhancer.SHARED_FLAVOR);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AppleNativeTargetDescriptionArg {
    public Optional<SourcePath> infoPlist;
    public Optional<ImmutableMap<String, String>> infoPlistSubstitutions;
  }

}
