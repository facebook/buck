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

package com.facebook.buck.cxx;

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.macros.MacroFinder;
import com.facebook.buck.core.macros.MacroMatchResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.LegacyNativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.PlatformLockedNativeLinkableGroup;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimaps;
import java.util.Optional;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class PrebuiltCxxLibraryGroupDescription
    implements DescriptionWithTargetGraph<PrebuiltCxxLibraryGroupDescriptionArg>,
        VersionPropagator<PrebuiltCxxLibraryGroupDescriptionArg> {

  private static final String LIB_MACRO = "lib";
  private static final String REL_LIB_MACRO = "rel-lib";

  /** If the arg contains a library reference, parse it and return it's name and argument. */
  private static Optional<Pair<String, String>> getLibRef(ImmutableSet<String> macros, String arg) {
    Optional<MacroMatchResult> result;
    try {
      result = MacroFinder.match(macros, arg);
    } catch (MacroException e) {
      throw new HumanReadableException(e, e.getMessage());
    }
    if (!result.isPresent()) {
      return Optional.empty();
    }
    if (result.get().getMacroType().equals("")) {
      throw new HumanReadableException("expected library reference");
    }
    if (result.get().getMacroInput().size() != 1) {
      throw new HumanReadableException("expected a single library reference argument");
    }
    return Optional.of(
        new Pair<>(result.get().getMacroType(), result.get().getMacroInput().get(0)));
  }

  @Override
  public Class<PrebuiltCxxLibraryGroupDescriptionArg> getConstructorArgType() {
    return PrebuiltCxxLibraryGroupDescriptionArg.class;
  }

  /**
   * @return the link args formed from the user-provided static link line after resolving library
   *     macro references.
   */
  private static Iterable<Arg> getStaticLinkArgs(
      BuildTarget target, ImmutableList<SourcePath> libs, ImmutableList<String> args) {
    ImmutableList.Builder<Arg> builder = ImmutableList.builder();
    for (String arg : args) {
      Optional<Pair<String, String>> libRef = getLibRef(ImmutableSet.of(LIB_MACRO), arg);
      if (libRef.isPresent()) {
        int index;
        try {
          index = Integer.parseInt(libRef.get().getSecond());
        } catch (NumberFormatException e) {
          throw new HumanReadableException("%s: ", target);
        }
        if (index < 0 || index >= libs.size()) {
          throw new HumanReadableException("%s: ", target);
        }
        builder.add(SourcePathArg.of(libs.get(index)));
      } else {
        builder.add(StringArg.of(arg));
      }
    }
    return builder.build();
  }

  /**
   * @return the link args formed from the user-provided shared link line after resolving library
   *     macro references.
   */
  private static Iterable<Arg> getSharedLinkArgs(
      BuildTarget target, ImmutableMap<String, SourcePath> libs, ImmutableList<String> args) {
    ImmutableList.Builder<Arg> builder = ImmutableList.builder();
    for (String arg : args) {
      Optional<Pair<String, String>> libRef =
          getLibRef(ImmutableSet.of(LIB_MACRO, REL_LIB_MACRO), arg);
      if (libRef.isPresent()) {
        SourcePath lib = libs.get(libRef.get().getSecond());
        if (lib == null) {
          throw new HumanReadableException(
              "%s: library \"%s\" (in \"%s\") must refer to keys in the `sharedLibs` parameter",
              target, libRef.get().getSecond(), arg);
        }
        Arg libArg;
        if (libRef.get().getFirst().equals(LIB_MACRO)) {
          libArg = SourcePathArg.of(lib);
        } else if (libRef.get().getFirst().equals(REL_LIB_MACRO)) {
          if (!(lib instanceof PathSourcePath)) {
            throw new HumanReadableException(
                "%s: can only link prebuilt DSOs without sonames", target);
          }
          libArg = new RelativeLinkArg((PathSourcePath) lib);
        } else {
          throw new IllegalStateException();
        }
        builder.add(libArg);
      } else {
        builder.add(StringArg.of(arg));
      }
    }
    return builder.build();
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PrebuiltCxxLibraryGroupDescriptionArg args) {
    return new CustomPrebuiltCxxLibrary(buildTarget, context.getProjectFilesystem(), params, args);
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  /**
   * An action graph representation of a custom prebuilt C/C++ library from the target graph,
   * providing the various interfaces to make it consumable by C/C++ preprocessing and native
   * linkable rules.
   */
  public static class CustomPrebuiltCxxLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps
      implements AbstractCxxLibraryGroup, LegacyNativeLinkableGroup {

    private final TransitiveCxxPreprocessorInputCache transitiveCxxPreprocessorInputCache =
        new TransitiveCxxPreprocessorInputCache(this);
    private PlatformLockedNativeLinkableGroup.Cache linkableCache =
        LegacyNativeLinkableGroup.getNativeLinkableCache(this);

    private final PrebuiltCxxLibraryGroupDescriptionArg args;
    private final CxxDeps allExportedDeps;

    public CustomPrebuiltCxxLibrary(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams params,
        PrebuiltCxxLibraryGroupDescriptionArg args) {
      super(buildTarget, projectFilesystem, params);
      this.args = args;
      this.allExportedDeps =
          CxxDeps.builder()
              .addDeps(args.getExportedDeps())
              .addPlatformDeps(args.getExportedPlatformDeps())
              .build();
    }

    @Override
    public boolean isPrebuiltSOForHaskellOmnibus(
        CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
      return true;
    }

    @Override
    public PlatformLockedNativeLinkableGroup.Cache getNativeLinkableCompatibilityCache() {
      return linkableCache;
    }

    @Override
    public Iterable<AndroidPackageable> getRequiredPackageables(BuildRuleResolver ruleResolver) {
      return AndroidPackageableCollector.getPackageableRules(getBuildDeps());
    }

    @Override
    public void addToCollector(AndroidPackageableCollector collector) {
      collector.addNativeLinkable(this);
    }

    @Override
    public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(
        CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
      if (!isPlatformSupported(cxxPlatform)) {
        return ImmutableList.of();
      }
      return FluentIterable.from(getBuildDeps()).filter(CxxPreprocessorDep.class);
    }

    @Override
    public CxxPreprocessorInput getCxxPreprocessorInput(
        CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
      CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();
      builder.putAllPreprocessorFlags(
          ImmutableListMultimap.copyOf(
              Multimaps.transformValues(
                  CxxFlags.getLanguageFlags(
                      args.getExportedPreprocessorFlags(),
                      PatternMatchedCollection.of(),
                      ImmutableMap.of(),
                      cxxPlatform),
                  StringArg::of)));
      for (SourcePath includeDir : args.getIncludeDirs()) {
        builder.addIncludes(CxxHeadersDir.of(CxxPreprocessables.IncludeType.SYSTEM, includeDir));
      }
      return builder.build();
    }

    @Override
    public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
        CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
      return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform, graphBuilder);
    }

    @Override
    public Iterable<? extends NativeLinkableGroup> getNativeLinkableDeps(
        BuildRuleResolver ruleResolver) {
      return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkableGroup.class);
    }

    @Override
    public Iterable<? extends NativeLinkableGroup> getNativeLinkableExportedDeps(
        BuildRuleResolver ruleResolver) {
      return FluentIterable.from(args.getExportedDeps())
          .transform(ruleResolver::getRule)
          .filter(NativeLinkableGroup.class);
    }

    @Override
    public NativeLinkableInput getNativeLinkableInput(
        CxxPlatform cxxPlatform,
        Linker.LinkableDepType type,
        boolean forceLinkWhole,
        ActionGraphBuilder graphBuilder,
        TargetConfiguration targetConfiguration) {

      if (!isPlatformSupported(cxxPlatform)) {
        return NativeLinkableInput.of();
      }
      NativeLinkableInput.Builder builder = NativeLinkableInput.builder();
      switch (type) {
        case STATIC:
          builder.addAllArgs(
              getStaticLinkArgs(
                  getBuildTarget(),
                  CxxGenruleDescription.fixupSourcePaths(
                      graphBuilder, cxxPlatform, args.getStaticLibs()),
                  args.getStaticLink()));
          break;
        case STATIC_PIC:
          builder.addAllArgs(
              getStaticLinkArgs(
                  getBuildTarget(),
                  CxxGenruleDescription.fixupSourcePaths(
                      graphBuilder, cxxPlatform, args.getStaticPicLibs()),
                  args.getStaticPicLink()));
          break;
        case SHARED:
          builder.addAllArgs(
              getSharedLinkArgs(
                  getBuildTarget(),
                  CxxGenruleDescription.fixupSourcePaths(
                      graphBuilder,
                      cxxPlatform,
                      ImmutableMap.<String, SourcePath>builder()
                          .putAll(args.getSharedLibs())
                          .putAll(args.getProvidedSharedLibs())
                          .build()),
                  args.getSharedLink()));
          break;
      }
      return builder.build();
    }

    @Override
    public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {

      // If we both shared and static libs, we support any linkage.
      if (!args.getSharedLink().isEmpty()
          && !(args.getStaticLink().isEmpty() && args.getStaticPicLink().isEmpty())) {
        return Linkage.ANY;
      }

      // Otherwise, if we have a shared library, we only support shared linkage.
      if (!args.getSharedLink().isEmpty()) {
        return Linkage.SHARED;
      }

      // Otherwise, if we have a static library, we only support static linkage.
      if (!(args.getStaticLink().isEmpty() && args.getStaticPicLink().isEmpty())) {
        return Linkage.STATIC;
      }

      // Otherwise, header only libs use any linkage.
      return Linkage.ANY;
    }

    @Override
    public boolean supportsOmnibusLinking(CxxPlatform cxxPlatform) {
      return getPreferredLinkage(cxxPlatform) != Linkage.SHARED;
    }

    @Override
    public Iterable<? extends NativeLinkableGroup> getNativeLinkableDepsForPlatform(
        CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
      if (!isPlatformSupported(cxxPlatform)) {
        return ImmutableList.of();
      }
      return getNativeLinkableDeps(ruleResolver);
    }

    @Override
    public Iterable<? extends NativeLinkableGroup> getNativeLinkableExportedDepsForPlatform(
        CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
      if (!isPlatformSupported(cxxPlatform)) {
        return ImmutableList.of();
      }
      return RichStream.from(allExportedDeps.get(graphBuilder, cxxPlatform))
          .filter(NativeLinkableGroup.class)
          .toImmutableList();
    }

    @Override
    public ImmutableMap<String, SourcePath> getSharedLibraries(
        CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
      if (!isPlatformSupported(cxxPlatform)) {
        return ImmutableMap.of();
      }
      return args.getSharedLibs();
    }

    private boolean isPlatformSupported(CxxPlatform cxxPlatform) {
      return !args.getSupportedPlatformsRegex().isPresent()
          || args.getSupportedPlatformsRegex()
              .get()
              .matcher(cxxPlatform.getFlavor().toString())
              .find();
    }
  }

  @RuleArg
  interface AbstractPrebuiltCxxLibraryGroupDescriptionArg extends BuildRuleArg, HasDeclaredDeps {
    ImmutableList<String> getExportedPreprocessorFlags();

    ImmutableList<SourcePath> getIncludeDirs();

    /** The link arguments to use when linking using the static link style. */
    ImmutableList<String> getStaticLink();

    /** Libraries references in the static link args above. */
    ImmutableList<SourcePath> getStaticLibs();

    /** The link arguments to use when linking using the static-pic link style. */
    ImmutableList<String> getStaticPicLink();

    /** Libraries references in the static-pic link args above. */
    ImmutableList<SourcePath> getStaticPicLibs();

    /** The link arguments to use when linking using the shared link style. */
    ImmutableList<String> getSharedLink();

    /** Libraries references in the shared link args above. */
    ImmutableMap<String, SourcePath> getSharedLibs();

    /** Import libraries references in the shared link args above. */
    ImmutableMap<String, SourcePath> getImportLibs();

    ImmutableMap<String, SourcePath> getProvidedSharedLibs();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExportedDeps();

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getExportedPlatformDeps() {
      return PatternMatchedCollection.of();
    }

    Optional<Pattern> getSupportedPlatformsRegex();
  }
}
