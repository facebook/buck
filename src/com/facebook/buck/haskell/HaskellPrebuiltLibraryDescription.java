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
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.ImmutableCxxPreprocessorInputCacheKey;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      final HaskellPrebuiltLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {
    return new PrebuiltHaskellLibrary(params) {

      private final LoadingCache<
              CxxPreprocessables.CxxPreprocessorInputCacheKey,
              ImmutableMap<BuildTarget, CxxPreprocessorInput>>
          transitiveCxxPreprocessorInputCache =
              CxxPreprocessables.getTransitiveCxxPreprocessorInputCache(this);

      @Override
      public Iterable<BuildRule> getCompileDeps(CxxPlatform cxxPlatform) {
        return RichStream.from(args.getDeps())
            .map(resolver::getRule)
            .filter(HaskellCompileDep.class::isInstance)
            .toImmutableList();
      }

      @Override
      public HaskellCompileInput getCompileInput(
          CxxPlatform cxxPlatform, Linker.LinkableDepType depType)
          throws NoSuchBuildTargetException {
        return HaskellCompileInput.builder()
            .addAllFlags(args.getExportedCompilerFlags())
            .addPackages(
                HaskellPackage.builder()
                    .setInfo(
                        HaskellPackageInfo.of(
                            getBuildTarget().getShortName(), args.getVersion(), args.getId()))
                    .setPackageDb(args.getDb())
                    .addAllInterfaces(args.getImportDirs())
                    .addAllLibraries(
                        depType == Linker.LinkableDepType.SHARED
                            ? args.getSharedLibs().values()
                            : args.getStaticLibs())
                    .build())
            .build();
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableDeps() {
        return ImmutableList.of();
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps() {
        return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkable.class);
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          CxxPlatform cxxPlatform, Linker.LinkableDepType type) {
        NativeLinkableInput.Builder builder = NativeLinkableInput.builder();
        builder.addAllArgs(StringArg.from(args.getExportedLinkerFlags()));
        if (type == Linker.LinkableDepType.SHARED) {
          builder.addAllArgs(SourcePathArg.from(args.getSharedLibs().values()));
        } else {
          builder.addAllArgs(SourcePathArg.from(args.getStaticLibs()));
        }
        return builder.build();
      }

      @Override
      public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
        return Linkage.ANY;
      }

      @Override
      public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
        return args.getSharedLibs();
      }

      @Override
      public Iterable<? extends CxxPreprocessorDep> getCxxPreprocessorDeps(
          CxxPlatform cxxPlatform) {
        return FluentIterable.from(getBuildDeps()).filter(CxxPreprocessorDep.class);
      }

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput(
          CxxPlatform cxxPlatform, HeaderVisibility headerVisibility)
          throws NoSuchBuildTargetException {
        CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();
        for (SourcePath headerDir : args.getCxxHeaderDirs()) {
          builder.addIncludes(CxxHeadersDir.of(CxxPreprocessables.IncludeType.SYSTEM, headerDir));
        }
        return builder.build();
      }

      @Override
      public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
          CxxPlatform cxxPlatform, HeaderVisibility headerVisibility)
          throws NoSuchBuildTargetException {
        return transitiveCxxPreprocessorInputCache.getUnchecked(
            ImmutableCxxPreprocessorInputCacheKey.of(cxxPlatform, headerVisibility));
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

    ImmutableMap<String, SourcePath> getSharedLibs();

    ImmutableList<String> getExportedLinkerFlags();

    ImmutableList<String> getExportedCompilerFlags();

    @Value.NaturalOrder
    ImmutableSortedSet<SourcePath> getCxxHeaderDirs();
  }
}
