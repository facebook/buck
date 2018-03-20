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

import com.facebook.buck.cxx.CxxHeadersDir;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.TransitiveCxxPreprocessorInputCache;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.immutables.value.Value;

public class HaskellPrebuiltLibraryDescription
    implements Description<HaskellPrebuiltLibraryDescriptionArg>,
        VersionPropagator<HaskellPrebuiltLibraryDescriptionArg> {

  @Override
  public Class<HaskellPrebuiltLibraryDescriptionArg> getConstructorArgType() {
    return HaskellPrebuiltLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      HaskellPrebuiltLibraryDescriptionArg args) {
    BuildRuleResolver resolver = context.getBuildRuleResolver();
    return new PrebuiltHaskellLibrary(buildTarget, context.getProjectFilesystem(), params) {

      private final TransitiveCxxPreprocessorInputCache transitiveCxxPreprocessorInputCache =
          new TransitiveCxxPreprocessorInputCache(this);

      @Override
      public Iterable<BuildRule> getCompileDeps(HaskellPlatform platform) {
        return RichStream.from(args.getDeps())
            .map(resolver::getRule)
            .filter(HaskellCompileDep.class::isInstance)
            .toImmutableList();
      }

      @Override
      public HaskellCompileInput getCompileInput(
          HaskellPlatform platform, Linker.LinkableDepType depType, boolean hsProfile) {

        // Build the package.
        HaskellPackage.Builder pkgBuilder =
            HaskellPackage.builder()
                .setInfo(
                    HaskellPackageInfo.of(
                        getBuildTarget().getShortName(), args.getVersion(), args.getId()))
                .setPackageDb(args.getDb())
                .addAllInterfaces(args.getImportDirs());
        if (Linker.LinkableDepType.SHARED == depType) {
          pkgBuilder.addAllLibraries(args.getSharedLibs().values());
        } else {
          pkgBuilder.addAllLibraries(args.getStaticLibs());
          // If profiling is enabled, we also include their libs in the same package.
          if (args.isEnableProfiling() || hsProfile) {
            pkgBuilder.addAllLibraries(args.getProfiledStaticLibs());
          }
        }
        HaskellPackage pkg = pkgBuilder.build();

        return HaskellCompileInput.builder()
            .addAllFlags(args.getExportedCompilerFlags())
            .addPackages(pkg)
            .build();
      }

      @Override
      public HaskellHaddockInput getHaddockInput(HaskellPlatform platform) {
        return HaskellHaddockInput.builder()
            .addAllInterfaces(ImmutableList.of())
            .addAllOutputDirs(ImmutableList.of())
            .build();
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableDeps(
          BuildRuleResolver ruleResolver) {
        return ImmutableList.of();
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(
          BuildRuleResolver ruleResolver) {
        return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkable.class);
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType type,
          boolean forceLinkWhole,
          ImmutableSet<LanguageExtensions> languageExtensions,
          BuildRuleResolver ruleResolver) {
        NativeLinkableInput.Builder builder = NativeLinkableInput.builder();
        builder.addAllArgs(StringArg.from(args.getExportedLinkerFlags()));
        if (type == Linker.LinkableDepType.SHARED) {
          builder.addAllArgs(SourcePathArg.from(args.getSharedLibs().values()));
        } else {
          Linker linker = cxxPlatform.getLd().resolve(resolver);
          ImmutableList<Arg> libArgs =
              SourcePathArg.from(
                  args.isEnableProfiling() ? args.getProfiledStaticLibs() : args.getStaticLibs());
          if (forceLinkWhole) {
            libArgs =
                RichStream.from(libArgs)
                    .flatMap(lib -> RichStream.from(linker.linkWhole(lib)))
                    .toImmutableList();
          }
          builder.addAllArgs(libArgs);
        }
        return builder.build();
      }

      @Override
      public Linkage getPreferredLinkage(CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
        return Linkage.ANY;
      }

      @Override
      public ImmutableMap<String, SourcePath> getSharedLibraries(
          CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
        return args.getSharedLibs();
      }

      @Override
      public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(
          CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
        return FluentIterable.from(getBuildDeps()).filter(CxxPreprocessorDep.class);
      }

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput(
          CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
        CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();
        for (SourcePath headerDir : args.getCxxHeaderDirs()) {
          builder.addIncludes(CxxHeadersDir.of(CxxPreprocessables.IncludeType.SYSTEM, headerDir));
        }
        return builder.build();
      }

      @Override
      public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
          CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
        return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform, ruleResolver);
      }
    };
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractHaskellPrebuiltLibraryDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps {
    String getVersion();

    @Value.Default
    default String getId() {
      return String.format("%s-%s", getName(), getVersion());
    }

    SourcePath getDb();

    ImmutableList<SourcePath> getImportDirs();

    ImmutableList<SourcePath> getStaticLibs();

    ImmutableList<SourcePath> getProfiledStaticLibs();

    ImmutableMap<String, SourcePath> getSharedLibs();

    ImmutableList<String> getExportedLinkerFlags();

    ImmutableList<String> getExportedCompilerFlags();

    @Value.NaturalOrder
    ImmutableSortedSet<SourcePath> getCxxHeaderDirs();

    @Value.Default
    default boolean isEnableProfiling() {
      return false;
    }
  }
}
