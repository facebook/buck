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

package com.facebook.buck.haskell;

import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.platform.CxxPlatform;
import com.facebook.buck.cxx.platform.Linker;
import com.facebook.buck.cxx.platform.NativeLinkable;
import com.facebook.buck.cxx.platform.NativeLinkableInput;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

public class HaskellLibraryDescription
    implements Description<HaskellLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<
            HaskellLibraryDescription.AbstractHaskellLibraryDescriptionArg>,
        Flavored,
        VersionPropagator<HaskellLibraryDescriptionArg> {

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      FlavorDomain.from("Haskell Library Type", Type.class);

  private final HaskellConfig haskellConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public HaskellLibraryDescription(
      HaskellConfig haskellConfig,
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.haskellConfig = haskellConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public Class<HaskellLibraryDescriptionArg> getConstructorArgType() {
    return HaskellLibraryDescriptionArg.class;
  }

  private BuildTarget getBaseBuildTarget(BuildTarget target) {
    return target.withoutFlavors(Sets.union(Type.FLAVOR_VALUES, cxxPlatforms.getFlavors()));
  }

  /** @return the package identifier to use for the library with the given target. */
  private HaskellPackageInfo getPackageInfo(BuildTarget target) {
    String name = String.format("%s-%s", target.getBaseName(), target.getShortName());
    name = name.replace(File.separatorChar, '-');
    name = name.replace('_', '-');
    name = name.replaceFirst("^-*", "");

    Optional<String> packageNamePrefix = haskellConfig.getPackageNamePrefix();
    if (packageNamePrefix.isPresent()) {
      name = packageNamePrefix.get() + "-" + name;
    }

    return HaskellPackageInfo.of(name, "1.0.0", name);
  }

  private HaskellCompileRule requireCompileRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      HaskellLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      Linker.LinkableDepType depType,
      boolean hsProfile)
      throws NoSuchBuildTargetException {
    return HaskellDescriptionUtils.requireCompileRule(
        buildTarget,
        projectFilesystem,
        params,
        resolver,
        ruleFinder,
        deps,
        cxxPlatform,
        haskellConfig,
        depType,
        hsProfile,
        Optional.empty(),
        Optional.of(getPackageInfo(buildTarget)),
        args.getCompilerFlags(),
        HaskellSources.from(
            buildTarget, resolver, pathResolver, ruleFinder, cxxPlatform, "srcs", args.getSrcs()));
  }

  private Archive createStaticLibrary(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      HaskellLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      Linker.LinkableDepType depType,
      boolean hsProfile)
      throws NoSuchBuildTargetException {
    HaskellCompileRule compileRule =
        requireCompileRule(
            target,
            projectFilesystem,
            baseParams,
            resolver,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            args,
            deps,
            depType,
            hsProfile);
    return Archive.from(
        target,
        projectFilesystem,
        ruleFinder,
        cxxPlatform,
        cxxBuckConfig.getArchiveContents(),
        CxxDescriptionEnhancer.getStaticLibraryPath(
            projectFilesystem,
            target.withoutFlavors(HaskellDescriptionUtils.PROF),
            cxxPlatform.getFlavor(),
            depType == Linker.LinkableDepType.STATIC
                ? CxxSourceRuleFactory.PicType.PDC
                : CxxSourceRuleFactory.PicType.PIC,
            cxxPlatform.getStaticLibraryExtension(),
            hsProfile ? "_p" : ""),
        compileRule.getObjects(),
        // TODO(#20466393): Currently, GHC produces nono-deterministically sized object files.
        // This means that it's possible to get a thin archive fetched from cache originating from
        // one build and the associated object files fetched from cache originating from another, in
        // which the sizes listed in the archive differ from the objects on disk, causing the GHC
        // linker to fail. Technically, since `HaskellCompileRule` is producing non-deterministic
        // outputs, we should mark that as uncacheable.  However, as that would have a significant
        // affect on build efficiency, and since this issue appears to only manifest by a size
        // mismatch with what is embedded in thin archives, just disable caching when using thin
        // archives.
        /* cacheable */ cxxBuckConfig.getArchiveContents() != Archive.Contents.THIN);
  }

  private Archive requireStaticLibrary(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      HaskellLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      Linker.LinkableDepType depType,
      boolean hsProfile)
      throws NoSuchBuildTargetException {
    Preconditions.checkArgument(
        Sets.intersection(
                baseTarget.getFlavors(), Sets.union(Type.FLAVOR_VALUES, cxxPlatforms.getFlavors()))
            .isEmpty());
    BuildTarget target =
        baseTarget.withAppendedFlavors(
            depType == Linker.LinkableDepType.STATIC
                ? Type.STATIC.getFlavor()
                : Type.STATIC_PIC.getFlavor(),
            cxxPlatform.getFlavor());

    if (hsProfile) {
      target = target.withAppendedFlavors(HaskellDescriptionUtils.PROF);
    } else {
      target = target.withoutFlavors(HaskellDescriptionUtils.PROF);
    }

    Optional<Archive> archive = resolver.getRuleOptionalWithType(target, Archive.class);
    if (archive.isPresent()) {
      return archive.get();
    }

    return resolver.addToIndex(
        createStaticLibrary(
            target,
            projectFilesystem,
            baseParams,
            resolver,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            args,
            deps,
            depType,
            hsProfile));
  }

  private HaskellPackageRule createPackage(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      HaskellLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      Linker.LinkableDepType depType,
      boolean hsProfile)
      throws NoSuchBuildTargetException {

    ImmutableSortedSet<SourcePath> libraries;
    BuildRule library;
    switch (depType) {
      case SHARED:
        library =
            requireSharedLibrary(
                getBaseBuildTarget(target),
                projectFilesystem,
                baseParams,
                resolver,
                pathResolver,
                ruleFinder,
                cxxPlatform,
                args,
                deps);
        libraries = ImmutableSortedSet.of(library.getSourcePathToOutput());
        break;
      case STATIC:
      case STATIC_PIC:
        library =
            requireStaticLibrary(
                getBaseBuildTarget(target),
                projectFilesystem,
                baseParams,
                resolver,
                pathResolver,
                ruleFinder,
                cxxPlatform,
                args,
                deps,
                depType,
                false);

        if (hsProfile) {
          if (!(Linker.LinkableDepType.STATIC == depType
              || Linker.LinkableDepType.STATIC_PIC == depType)) {
            throw new IllegalStateException();
          }

          BuildRule profiledLibrary =
              requireStaticLibrary(
                  getBaseBuildTarget(target),
                  projectFilesystem,
                  baseParams,
                  resolver,
                  pathResolver,
                  ruleFinder,
                  cxxPlatform,
                  args,
                  deps,
                  depType,
                  true);

          libraries =
              ImmutableSortedSet.of(
                  library.getSourcePathToOutput(), profiledLibrary.getSourcePathToOutput());

        } else {
          libraries = ImmutableSortedSet.of(library.getSourcePathToOutput());
        }
        break;
      default:
        throw new IllegalStateException();
    }

    ImmutableSortedMap.Builder<String, HaskellPackage> depPackagesBuilder =
        ImmutableSortedMap.naturalOrder();
    for (BuildRule rule : deps) {
      if (rule instanceof HaskellCompileDep) {
        ImmutableList<HaskellPackage> packages =
            ((HaskellCompileDep) rule)
                .getCompileInput(cxxPlatform, depType, hsProfile)
                .getPackages();
        for (HaskellPackage pkg : packages) {
          depPackagesBuilder.put(pkg.getInfo().getIdentifier(), pkg);
        }
      }
    }

    ImmutableSortedMap<String, HaskellPackage> depPackages = depPackagesBuilder.build();

    ImmutableSortedSet<SourcePath> interfaces;
    ImmutableSortedSet<SourcePath> objects;
    HaskellCompileRule compileRule =
        requireCompileRule(
            target,
            projectFilesystem,
            baseParams,
            resolver,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            args,
            deps,
            depType,
            false);

    if (hsProfile) {
      HaskellCompileRule profiledCompileRule =
          requireCompileRule(
              target,
              projectFilesystem,
              baseParams,
              resolver,
              pathResolver,
              ruleFinder,
              cxxPlatform,
              args,
              deps,
              depType,
              true);

      interfaces =
          ImmutableSortedSet.of(compileRule.getInterfaces(), profiledCompileRule.getInterfaces());
      objects =
          ImmutableSortedSet.of(compileRule.getObjectsDir(), profiledCompileRule.getObjectsDir());
    } else {
      interfaces = ImmutableSortedSet.of(compileRule.getInterfaces());
      objects = ImmutableSortedSet.of(compileRule.getObjectsDir());
    }

    return HaskellPackageRule.from(
        target,
        projectFilesystem,
        baseParams,
        ruleFinder,
        haskellConfig.getPackager().resolve(resolver),
        haskellConfig.getHaskellVersion(),
        depType,
        getPackageInfo(target),
        depPackages,
        compileRule.getModules(),
        libraries,
        interfaces,
        objects);
  }

  private HaskellPackageRule requirePackage(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      HaskellLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      Linker.LinkableDepType depType,
      boolean hsProfile)
      throws NoSuchBuildTargetException {
    Preconditions.checkArgument(
        Sets.intersection(
                baseTarget.getFlavors(), Sets.union(Type.FLAVOR_VALUES, cxxPlatforms.getFlavors()))
            .isEmpty());
    BuildTarget target = baseTarget.withAppendedFlavors(cxxPlatform.getFlavor());
    switch (depType) {
      case SHARED:
        target = target.withAppendedFlavors(Type.PACKAGE_SHARED.getFlavor());
        break;
      case STATIC:
        target = target.withAppendedFlavors(Type.PACKAGE_STATIC.getFlavor());
        break;
      case STATIC_PIC:
        target = target.withAppendedFlavors(Type.PACKAGE_STATIC_PIC.getFlavor());
        break;
      default:
        throw new IllegalStateException();
    }

    if (hsProfile) {
      target = target.withAppendedFlavors(HaskellDescriptionUtils.PROF);
    }

    Optional<HaskellPackageRule> packageRule =
        resolver.getRuleOptionalWithType(target, HaskellPackageRule.class);
    if (packageRule.isPresent()) {
      return packageRule.get();
    }
    return resolver.addToIndex(
        createPackage(
            target,
            projectFilesystem,
            baseParams,
            resolver,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            args,
            deps,
            depType,
            hsProfile));
  }

  private HaskellLinkRule createSharedLibrary(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      HaskellLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps)
      throws NoSuchBuildTargetException {
    HaskellCompileRule compileRule =
        requireCompileRule(
            target,
            projectFilesystem,
            baseParams,
            resolver,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            args,
            deps,
            Linker.LinkableDepType.SHARED,
            false);

    String name =
        CxxDescriptionEnhancer.getSharedLibrarySoname(
            Optional.empty(), target.withFlavors(), cxxPlatform);
    Path outputPath = BuildTargets.getGenPath(projectFilesystem, target, "%s").resolve(name);

    return HaskellDescriptionUtils.createLinkRule(
        target,
        projectFilesystem,
        baseParams,
        resolver,
        ruleFinder,
        cxxPlatform,
        haskellConfig,
        Linker.LinkType.SHARED,
        ImmutableList.of(),
        ImmutableList.copyOf(SourcePathArg.from(compileRule.getObjects())),
        RichStream.from(deps).filter(NativeLinkable.class).toImmutableList(),
        ImmutableSet.of(),
        Linker.LinkableDepType.SHARED,
        outputPath,
        Optional.of(name),
        false);
  }

  private HaskellLinkRule requireSharedLibrary(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      HaskellLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps)
      throws NoSuchBuildTargetException {
    Preconditions.checkArgument(
        Sets.intersection(
                baseTarget.getFlavors(), Sets.union(Type.FLAVOR_VALUES, cxxPlatforms.getFlavors()))
            .isEmpty());
    BuildTarget target =
        baseTarget.withAppendedFlavors(Type.SHARED.getFlavor(), cxxPlatform.getFlavor());

    Optional<HaskellLinkRule> linkRule =
        resolver.getRuleOptionalWithType(target, HaskellLinkRule.class);
    if (linkRule.isPresent()) {
      return linkRule.get();
    }
    return resolver.addToIndex(
        createSharedLibrary(
            target,
            projectFilesystem,
            baseParams,
            resolver,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            args,
            deps));
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      final ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      final HaskellLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    final SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    CxxDeps allDeps =
        CxxDeps.builder().addDeps(args.getDeps()).addPlatformDeps(args.getPlatformDeps()).build();

    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, Type>> type = LIBRARY_TYPE.getFlavorAndValue(buildTarget);
    Optional<CxxPlatform> cxxPlatform = cxxPlatforms.getValue(buildTarget);
    if (type.isPresent()) {
      Preconditions.checkState(cxxPlatform.isPresent());

      // Get the base build, without any flavors referring to the library type or platform.
      BuildTarget baseTarget =
          buildTarget.withoutFlavors(Sets.union(Type.FLAVOR_VALUES, cxxPlatforms.getFlavors()));

      ImmutableSet<BuildRule> deps = allDeps.get(resolver, cxxPlatform.get());

      switch (type.get().getValue()) {
        case PACKAGE_SHARED:
        case PACKAGE_STATIC:
        case PACKAGE_STATIC_PIC:
          Linker.LinkableDepType depType;
          if (type.get().getValue().equals(Type.PACKAGE_SHARED)) {
            depType = Linker.LinkableDepType.SHARED;
          } else if (type.get().getValue().equals(Type.PACKAGE_STATIC)) {
            depType = Linker.LinkableDepType.STATIC;
          } else {
            depType = Linker.LinkableDepType.STATIC_PIC;
          }
          return requirePackage(
              baseTarget,
              projectFilesystem,
              params,
              resolver,
              pathResolver,
              ruleFinder,
              cxxPlatform.get(),
              args,
              deps,
              depType,
              false);
        case SHARED:
          return requireSharedLibrary(
              baseTarget,
              projectFilesystem,
              params,
              resolver,
              pathResolver,
              ruleFinder,
              cxxPlatform.get(),
              args,
              deps);
        case STATIC_PIC:
        case STATIC:
          return requireStaticLibrary(
              baseTarget,
              projectFilesystem,
              params,
              resolver,
              pathResolver,
              ruleFinder,
              cxxPlatform.get(),
              args,
              deps,
              type.get().getValue() == Type.STATIC
                  ? Linker.LinkableDepType.STATIC
                  : Linker.LinkableDepType.STATIC_PIC,
              false);
      }

      throw new IllegalStateException(
          String.format("%s: unexpected type `%s`", buildTarget, type.get().getValue()));
    }

    return new HaskellLibrary(buildTarget, projectFilesystem, params) {

      @Override
      public Iterable<BuildRule> getCompileDeps(CxxPlatform cxxPlatform) {
        return RichStream.from(allDeps.get(resolver, cxxPlatform))
            .filter(dep -> dep instanceof HaskellCompileDep || dep instanceof CxxPreprocessorDep)
            .toImmutableList();
      }

      @Override
      public HaskellCompileInput getCompileInput(
          CxxPlatform cxxPlatform, Linker.LinkableDepType depType, boolean hsProfile)
          throws NoSuchBuildTargetException {
        HaskellPackageRule rule =
            requirePackage(
                getBaseBuildTarget(getBuildTarget()),
                projectFilesystem,
                params,
                resolver,
                pathResolver,
                ruleFinder,
                cxxPlatform,
                args,
                allDeps.get(resolver, cxxPlatform),
                depType,
                hsProfile);
        return HaskellCompileInput.builder().addPackages(rule.getPackage()).build();
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableDeps() {
        return ImmutableList.of();
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableExportedDepsForPlatform(
          CxxPlatform cxxPlatform) {
        return RichStream.from(allDeps.get(resolver, cxxPlatform))
            .filter(NativeLinkable.class)
            .toImmutableList();
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps() {
        return RichStream.from(allDeps.getForAllPlatforms(resolver))
            .filter(NativeLinkable.class)
            .toImmutableList();
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType type,
          boolean forceLinkWhole,
          ImmutableSet<NativeLinkable.LanguageExtensions> languageExtensions)
          throws NoSuchBuildTargetException {
        Iterable<com.facebook.buck.rules.args.Arg> linkArgs;
        switch (type) {
          case STATIC:
          case STATIC_PIC:
            Archive archive =
                requireStaticLibrary(
                    getBaseBuildTarget(getBuildTarget()),
                    projectFilesystem,
                    params,
                    resolver,
                    pathResolver,
                    ruleFinder,
                    cxxPlatform,
                    args,
                    allDeps.get(resolver, cxxPlatform),
                    type,
                    false);
            linkArgs =
                args.getLinkWhole() || forceLinkWhole
                    ? cxxPlatform.getLd().resolve(resolver).linkWhole(archive.toArg())
                    : ImmutableList.of(archive.toArg());
            break;
          case SHARED:
            BuildRule rule =
                requireSharedLibrary(
                    getBaseBuildTarget(getBuildTarget()),
                    projectFilesystem,
                    params,
                    resolver,
                    pathResolver,
                    ruleFinder,
                    cxxPlatform,
                    args,
                    allDeps.get(resolver, cxxPlatform));
            linkArgs = ImmutableList.of(SourcePathArg.of(rule.getSourcePathToOutput()));
            break;
          default:
            throw new IllegalStateException();
        }
        return NativeLinkableInput.builder().addAllArgs(linkArgs).build();
      }

      @Override
      public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
        return args.getPreferredLinkage();
      }

      @Override
      public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform)
          throws NoSuchBuildTargetException {
        ImmutableMap.Builder<String, SourcePath> libs = ImmutableMap.builder();
        String sharedLibrarySoname =
            CxxDescriptionEnhancer.getSharedLibrarySoname(
                Optional.empty(), getBuildTarget(), cxxPlatform);
        BuildRule sharedLibraryBuildRule =
            requireSharedLibrary(
                getBaseBuildTarget(getBuildTarget()),
                projectFilesystem,
                params,
                resolver,
                pathResolver,
                ruleFinder,
                cxxPlatform,
                args,
                allDeps.get(resolver, cxxPlatform));
        libs.put(sharedLibrarySoname, sharedLibraryBuildRule.getSourcePathToOutput());
        return libs.build();
      }
    };
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    if (cxxPlatforms.containsAnyOf(flavors)) {
      return true;
    }

    for (Type type : Type.values()) {
      if (flavors.contains(type.getFlavor())) {
        return true;
      }
    }

    return false;
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractHaskellLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    HaskellDescriptionUtils.getParseTimeDeps(
        haskellConfig, cxxPlatforms.getValues(), extraDepsBuilder);
  }

  protected enum Type implements FlavorConvertible {
    PACKAGE_SHARED(InternalFlavor.of("package-shared")),
    PACKAGE_STATIC(InternalFlavor.of("package-static")),
    PACKAGE_STATIC_PIC(InternalFlavor.of("package-static-pic")),

    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
    STATIC(CxxDescriptionEnhancer.STATIC_FLAVOR),
    STATIC_PIC(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR),
    ;

    public static final ImmutableSet<Flavor> FLAVOR_VALUES =
        ImmutableList.copyOf(Type.values())
            .stream()
            .map(Type::getFlavor)
            .collect(MoreCollectors.toImmutableSet());

    private final Flavor flavor;

    Type(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractHaskellLibraryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    @Value.Default
    default SourceList getSrcs() {
      return SourceList.EMPTY;
    }

    ImmutableList<StringWithMacros> getLinkerFlags();

    ImmutableList<String> getCompilerFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }

    @Value.Default
    default boolean getLinkWhole() {
      return false;
    }

    @Value.Default
    default NativeLinkable.Linkage getPreferredLinkage() {
      return NativeLinkable.Linkage.ANY;
    }
  }
}
