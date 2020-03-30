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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxHeadersDir;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.TransitiveCxxPreprocessorInputCache;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInfo;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.PlatformMappedCache;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

public class HaskellPrebuiltLibraryDescription
    implements DescriptionWithTargetGraph<HaskellPrebuiltLibraryDescriptionArg>,
        VersionPropagator<HaskellPrebuiltLibraryDescriptionArg> {

  @Override
  public Class<HaskellPrebuiltLibraryDescriptionArg> getConstructorArgType() {
    return HaskellPrebuiltLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      HaskellPrebuiltLibraryDescriptionArg args) {
    BuildRuleResolver resolver = context.getActionGraphBuilder();
    return new PrebuiltHaskellLibrary(buildTarget, context.getProjectFilesystem(), params) {

      private final TransitiveCxxPreprocessorInputCache transitiveCxxPreprocessorInputCache =
          new TransitiveCxxPreprocessorInputCache(this);
      private final PlatformMappedCache<NativeLinkableInfo> linkableCache =
          new PlatformMappedCache<>();

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

        return ImmutableHaskellCompileInput.of(args.getExportedCompilerFlags(), pkg);
      }

      @Override
      public HaskellHaddockInput getHaddockInput(HaskellPlatform platform) {
        return ImmutableHaskellHaddockInput.of(ImmutableList.of(), ImmutableList.of());
      }

      public NativeLinkableInput getNativeLinkableInput(
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType type,
          boolean forceLinkWhole,
          ActionGraphBuilder graphBuilder,
          TargetConfiguration targetConfiguration) {
        NativeLinkableInput.Builder builder = NativeLinkableInput.builder();
        builder.addAllArgs(StringArg.from(args.getExportedLinkerFlags()));
        if (type == Linker.LinkableDepType.SHARED) {
          builder.addAllArgs(SourcePathArg.from(args.getSharedLibs().values()));
        } else {
          Linker linker = cxxPlatform.getLd().resolve(resolver, targetConfiguration);
          ImmutableList<Arg> libArgs =
              SourcePathArg.from(
                  args.isEnableProfiling() ? args.getProfiledStaticLibs() : args.getStaticLibs());
          if (forceLinkWhole) {
            libArgs =
                RichStream.from(libArgs)
                    .flatMap(
                        lib ->
                            RichStream.from(
                                linker.linkWhole(lib, graphBuilder.getSourcePathResolver())))
                    .toImmutableList();
          }
          builder.addAllArgs(libArgs);
        }
        return builder.build();
      }

      @Override
      public Optional<Iterable<? extends NativeLinkableGroup>> getOmnibusPassthroughDeps(
          CxxPlatform platform, ActionGraphBuilder graphBuilder) {
        // Haskell prebuilt libraries don't have platform-specific deps so just return all of them.
        return Optional.of(getAllLinkableDeps());
      }

      @Override
      public NativeLinkable getNativeLinkable(
          CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        return linkableCache.get(
            cxxPlatform,
            () -> {
              ImmutableList<NativeLinkable> exportedDeps =
                  getAllLinkableDeps()
                      .transform(g -> g.getNativeLinkable(cxxPlatform, graphBuilder))
                      .toList();
              return new NativeLinkableInfo(
                  buildTarget,
                  getType(),
                  ImmutableList.of(),
                  exportedDeps,
                  Linkage.ANY,
                  new NativeLinkableInfo.Delegate() {
                    @Override
                    public NativeLinkableInput computeInput(
                        ActionGraphBuilder graphBuilder,
                        Linker.LinkableDepType type,
                        boolean forceLinkWhole,
                        TargetConfiguration targetConfiguration) {
                      return getNativeLinkableInput(
                          cxxPlatform, type, forceLinkWhole, graphBuilder, targetConfiguration);
                    }

                    @Override
                    public ImmutableMap<String, SourcePath> getSharedLibraries(
                        ActionGraphBuilder graphBuilder) {
                      return args.getSharedLibs();
                    }
                  },
                  NativeLinkableInfo.defaults());
            });
      }

      public FluentIterable<NativeLinkableGroup> getAllLinkableDeps() {
        return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkableGroup.class);
      }

      @Override
      public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(
          CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
        return FluentIterable.from(getBuildDeps()).filter(CxxPreprocessorDep.class);
      }

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput(
          CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();
        for (SourcePath headerDir : args.getCxxHeaderDirs()) {
          builder.addIncludes(CxxHeadersDir.of(CxxPreprocessables.IncludeType.SYSTEM, headerDir));
        }
        return builder.build();
      }

      @Override
      public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
          CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform, graphBuilder);
      }
    };
  }

  @RuleArg
  interface AbstractHaskellPrebuiltLibraryDescriptionArg extends BuildRuleArg, HasDeclaredDeps {
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
