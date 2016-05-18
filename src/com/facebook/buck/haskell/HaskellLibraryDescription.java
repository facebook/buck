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
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.io.File;
import java.util.Map;

public class HaskellLibraryDescription implements
    Description<HaskellLibraryDescription.Arg>,
    ImplicitDepsInferringDescription<HaskellLibraryDescription.Arg>,
    Flavored {

  private static final BuildRuleType TYPE = BuildRuleType.of("haskell_library");
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
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  /**
   * @return the package identifier to use for the library with the given target.
   */
  private HaskellPackageInfo getPackageInfo(BuildTarget target) {
    String name =
        String.format("%s-%s", target.getBaseName(), target.getShortName());
    name = name.replace(File.separatorChar, '-');
    name = name.replace('_', '-');
    name = name.replaceFirst("^-*", "");
    return HaskellPackageInfo.of(name, "1.0.0", name);
  }

  private HaskellCompileRule requireCompileRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Arg args,
      CxxSourceRuleFactory.PicType picType)
      throws NoSuchBuildTargetException {
    return HaskellDescriptionUtils.requireCompileRule(
        params,
        resolver,
        pathResolver,
        cxxPlatform,
        haskellConfig,
        picType,
        Optional.<String>absent(),
        Optional.of(getPackageInfo(params.getBuildTarget())),
        args.compilerFlags.or(ImmutableList.<String>of()),
        HaskellSources.from(
            params.getBuildTarget(),
            resolver,
            pathResolver,
            cxxPlatform,
            "srcs",
            args.srcs.or(SourceList.EMPTY)));
  }

  private Archive createStaticLibrary(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Arg args,
      CxxSourceRuleFactory.PicType picType)
      throws NoSuchBuildTargetException {
    HaskellCompileRule compileRule =
        requireCompileRule(params, resolver, pathResolver, cxxPlatform, args, picType);
    return Archive.from(
        params.getBuildTarget(),
        params,
        pathResolver,
        cxxPlatform.getAr(),
        cxxPlatform.getRanlib(),
        cxxBuckConfig.getArchiveContents(),
        CxxDescriptionEnhancer.getStaticLibraryPath(
            params.getProjectFilesystem(),
            params.getBuildTarget(),
            cxxPlatform.getFlavor(),
            picType),
        compileRule.getObjects());
  }

  private HaskellPackageRule createPackage(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Arg args,
      CxxSourceRuleFactory.PicType picType)
      throws NoSuchBuildTargetException {

    ImmutableSortedMap.Builder<String, HaskellPackage> depPackagesBuilder =
        ImmutableSortedMap.naturalOrder();
    for (BuildRule rule : params.getDeps()) {
      if (rule instanceof HaskellCompileDep) {
        ImmutableList<HaskellPackage> packages =
            ((HaskellCompileDep) rule).getCompileInput(cxxPlatform, picType).getPackages();
        for (HaskellPackage pkg : packages) {
          depPackagesBuilder.put(pkg.getInfo().getIdentifier(), pkg);
        }
      }
    }
    ImmutableSortedMap<String, HaskellPackage> depPackages = depPackagesBuilder.build();

    HaskellCompileRule compileRule =
        requireCompileRule(params, resolver, pathResolver, cxxPlatform, args, picType);

    return HaskellPackageRule.from(
        params.getBuildTarget(),
        params,
        pathResolver,
        haskellConfig.getPackager().resolve(resolver),
        getPackageInfo(params.getBuildTarget()),
        depPackages,
        compileRule.getModules(),
        ImmutableSortedSet.<SourcePath>of(),
        ImmutableSortedSet.of(compileRule.getInterfaces()));
  }

  private HaskellLinkRule createSharedLibrary(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Arg args)
      throws NoSuchBuildTargetException {
    HaskellCompileRule compileRule =
        requireCompileRule(
            params,
            resolver,
            pathResolver,
            cxxPlatform,
            args,
            CxxSourceRuleFactory.PicType.PIC);

    return HaskellDescriptionUtils.createLinkRule(
        params.getBuildTarget(),
        params,
        resolver,
        pathResolver,
        cxxPlatform,
        haskellConfig,
        Linker.LinkType.SHARED,
        ImmutableList.<String>of(),
        ImmutableList.copyOf(SourcePathArg.from(pathResolver, compileRule.getObjects())),
        Iterables.filter(params.getDeps(), NativeLinkable.class),
        Linker.LinkableDepType.SHARED);
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args)
      throws NoSuchBuildTargetException {

    BuildTarget buildTarget = params.getBuildTarget();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, Type>> type = LIBRARY_TYPE.getFlavorAndValue(buildTarget);
    Optional<CxxPlatform> cxxPlatform = cxxPlatforms.getValue(buildTarget);
    if (type.isPresent()) {
      Preconditions.checkState(cxxPlatform.isPresent());

      switch (type.get().getValue()) {
        case PACKAGE:
        case PACKAGE_DYNAMIC:
          return createPackage(
              params,
              resolver,
              pathResolver,
              cxxPlatform.get(),
              args,
              type.get().getValue() == Type.PACKAGE_DYNAMIC ?
                  CxxSourceRuleFactory.PicType.PIC :
                  CxxSourceRuleFactory.PicType.PDC);
        case SHARED:
          return createSharedLibrary(
              params,
              resolver,
              pathResolver,
              cxxPlatform.get(),
              args);
        case STATIC_PIC:
        case STATIC:
          return createStaticLibrary(
              params,
              resolver,
              pathResolver,
              cxxPlatform.get(),
              args,
              type.get().getValue() == Type.STATIC_PIC ?
                  CxxSourceRuleFactory.PicType.PIC :
                  CxxSourceRuleFactory.PicType.PDC);
      }

      throw new IllegalStateException(
          String.format(
              "%s: unexpected type `%s`",
              params.getBuildTarget(),
              type.get().getValue()));
    }

    return new HaskellLibrary(params, pathResolver, resolver);
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
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg) {
    return HaskellDescriptionUtils.getParseTimeDeps(haskellConfig, cxxPlatforms.getValues());
  }

  protected enum Type implements FlavorConvertible {

    PACKAGE(ImmutableFlavor.of("package")),
    PACKAGE_DYNAMIC(ImmutableFlavor.of("package-dynamic")),

    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
    STATIC_PIC(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR),
    STATIC(CxxDescriptionEnhancer.STATIC_FLAVOR),

    ;

    private final Flavor flavor;

    Type(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }

  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<SourceList> srcs;
    public Optional<ImmutableList<String>> compilerFlags;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }

}
