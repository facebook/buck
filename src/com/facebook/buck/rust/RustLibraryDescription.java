/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rust;

import static com.facebook.buck.rust.RustCompileUtils.ruleToCrateName;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasSrcs;
import com.facebook.buck.rules.HasTests;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

public class RustLibraryDescription
    implements Description<RustLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<RustLibraryDescription.AbstractRustLibraryDescriptionArg>,
        Flavored,
        VersionPropagator<RustLibraryDescriptionArg> {

  private static final FlavorDomain<RustDescriptionEnhancer.Type> LIBRARY_TYPE =
      FlavorDomain.from("Rust Library Type", RustDescriptionEnhancer.Type.class);

  private final ToolchainProvider toolchainProvider;
  private final RustBuckConfig rustBuckConfig;

  public RustLibraryDescription(
      ToolchainProvider toolchainProvider, RustBuckConfig rustBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.rustBuckConfig = rustBuckConfig;
  }

  @Override
  public Class<RustLibraryDescriptionArg> getConstructorArgType() {
    return RustLibraryDescriptionArg.class;
  }

  private RustCompileRule requireBuild(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      RustBuckConfig rustBuckConfig,
      ImmutableList<String> extraFlags,
      ImmutableList<String> extraLinkerFlags,
      Iterable<Arg> linkerInputs,
      String crate,
      CrateType crateType,
      Linker.LinkableDepType depType,
      RustLibraryDescriptionArg args) {
    Pair<SourcePath, ImmutableSortedSet<SourcePath>> rootModuleAndSources =
        RustCompileUtils.getRootModuleAndSources(
            buildTarget,
            resolver,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            crate,
            args.getCrateRoot(),
            ImmutableSet.of("lib.rs"),
            args.getSrcs());
    return RustCompileUtils.requireBuild(
        buildTarget,
        projectFilesystem,
        params,
        resolver,
        ruleFinder,
        cxxPlatform,
        rustBuckConfig,
        extraFlags,
        extraLinkerFlags,
        linkerInputs,
        crate,
        crateType,
        depType,
        rootModuleAndSources.getSecond(),
        rootModuleAndSources.getFirst());
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      RustLibraryDescriptionArg args) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    ImmutableList.Builder<String> rustcArgs = ImmutableList.builder();

    RustCompileUtils.addFeatures(buildTarget, args.getFeatures(), rustcArgs);

    rustcArgs.addAll(args.getRustcFlags());
    rustcArgs.addAll(rustBuckConfig.getRustLibraryFlags());

    String crate = args.getCrate().orElse(ruleToCrateName(buildTarget.getShortName()));

    CxxPlatformsProvider cxxPlatformsProvider = getCxxPlatformsProvider();

    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, RustDescriptionEnhancer.Type>> type =
        LIBRARY_TYPE.getFlavorAndValue(buildTarget);
    Optional<CxxPlatform> cxxPlatform =
        cxxPlatformsProvider.getCxxPlatforms().getValue(buildTarget);

    if (type.isPresent()) {
      // Uncommon case - someone explicitly invoked buck to build a specific flavor as the
      // direct target.
      CrateType crateType;

      Linker.LinkableDepType depType;

      if (args.getProcMacro()) {
        crateType = CrateType.PROC_MACRO;
      } else {
        crateType = type.get().getValue().getCrateType();
      }

      if (crateType.isDynamic()) {
        depType = Linker.LinkableDepType.SHARED;
      } else {
        if (crateType.isPic()) {
          depType = Linker.LinkableDepType.STATIC_PIC;
        } else {
          depType = Linker.LinkableDepType.STATIC;
        }
      }

      return requireBuild(
          buildTarget,
          projectFilesystem,
          params,
          resolver,
          pathResolver,
          ruleFinder,
          cxxPlatform.orElse(cxxPlatformsProvider.getDefaultCxxPlatform()),
          rustBuckConfig,
          rustcArgs.build(),
          /* linkerArgs */ ImmutableList.of(),
          /* linkerInputs */ ImmutableList.of(),
          crate,
          crateType,
          depType,
          args);
    }

    // Common case - we're being invoked to satisfy some other rule's dependency.
    return new RustLibrary(buildTarget, projectFilesystem, params) {
      // RustLinkable
      @Override
      public Arg getLinkerArg(
          boolean direct,
          boolean isCheck,
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType depType) {
        BuildRule rule;
        CrateType crateType;

        // Determine a crate type from preferred linkage and deptype.
        // NOTE: DYLIB requires upstream rustc bug #38795 to be fixed, because otherwise
        // the use of -rpath will break with flavored paths containing ','.
        if (isCheck) {
          crateType = CrateType.CHECK;
        } else if (args.getProcMacro()) {
          crateType = CrateType.PROC_MACRO;
        } else {
          switch (args.getPreferredLinkage()) {
            case ANY:
            default:
              switch (depType) {
                case SHARED:
                  crateType = CrateType.DYLIB;
                  break;
                case STATIC_PIC:
                  crateType = CrateType.RLIB_PIC;
                  break;
                case STATIC:
                default:
                  crateType = CrateType.RLIB;
                  break;
              }
              break;

            case SHARED:
              crateType = CrateType.DYLIB;
              break;

            case STATIC:
              if (depType == Linker.LinkableDepType.STATIC) {
                crateType = CrateType.RLIB;
              } else {
                crateType = CrateType.RLIB_PIC;
              }
              break;
          }
        }

        rule =
            requireBuild(
                buildTarget,
                projectFilesystem,
                params,
                resolver,
                pathResolver,
                ruleFinder,
                cxxPlatform,
                rustBuckConfig,
                rustcArgs.build(),
                /* linkerArgs */ ImmutableList.of(),
                /* linkerInputs */ ImmutableList.of(),
                crate,
                crateType,
                depType,
                args);
        SourcePath rlib = rule.getSourcePathToOutput();
        return new RustLibraryArg(crate, rlib, direct);
      }

      @Override
      public boolean isProcMacro() {
        return args.getProcMacro();
      }

      @Override
      public Linkage getPreferredLinkage() {
        return args.getPreferredLinkage();
      }

      @Override
      public ImmutableMap<String, SourcePath> getRustSharedLibraries(CxxPlatform cxxPlatform) {
        BuildTarget target = getBuildTarget();

        ImmutableMap.Builder<String, SourcePath> libs = ImmutableMap.builder();
        String sharedLibrarySoname = CrateType.DYLIB.filenameFor(target, crate, cxxPlatform);
        BuildRule sharedLibraryBuildRule =
            requireBuild(
                buildTarget,
                projectFilesystem,
                params,
                resolver,
                pathResolver,
                ruleFinder,
                cxxPlatform,
                rustBuckConfig,
                rustcArgs.build(),
                /* linkerArgs */ ImmutableList.of(),
                /* linkerInputs */ ImmutableList.of(),
                crate,
                CrateType.DYLIB,
                Linker.LinkableDepType.SHARED,
                args);
        libs.put(sharedLibrarySoname, sharedLibraryBuildRule.getSourcePathToOutput());
        return libs.build();
      }

      // NativeLinkable
      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableDeps() {
        return ImmutableList.of();
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps() {
        return FluentIterable.from(getBuildDeps()).filter(NativeLinkable.class);
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType depType,
          boolean forceLinkWhole,
          ImmutableSet<LanguageExtensions> languageExtensions) {
        CrateType crateType;

        switch (depType) {
          case SHARED:
            crateType = CrateType.CDYLIB;
            break;

          case STATIC_PIC:
            crateType = CrateType.STATIC_PIC;
            break;

          case STATIC:
          default:
            crateType = CrateType.STATIC;
            break;
        }

        BuildRule rule =
            requireBuild(
                buildTarget,
                projectFilesystem,
                params,
                resolver,
                pathResolver,
                ruleFinder,
                cxxPlatform,
                rustBuckConfig,
                rustcArgs.build(),
                /* linkerArgs */ ImmutableList.of(),
                /* linkerInputs */ ImmutableList.of(),
                crate,
                crateType,
                depType,
                args);

        SourcePath lib = rule.getSourcePathToOutput();
        SourcePathArg arg = SourcePathArg.of(lib);

        return NativeLinkableInput.builder().addArgs(arg).build();
      }

      @Override
      public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
        return args.getPreferredLinkage();
      }

      @Override
      public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
        ImmutableMap.Builder<String, SourcePath> libs = ImmutableMap.builder();
        String sharedLibrarySoname =
            CxxDescriptionEnhancer.getSharedLibrarySoname(
                Optional.empty(), getBuildTarget(), cxxPlatform);
        BuildRule sharedLibraryBuildRule =
            requireBuild(
                buildTarget,
                projectFilesystem,
                params,
                resolver,
                pathResolver,
                ruleFinder,
                cxxPlatform,
                rustBuckConfig,
                rustcArgs.build(),
                /* linkerArgs */ ImmutableList.of(),
                /* linkerInputs */ ImmutableList.of(),
                crate,
                CrateType.CDYLIB,
                Linker.LinkableDepType.SHARED,
                args);
        libs.put(sharedLibrarySoname, sharedLibraryBuildRule.getSourcePathToOutput());
        return libs.build();
      }
    };
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractRustLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder.addAll(rustBuckConfig.getRustCompiler().getParseTimeDeps());
    extraDepsBuilder.addAll(
        rustBuckConfig.getLinker().map(ToolProvider::getParseTimeDeps).orElse(ImmutableList.of()));

    extraDepsBuilder.addAll(
        CxxPlatforms.getParseTimeDeps(getCxxPlatformsProvider().getCxxPlatforms().getValues()));
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    if (getCxxPlatformsProvider().getCxxPlatforms().containsAnyOf(flavors)) {
      return true;
    }

    for (RustDescriptionEnhancer.Type type : RustDescriptionEnhancer.Type.values()) {
      if (flavors.contains(type.getFlavor())) {
        return true;
      }
    }

    return false;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(ImmutableSet.of(getCxxPlatformsProvider().getCxxPlatforms(), LIBRARY_TYPE));
  }

  private CxxPlatformsProvider getCxxPlatformsProvider() {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractRustLibraryDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps, HasSrcs, HasTests {
    @Value.NaturalOrder
    ImmutableSortedSet<String> getFeatures();

    List<String> getRustcFlags();

    @Value.Default
    default NativeLinkable.Linkage getPreferredLinkage() {
      return NativeLinkable.Linkage.ANY;
    }

    Optional<String> getCrate();

    Optional<SourcePath> getCrateRoot();

    @Value.Default
    default boolean getProcMacro() {
      return false;
    }
  }
}
