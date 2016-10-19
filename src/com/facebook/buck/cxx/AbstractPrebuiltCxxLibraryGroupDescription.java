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
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractPrebuiltCxxLibraryGroupDescription implements
    Description<AbstractPrebuiltCxxLibraryGroupDescription.Args> {

  private static final MacroFinder FINDER = new MacroFinder();
  private static final String LIB_MACRO = "lib";
  private static final String REL_LIB_MACRO = "rel-lib";

  /**
   * If the arg contains a library reference, parse it and return it's name and argument.
   */
  private Optional<Pair<String, String>> getLibRef(ImmutableSet<String> macros, String arg) {
    Optional<MacroMatchResult> result;
    try {
      result = FINDER.match(macros, arg);
    } catch (MacroException e) {
      throw new HumanReadableException(e, e.getMessage());
    }
    if (!result.isPresent()) {
      return Optional.absent();
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
  @Value.Derived
  public BuildRuleType getBuildRuleType() {
    return BuildRuleType.of("prebuilt_cxx_library_group");
  }

  @Override
  public Args createUnpopulatedConstructorArg() {
    return new Args();
  }

  /**
   * @return the link args formed from the user-provided static link line after resolving library
   *         macro references.
   */
  private Iterable<Arg> getStaticLinkArgs(
      BuildTarget target,
      SourcePathResolver pathResolver,
      ImmutableList<SourcePath> libs,
      ImmutableList<String> args) {
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
        builder.add(new SourcePathArg(pathResolver, libs.get(index)));
      } else {
        builder.add(new StringArg(arg));
      }
    }
    return builder.build();
  }

  /**
   * @return the link args formed from the user-provided shared link line after resolving library
   *         macro references.
   */
  private Iterable<Arg> getSharedLinkArgs(
      BuildTarget target,
      SourcePathResolver pathResolver,
      ImmutableMap<String, SourcePath> libs,
      ImmutableList<String> args) {
    ImmutableList.Builder<Arg> builder = ImmutableList.builder();
    for (String arg : args) {
      Optional<Pair<String, String>> libRef =
          getLibRef(ImmutableSet.of(LIB_MACRO, REL_LIB_MACRO), arg);
      if (libRef.isPresent()) {
        SourcePath lib = libs.get(libRef.get().getSecond());
        if (lib == null) {
          throw new HumanReadableException(
              "%s: library \"%s\" (in \"%s\") must refer to keys in the `sharedLibs` parameter",
              target,
              libRef.get().getSecond(),
              arg);
        }
        Arg libArg;
        if (libRef.get().getFirst().equals(LIB_MACRO)) {
          libArg = new SourcePathArg(pathResolver, lib);
        } else if (libRef.get().getFirst().equals(REL_LIB_MACRO)) {
          if (!(lib instanceof PathSourcePath)) {
            throw new HumanReadableException(
                "%s: can only link prebuilt DSOs without sonames",
                target);
          }
          libArg = new RelativeLinkArg((PathSourcePath) lib);
        } else {
          throw new IllegalStateException();
        }
        builder.add(libArg);
      } else {
        builder.add(new StringArg(arg));
      }
    }
    return builder.build();
  }

  @Override
  public <A extends Args> BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      final A args) throws NoSuchBuildTargetException {
    final SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return new CustomPrebuiltCxxLibrary(params, pathResolver) {

      private final LoadingCache<
          CxxPreprocessables.CxxPreprocessorInputCacheKey,
          ImmutableMap<BuildTarget, CxxPreprocessorInput>
          > transitiveCxxPreprocessorInputCache =
          CxxPreprocessables.getTransitiveCxxPreprocessorInputCache(this);

      @Override
      public Iterable<AndroidPackageable> getRequiredPackageables() {
        return AndroidPackageableCollector.getPackageableRules(params.getDeps());
      }

      @Override
      public void addToCollector(AndroidPackageableCollector collector) {
        collector.addNativeLinkable(this);
      }

      @Override
      public Iterable<? extends CxxPreprocessorDep> getCxxPreprocessorDeps(
          CxxPlatform cxxPlatform) {
        return FluentIterable.from(getDeps())
            .filter(CxxPreprocessorDep.class);
      }

      @Override
      public Optional<HeaderSymlinkTree> getExportedHeaderSymlinkTree(CxxPlatform cxxPlatform) {
        return Optional.absent();
      }

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput(
          CxxPlatform cxxPlatform,
          HeaderVisibility headerVisibility)
          throws NoSuchBuildTargetException {
        CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();

        switch (headerVisibility) {
          case PUBLIC:
            builder.putAllPreprocessorFlags(
                CxxFlags.getLanguageFlags(
                    args.exportedPreprocessorFlags,
                    PatternMatchedCollection.of(),
                    ImmutableMap.of(),
                    cxxPlatform));
            for (SourcePath includeDir : args.includeDirs) {
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
          CxxPlatform cxxPlatform,
          HeaderVisibility headerVisibility)
          throws NoSuchBuildTargetException {
        return transitiveCxxPreprocessorInputCache.getUnchecked(
            ImmutableCxxPreprocessorInputCacheKey.of(cxxPlatform, headerVisibility));
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableDeps(CxxPlatform cxxPlatform) {
        return FluentIterable.from(params.getDeclaredDeps().get())
            .filter(NativeLinkable.class);
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(
          CxxPlatform cxxPlatform) {
        return FluentIterable.from(args.exportedDeps)
            .transform(resolver.getRuleFunction())
            .filter(NativeLinkable.class);
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType type)
          throws NoSuchBuildTargetException {
        NativeLinkableInput.Builder builder = NativeLinkableInput.builder();
        switch (type) {
          case STATIC:
            builder.addAllArgs(
                getStaticLinkArgs(
                    getBuildTarget(),
                    pathResolver,
                    args.staticLibs,
                    args.staticLink));
            break;
          case STATIC_PIC:
            builder.addAllArgs(
                getStaticLinkArgs(
                    getBuildTarget(),
                    pathResolver,
                    args.staticPicLibs,
                    args.staticPicLink));
            break;
          case SHARED:
            builder.addAllArgs(
                getSharedLinkArgs(
                    getBuildTarget(),
                    pathResolver,
                    ImmutableMap.<String, SourcePath>builder()
                        .putAll(args.sharedLibs)
                        .putAll(args.providedSharedLibs)
                        .build(),
                    args.sharedLink));
            break;
        }
        return builder.build();
      }

      @Override
      public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {

        // If we both shared and static libs, we support any linkage.
        if (!args.sharedLink.isEmpty() &&
            !(args.staticLink.isEmpty() && args.staticPicLink.isEmpty())) {
          return Linkage.ANY;
        }

        // Otherwise, if we have a shared library, we only support shared linkage.
        if (!args.sharedLink.isEmpty()) {
          return Linkage.SHARED;
        }

        // Otherwise, if we have a static library, we only support static linkage.
        if (!(args.staticLink.isEmpty() && args.staticPicLink.isEmpty())) {
          return Linkage.STATIC;
        }

        // Otherwise, header only libs use any linkage.
        return Linkage.ANY;
      }

      @Override
      public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform)
          throws NoSuchBuildTargetException {
        return args.sharedLibs;
      }

    };
  }

  private abstract static class CustomPrebuiltCxxLibrary
      extends NoopBuildRule
      implements AbstractCxxLibrary {
    public CustomPrebuiltCxxLibrary(
        BuildRuleParams params,
        SourcePathResolver resolver) {
      super(params, resolver);
    }
  }

  @SuppressFieldNotInitialized
  public static class Args {

    public ImmutableList<String> exportedPreprocessorFlags = ImmutableList.of();
    public ImmutableList<SourcePath> includeDirs = ImmutableList.of();

    /**
     * The link arguments to use when linking using the static link style.
     */
    public ImmutableList<String> staticLink = ImmutableList.of();

    /**
     * Libraries references in the static link args above.
     */
    public ImmutableList<SourcePath> staticLibs = ImmutableList.of();

    /**
     * The link arguments to use when linking using the static-pic link style.
     */
    public ImmutableList<String> staticPicLink = ImmutableList.of();

    /**
     * Libraries references in the static-pic link args above.
     */
    public ImmutableList<SourcePath> staticPicLibs = ImmutableList.of();

    /**
     * The link arguments to use when linking using the shared link style.
     */
    public ImmutableList<String> sharedLink = ImmutableList.of();

    /**
     * Libraries references in the shared link args above.
     */
    public ImmutableMap<String, SourcePath> sharedLibs = ImmutableMap.of();
    public ImmutableMap<String, SourcePath> providedSharedLibs = ImmutableMap.of();

    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public ImmutableSortedSet<BuildTarget> exportedDeps = ImmutableSortedSet.of();

  }

}
