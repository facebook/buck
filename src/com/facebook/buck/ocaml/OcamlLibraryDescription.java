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

package com.facebook.buck.ocaml;

import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.OcamlSource;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

public class OcamlLibraryDescription
    implements Description<OcamlLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<
            OcamlLibraryDescription.AbstractOcamlLibraryDescriptionArg>,
        VersionPropagator<OcamlLibraryDescriptionArg>,
        Flavored {

  private final ToolchainProvider toolchainProvider;

  public OcamlLibraryDescription(ToolchainProvider toolchainProvider) {
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Class<OcamlLibraryDescriptionArg> getConstructorArgType() {
    return OcamlLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      OcamlLibraryDescriptionArg args) {

    CxxDeps allDeps =
        CxxDeps.builder().addDeps(args.getDeps()).addPlatformDeps(args.getPlatformDeps()).build();

    OcamlToolchain ocamlToolchain =
        toolchainProvider.getByName(OcamlToolchain.DEFAULT_NAME, OcamlToolchain.class);
    FlavorDomain<OcamlPlatform> ocamlPlatforms = ocamlToolchain.getOcamlPlatforms();
    Optional<OcamlPlatform> ocamlPlatform = ocamlPlatforms.getValue(buildTarget);
    if (ocamlPlatform.isPresent()) {
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(context.getBuildRuleResolver());
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

      ImmutableList<OcamlSource> srcs = args.getSrcs();

      ImmutableList<Arg> flags =
          OcamlRuleBuilder.getFlags(
              buildTarget,
              context.getCellPathResolver(),
              context.getBuildRuleResolver(),
              ocamlPlatform.get(),
              args.getCompilerFlags(),
              args.getWarningsFlags());

      BuildTarget compileBuildTarget = OcamlRuleBuilder.createStaticLibraryBuildTarget(buildTarget);

      if (OcamlRuleBuilder.shouldUseFineGrainedRules(context.getBuildRuleResolver(), srcs)) {
        OcamlGeneratedBuildRules result =
            OcamlRuleBuilder.createFineGrainedBuildRules(
                buildTarget,
                ocamlPlatform.get(),
                compileBuildTarget,
                context.getProjectFilesystem(),
                params,
                context.getBuildRuleResolver(),
                allDeps.get(context.getBuildRuleResolver(), ocamlPlatform.get().getCxxPlatform()),
                srcs,
                /* isLibrary */ true,
                args.getBytecodeOnly(),
                flags,
                args.getOcamldepFlags(),
                !args.getBytecodeOnly() && args.getNativePlugin());
        return new OcamlStaticLibrary(
            buildTarget,
            context.getProjectFilesystem(),
            params,
            args.getLinkerFlags(),
            result.getObjectFiles(),
            result.getOcamlContext(),
            result.getRules().get(0),
            result.getNativeCompileDeps(),
            result.getBytecodeCompileDeps(),
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .add(result.getBytecodeLink())
                .addAll(ruleFinder.filterBuildRuleInputs(result.getObjectFiles()))
                .build(),
            result
                .getRules()
                .stream()
                .map(BuildRule::getBuildTarget)
                .collect(ImmutableList.toImmutableList()));

      } else {
        OcamlBuild ocamlLibraryBuild =
            OcamlRuleBuilder.createBulkCompileRule(
                buildTarget,
                ocamlPlatform.get(),
                compileBuildTarget,
                context.getProjectFilesystem(),
                params,
                context.getBuildRuleResolver(),
                allDeps.get(context.getBuildRuleResolver(), ocamlPlatform.get().getCxxPlatform()),
                srcs,
                /* isLibrary */ true,
                args.getBytecodeOnly(),
                flags,
                args.getOcamldepFlags());
        return new OcamlStaticLibrary(
            buildTarget,
            context.getProjectFilesystem(),
            params,
            args.getLinkerFlags(),
            srcs.stream()
                .map(OcamlSource::getSource)
                .map(pathResolver::getAbsolutePath)
                .filter(OcamlUtil.ext(OcamlCompilables.OCAML_C))
                .map(ocamlLibraryBuild.getOcamlContext()::getCOutput)
                .map(input -> ExplicitBuildTargetSourcePath.of(compileBuildTarget, input))
                .collect(ImmutableList.toImmutableList()),
            ocamlLibraryBuild.getOcamlContext(),
            ocamlLibraryBuild,
            ImmutableSortedSet.of(ocamlLibraryBuild),
            ImmutableSortedSet.of(ocamlLibraryBuild),
            ImmutableSortedSet.of(ocamlLibraryBuild),
            ImmutableList.of(ocamlLibraryBuild.getBuildTarget()));
      }
    }

    // Platform-agnostic wrapper for Ocaml library rules.  Ideally, the inner library rules, which
    // are created on-demand for the given passed in platform would use a different rule type or,
    // better yet, be non-build-rule types provided by metadata.
    return new OcamlLibrary(buildTarget, context.getProjectFilesystem(), params) {

      private OcamlLibrary getWrapped(OcamlPlatform platform) {
        return (OcamlLibrary)
            context
                .getBuildRuleResolver()
                .requireRule(getBuildTarget().withAppendedFlavors(platform.getFlavor()));
      }

      @Override
      public Path getIncludeLibDir(OcamlPlatform platform) {
        return getWrapped(platform).getIncludeLibDir(platform);
      }

      @Override
      public Iterable<String> getBytecodeIncludeDirs(OcamlPlatform platform) {
        return getWrapped(platform).getBytecodeIncludeDirs(platform);
      }

      @Override
      public ImmutableSortedSet<BuildRule> getNativeCompileDeps(OcamlPlatform platform) {
        return getWrapped(platform).getNativeCompileDeps(platform);
      }

      @Override
      public ImmutableSortedSet<BuildRule> getBytecodeCompileDeps(OcamlPlatform platform) {
        return getWrapped(platform).getBytecodeCompileDeps(platform);
      }

      @Override
      public ImmutableSortedSet<BuildRule> getBytecodeLinkDeps(OcamlPlatform platform) {
        return getWrapped(platform).getBytecodeLinkDeps(platform);
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(OcamlPlatform platform) {
        return getWrapped(platform).getNativeLinkableInput(platform);
      }

      @Override
      public NativeLinkableInput getBytecodeLinkableInput(OcamlPlatform platform) {
        return getWrapped(platform).getBytecodeLinkableInput(platform);
      }

      @Override
      public Iterable<BuildRule> getOcamlLibraryDeps(OcamlPlatform platform) {
        return allDeps.get(context.getBuildRuleResolver(), platform.getCxxPlatform());
      }
    };
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractOcamlLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    for (OcamlPlatform platform :
        toolchainProvider
            .getByName(OcamlToolchain.DEFAULT_NAME, OcamlToolchain.class)
            .getOcamlPlatforms()
            .getValues()) {
      targetGraphOnlyDepsBuilder.addAll(OcamlUtil.getParseTimeDeps(platform));
    }
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return flavors.equals(
        ImmutableSet.of(
            toolchainProvider
                .getByName(OcamlToolchain.DEFAULT_NAME, OcamlToolchain.class)
                .getDefaultOcamlPlatform()
                .getFlavor()));
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractOcamlLibraryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    ImmutableList<OcamlSource> getSrcs();

    ImmutableList<StringWithMacros> getCompilerFlags();

    ImmutableList<String> getOcamldepFlags();

    ImmutableList<String> getLinkerFlags();

    Optional<String> getWarningsFlags();

    @Value.Default
    default boolean getBytecodeOnly() {
      return false;
    }

    @Value.Default
    default boolean getNativePlugin() {
      return false;
    }

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }
  }
}
