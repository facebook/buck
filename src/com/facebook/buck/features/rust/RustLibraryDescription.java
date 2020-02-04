/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.rust;

import static com.facebook.buck.features.rust.RustCompileUtils.ruleToCrateName;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.facebook.buck.cxx.toolchain.nativelink.LegacyNativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.PlatformLockedNativeLinkableGroup;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.immutables.value.Value;

public class RustLibraryDescription
    implements DescriptionWithTargetGraph<RustLibraryDescriptionArg>,
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
      ActionGraphBuilder graphBuilder,
      RustPlatform rustPlatform,
      RustBuckConfig rustBuckConfig,
      ImmutableSortedMap<String, Arg> environment,
      ImmutableList<Arg> extraFlags,
      ImmutableList<Arg> extraLinkerFlags,
      Iterable<Arg> linkerInputs,
      String crate,
      CrateType crateType,
      Optional<String> edition,
      LinkableDepType depType,
      RustLibraryDescriptionArg args,
      Iterable<BuildRule> deps,
      ImmutableMap<String, BuildTarget> depsAliases) {
    Pair<String, ImmutableSortedMap<SourcePath, Optional<String>>> rootModuleAndSources =
        RustCompileUtils.getRootModuleAndSources(
            projectFilesystem,
            buildTarget,
            graphBuilder,
            rustPlatform.getCxxPlatform(),
            crate,
            args.getCrateRoot(),
            ImmutableSet.of("lib.rs"),
            args.getSrcs(),
            args.getMappedSrcs());
    return RustCompileUtils.requireBuild(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        rustPlatform,
        rustBuckConfig,
        environment,
        extraFlags,
        extraLinkerFlags,
        linkerInputs,
        crate,
        crateType,
        edition,
        depType,
        rootModuleAndSources.getSecond(),
        rootModuleAndSources.getFirst(),
        rustBuckConfig.getForceRlib(),
        rustBuckConfig.getPreferStaticLibs(),
        deps,
        depsAliases,
        rustBuckConfig.getIncremental(rustPlatform.getFlavor().getName()));
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      RustLibraryDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CxxDeps allDeps =
        CxxDeps.builder()
            .addDeps(args.getDeps())
            .addDeps(args.getNamedDeps().values())
            .addPlatformDeps(args.getPlatformDeps())
            .build();

    Function<RustPlatform, Pair<ImmutableList<Arg>, ImmutableSortedMap<String, Arg>>>
        getRustcArgsEnv =
            rustPlatform -> {
              StringWithMacrosConverter converter =
                  RustCompileUtils.getMacroExpander(
                      context, buildTarget, rustPlatform.getCxxPlatform());

              ImmutableList.Builder<Arg> rustcArgs = ImmutableList.builder();
              RustCompileUtils.addFeatures(buildTarget, args.getFeatures(), rustcArgs);
              RustCompileUtils.addTargetTripleForFlavor(rustPlatform.getFlavor(), rustcArgs);
              rustcArgs.addAll(rustPlatform.getRustLibraryFlags());
              rustcArgs.addAll(args.getRustcFlags().stream().map(converter::convert).iterator());

              ImmutableSortedMap<String, Arg> env =
                  ImmutableSortedMap.copyOf(
                      Maps.transformValues(args.getEnv(), converter::convert));

              return new Pair<>(rustcArgs.build(), env);
            };

    String crate = args.getCrate().orElse(ruleToCrateName(buildTarget.getShortName()));

    RustToolchain rustToolchain = getRustToolchain(buildTarget.getTargetConfiguration());

    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, RustDescriptionEnhancer.Type>> type =
        LIBRARY_TYPE.getFlavorAndValue(buildTarget);

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

      RustPlatform platform =
          RustCompileUtils.getRustPlatform(rustToolchain, buildTarget, args)
              .resolve(graphBuilder, buildTarget.getTargetConfiguration());

      Pair<ImmutableList<Arg>, ImmutableSortedMap<String, Arg>> argenv =
          getRustcArgsEnv.apply(platform);

      return requireBuild(
          buildTarget,
          projectFilesystem,
          graphBuilder,
          platform,
          rustBuckConfig,
          argenv.getSecond(),
          argenv.getFirst(),
          /* linkerArgs */ ImmutableList.of(),
          /* linkerInputs */ ImmutableList.of(),
          crate,
          crateType,
          args.getEdition(),
          depType,
          args,
          allDeps.get(graphBuilder, platform.getCxxPlatform()),
          args.getNamedDeps());
    }

    // Common case - we're being invoked to satisfy some other rule's dependency.
    return new RustLibrary(buildTarget, projectFilesystem, params) {
      private final PlatformLockedNativeLinkableGroup.Cache linkableCache =
          LegacyNativeLinkableGroup.getNativeLinkableCache(this);
      // RustLinkable
      @Override
      public Arg getLinkerArg(
          boolean direct,
          boolean isCheck,
          RustPlatform rustPlatform,
          LinkableDepType depType,
          Optional<String> alias) {
        BuildRule rule;
        CrateType crateType;

        // Determine a crate type from preferred linkage and deptype.
        // Procedural Macros (aka, compiler plugins) take priority over check builds
        // as we need the compiler plugin to be able to check the code which depends on the
        // plugin.
        if (isProcMacro()) {
          crateType = CrateType.PROC_MACRO;
        } else if (isCheck) {
          crateType = CrateType.CHECK;
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

        Pair<ImmutableList<Arg>, ImmutableSortedMap<String, Arg>> argenv =
            getRustcArgsEnv.apply(rustPlatform);

        rule =
            requireBuild(
                buildTarget,
                projectFilesystem,
                graphBuilder,
                rustPlatform,
                rustBuckConfig,
                argenv.getSecond(),
                argenv.getFirst(),
                /* linkerArgs */ ImmutableList.of(),
                /* linkerInputs */ ImmutableList.of(),
                crate,
                crateType,
                args.getEdition(),
                depType,
                args,
                allDeps.get(graphBuilder, rustPlatform.getCxxPlatform()),
                args.getNamedDeps());
        SourcePath rlib = rule.getSourcePathToOutput();
        return new RustLibraryArg(crate, rlib, direct, alias);
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
      public ImmutableMap<String, SourcePath> getRustSharedLibraries(RustPlatform rustPlatform) {
        BuildTarget target = getBuildTarget();

        ImmutableMap.Builder<String, SourcePath> libs = ImmutableMap.builder();
        String sharedLibrarySoname =
            CrateType.DYLIB.filenameFor(target, crate, rustPlatform.getCxxPlatform());

        Pair<ImmutableList<Arg>, ImmutableSortedMap<String, Arg>> argenv =
            getRustcArgsEnv.apply(rustPlatform);

        BuildRule sharedLibraryBuildRule =
            requireBuild(
                buildTarget,
                projectFilesystem,
                graphBuilder,
                rustPlatform,
                rustBuckConfig,
                argenv.getSecond(),
                argenv.getFirst(),
                /* linkerArgs */ ImmutableList.of(),
                /* linkerInputs */ ImmutableList.of(),
                crate,
                CrateType.DYLIB,
                args.getEdition(),
                LinkableDepType.SHARED,
                args,
                allDeps.get(graphBuilder, rustPlatform.getCxxPlatform()),
                args.getNamedDeps());
        libs.put(sharedLibrarySoname, sharedLibraryBuildRule.getSourcePathToOutput());
        return libs.build();
      }

      @Override
      public Iterable<BuildRule> getRustLinakbleDeps(RustPlatform rustPlatform) {
        return allDeps.get(graphBuilder, rustPlatform.getCxxPlatform());
      }

      // NativeLinkable
      @Override
      public Iterable<? extends NativeLinkableGroup> getNativeLinkableDeps(
          BuildRuleResolver ruleResolver) {
        return ImmutableList.of();
      }

      @Override
      public Iterable<NativeLinkableGroup> getNativeLinkableExportedDeps(
          BuildRuleResolver ruleResolver) {
        return RichStream.from(allDeps.getForAllPlatforms(ruleResolver))
            .filter(NativeLinkableGroup.class)
            .toImmutableList();
      }

      @Override
      public PlatformLockedNativeLinkableGroup.Cache getNativeLinkableCompatibilityCache() {
        return linkableCache;
      }

      @Override
      public Iterable<? extends NativeLinkableGroup> getNativeLinkableExportedDepsForPlatform(
          CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        // We want to skip over all the transitive Rust deps, and only return non-Rust
        // deps at the edge of the graph
        ImmutableList.Builder<NativeLinkableGroup> nativedeps = ImmutableList.builder();

        RustPlatform rustPlatform =
            getRustToolchain(buildTarget.getTargetConfiguration())
                .getRustPlatforms()
                .getValue(cxxPlatform.getFlavor())
                .resolve(graphBuilder, buildTarget.getTargetConfiguration());
        new AbstractBreadthFirstTraversal<BuildRule>(allDeps.get(graphBuilder, cxxPlatform)) {
          @Override
          public Iterable<BuildRule> visit(BuildRule rule) {
            if (rule instanceof RustLinkable) {
              // Rust rule - we just want to visit the children
              return ((RustLinkable) rule).getRustLinakbleDeps(rustPlatform);
            }
            if (rule instanceof NativeLinkableGroup) {
              nativedeps.add((NativeLinkableGroup) rule);
            }
            return ImmutableList.of();
          }
        }.start();

        return nativedeps.build();
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType depType,
          boolean forceLinkWhole,
          ActionGraphBuilder graphBuilder,
          TargetConfiguration targetConfiguration) {
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

        RustPlatform rustPlatform =
            getRustToolchain(targetConfiguration)
                .getRustPlatforms()
                .getValue(cxxPlatform.getFlavor())
                .resolve(graphBuilder, buildTarget.getTargetConfiguration());
        Pair<ImmutableList<Arg>, ImmutableSortedMap<String, Arg>> argenv =
            getRustcArgsEnv.apply(rustPlatform);

        BuildRule rule =
            requireBuild(
                buildTarget,
                projectFilesystem,
                graphBuilder,
                rustPlatform,
                rustBuckConfig,
                argenv.getSecond(),
                argenv.getFirst(),
                /* linkerArgs */ ImmutableList.of(),
                /* linkerInputs */ ImmutableList.of(),
                crate,
                crateType,
                args.getEdition(),
                depType,
                args,
                allDeps.get(graphBuilder, rustPlatform.getCxxPlatform()),
                args.getNamedDeps());

        SourcePath lib = rule.getSourcePathToOutput();
        SourcePathArg arg = SourcePathArg.of(lib);

        return NativeLinkableInput.builder().addArgs(arg).build();
      }

      @Override
      public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
        return args.getPreferredLinkage();
      }

      @Override
      public ImmutableMap<String, SourcePath> getSharedLibraries(
          CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        ImmutableMap.Builder<String, SourcePath> libs = ImmutableMap.builder();
        String sharedLibrarySoname =
            CrateType.DYLIB.filenameFor(getBuildTarget(), crate, cxxPlatform);
        RustPlatform rustPlatform =
            getRustToolchain(buildTarget.getTargetConfiguration())
                .getRustPlatforms()
                .getValue(cxxPlatform.getFlavor())
                .resolve(graphBuilder, buildTarget.getTargetConfiguration());

        Pair<ImmutableList<Arg>, ImmutableSortedMap<String, Arg>> argenv =
            getRustcArgsEnv.apply(rustPlatform);

        BuildRule sharedLibraryBuildRule =
            requireBuild(
                buildTarget,
                projectFilesystem,
                graphBuilder,
                rustPlatform,
                rustBuckConfig,
                argenv.getSecond(),
                argenv.getFirst(),
                /* linkerArgs */ ImmutableList.of(),
                /* linkerInputs */ ImmutableList.of(),
                crate,
                CrateType.CDYLIB,
                args.getEdition(),
                LinkableDepType.SHARED,
                args,
                allDeps.get(graphBuilder, rustPlatform.getCxxPlatform()),
                args.getNamedDeps());
        libs.put(sharedLibrarySoname, sharedLibraryBuildRule.getSourcePathToOutput());
        return libs.build();
      }
    };
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractRustLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // Add parse-time deps for *all* platforms, as we don't know which platform will be
    // selected by a top-level binary rule (e.g. a Python binary transitively depending on
    // this library may choose platform "foo").
    getRustToolchain(buildTarget.getTargetConfiguration()).getRustPlatforms().getValues().stream()
        .flatMap(
            p ->
                RichStream.from(
                    RustCompileUtils.getPlatformParseTimeDeps(
                        buildTarget.getTargetConfiguration(), p)))
        .forEach(targetGraphOnlyDepsBuilder::add);
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    return Optional.of(
        ImmutableSet.of(
            getRustToolchain(toolchainTargetConfiguration).getRustPlatforms(), LIBRARY_TYPE));
  }

  private RustToolchain getRustToolchain(TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider.getByName(
        RustToolchain.DEFAULT_NAME, toolchainTargetConfiguration, RustToolchain.class);
  }

  @RuleArg
  interface AbstractRustLibraryDescriptionArg extends RustCommonArgs, HasTests {
    @Value.Default
    default NativeLinkableGroup.Linkage getPreferredLinkage() {
      return NativeLinkableGroup.Linkage.ANY;
    }

    @Value.Default
    default boolean getProcMacro() {
      return false;
    }
  }
}
