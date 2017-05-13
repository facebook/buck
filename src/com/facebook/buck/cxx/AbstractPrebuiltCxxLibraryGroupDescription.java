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

package com.facebook.buck.cxx;

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.model.MacroFinder;
import com.facebook.buck.model.MacroMatchResult;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.regex.Pattern;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractPrebuiltCxxLibraryGroupDescription
    implements Description<PrebuiltCxxLibraryGroupDescriptionArg>,
        VersionPropagator<PrebuiltCxxLibraryGroupDescriptionArg> {

  private static final MacroFinder FINDER = new MacroFinder();
  private static final String LIB_MACRO = "lib";
  private static final String REL_LIB_MACRO = "rel-lib";

  /** If the arg contains a library reference, parse it and return it's name and argument. */
  private Optional<Pair<String, String>> getLibRef(ImmutableSet<String> macros, String arg) {
    Optional<MacroMatchResult> result;
    try {
      result = FINDER.match(macros, arg);
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
  private Iterable<Arg> getStaticLinkArgs(
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
  private Iterable<Arg> getSharedLinkArgs(
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
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      final PrebuiltCxxLibraryGroupDescriptionArg args)
      throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    return new CustomPrebuiltCxxLibrary(params) {

      private final LoadingCache<
              CxxPreprocessables.CxxPreprocessorInputCacheKey,
              ImmutableMap<BuildTarget, CxxPreprocessorInput>>
          transitiveCxxPreprocessorInputCache =
              CxxPreprocessables.getTransitiveCxxPreprocessorInputCache(this);

      @Override
      public Iterable<AndroidPackageable> getRequiredPackageables() {
        return AndroidPackageableCollector.getPackageableRules(params.getBuildDeps());
      }

      @Override
      public void addToCollector(AndroidPackageableCollector collector) {
        collector.addNativeLinkable(this);
      }

      @Override
      public Iterable<? extends CxxPreprocessorDep> getCxxPreprocessorDeps(
          CxxPlatform cxxPlatform) {
        if (!isPlatformSupported(cxxPlatform)) {
          return ImmutableList.of();
        }
        return FluentIterable.from(getBuildDeps()).filter(CxxPreprocessorDep.class);
      }

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput(
          CxxPlatform cxxPlatform, HeaderVisibility headerVisibility)
          throws NoSuchBuildTargetException {
        CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();

        switch (headerVisibility) {
          case PUBLIC:
            builder.putAllPreprocessorFlags(
                CxxFlags.getLanguageFlags(
                    args.getExportedPreprocessorFlags(),
                    PatternMatchedCollection.of(),
                    ImmutableMap.of(),
                    cxxPlatform));
            for (SourcePath includeDir : args.getIncludeDirs()) {
              builder.addIncludes(
                  CxxHeadersDir.of(CxxPreprocessables.IncludeType.SYSTEM, includeDir));
            }
            return builder.build();
          case PRIVATE:
            return builder.build();
        }

        // We explicitly don't put this in a default statement because we
        // want the compiler to warn if someone modifies the HeaderVisibility enum.
        throw new RuntimeException("Invalid header visibility: " + headerVisibility);
      }

      @Override
      public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
          CxxPlatform cxxPlatform, HeaderVisibility headerVisibility)
          throws NoSuchBuildTargetException {
        return transitiveCxxPreprocessorInputCache.getUnchecked(
            ImmutableCxxPreprocessorInputCacheKey.of(cxxPlatform, headerVisibility));
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableDeps() {
        return FluentIterable.from(params.getDeclaredDeps().get()).filter(NativeLinkable.class);
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps() {
        return FluentIterable.from(args.getExportedDeps())
            .transform(resolver::getRule)
            .filter(NativeLinkable.class);
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          CxxPlatform cxxPlatform, Linker.LinkableDepType type) throws NoSuchBuildTargetException {
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
                        resolver, ruleFinder, cxxPlatform, args.getStaticLibs()),
                    args.getStaticLink()));
            break;
          case STATIC_PIC:
            builder.addAllArgs(
                getStaticLinkArgs(
                    getBuildTarget(),
                    CxxGenruleDescription.fixupSourcePaths(
                        resolver, ruleFinder, cxxPlatform, args.getStaticPicLibs()),
                    args.getStaticPicLink()));
            break;
          case SHARED:
            builder.addAllArgs(
                getSharedLinkArgs(
                    getBuildTarget(),
                    CxxGenruleDescription.fixupSourcePaths(
                        resolver,
                        ruleFinder,
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
      public Iterable<? extends NativeLinkable> getNativeLinkableDepsForPlatform(
          CxxPlatform cxxPlatform) {
        if (!isPlatformSupported(cxxPlatform)) {
          return ImmutableList.of();
        }
        return getNativeLinkableDeps();
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableExportedDepsForPlatform(
          CxxPlatform cxxPlatform) {
        if (!isPlatformSupported(cxxPlatform)) {
          return ImmutableList.of();
        }
        return getNativeLinkableExportedDeps();
      }

      @Override
      public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform)
          throws NoSuchBuildTargetException {
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
    };
  }

  private abstract static class CustomPrebuiltCxxLibrary extends NoopBuildRule
      implements AbstractCxxLibrary {
    public CustomPrebuiltCxxLibrary(BuildRuleParams params) {
      super(params);
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractPrebuiltCxxLibraryGroupDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps {
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

    ImmutableMap<String, SourcePath> getProvidedSharedLibs();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExportedDeps();

    Optional<Pattern> getSupportedPlatformsRegex();
  }
}
