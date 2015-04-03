/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.macros.MacroException;
import com.facebook.buck.rules.macros.MacroFinder;
import com.facebook.buck.rules.macros.MacroReplacer;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Map;

public class PrebuiltCxxLibraryDescription
    implements Description<PrebuiltCxxLibraryDescription.Arg> {

  private static final MacroFinder MACRO_FINDER = new MacroFinder();

  private static enum Type {
    SHARED,
  }

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      new FlavorDomain<>(
          "C/C++ Library Type",
          ImmutableMap.of(
              CxxDescriptionEnhancer.SHARED_FLAVOR, Type.SHARED));

  public static final BuildRuleType TYPE = BuildRuleType.of("prebuilt_cxx_library");

  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public PrebuiltCxxLibraryDescription(FlavorDomain<CxxPlatform> cxxPlatforms) {
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

  // Using the {@code MACRO_FINDER} above, return the given string with any `platform` macros
  // replaced with the name of the given platform.
  private static String expandPlatform(
      BuildTarget target,
      final CxxPlatform cxxPlatform,
      String arg) {
    try {
      return MACRO_FINDER.replace(
          ImmutableMap.<String, MacroReplacer>of(
              "platform",
              new MacroReplacer() {
                @Override
                public String replace(String input) throws MacroException {
                  return cxxPlatform.getFlavor().toString();
                }
              }),
          arg);
    } catch (MacroException e) {
      throw new HumanReadableException("%s: %s", target, e.getMessage());
    }
  }

  // Resolve the given optional arg, falling back to the given default if not present and
  // expanding platform macros otherwise.
  private static String getOptionalArg(
      BuildTarget target,
      CxxPlatform cxxPlatform,
      Optional<String> arg,
      String defaultValue) {
    if (!arg.isPresent()) {
      return defaultValue;
    }
    return expandPlatform(target, cxxPlatform, arg.get());
  }

  private static String getLibDir(
      BuildTarget target,
      CxxPlatform cxxPlatform,
      Optional<String> libDir) {
    return getOptionalArg(target, cxxPlatform, libDir, "lib");
  }

  private static String getLibName(
      BuildTarget target,
      CxxPlatform cxxPlatform,
      Optional<String> libName) {
    return getOptionalArg(target, cxxPlatform, libName, target.getShortName());
  }

  public static String getSoname(
      BuildTarget target,
      CxxPlatform cxxPlatform,
      Optional<String> soname,
      Optional<String> libName) {
    return getOptionalArg(
        target,
        cxxPlatform,
        soname,
        String.format(
            "lib%s.%s",
            getLibName(target, cxxPlatform, libName),
            cxxPlatform.getSharedLibraryExtension()));
  }

  private static Path getLibraryPath(
      BuildTarget target,
      CxxPlatform cxxPlatform,
      Optional<String> libDir,
      Optional<String> libName,
      String suffix) {
    return target.getBasePath()
        .resolve(getLibDir(target, cxxPlatform, libDir))
        .resolve(String.format("lib%s%s", getLibName(target, cxxPlatform, libName), suffix));
  }

  public static Path getSharedLibraryPath(
      BuildTarget target,
      CxxPlatform cxxPlatform,
      Optional<String> libDir,
      Optional<String> libName) {
    return getLibraryPath(
        target,
        cxxPlatform,
        libDir,
        libName,
        String.format(".%s", cxxPlatform.getSharedLibraryExtension()));
  }

  public static Path getStaticLibraryPath(
      BuildTarget target,
      CxxPlatform cxxPlatform,
      Optional<String> libDir,
      Optional<String> libName) {
    return getLibraryPath(target, cxxPlatform, libDir, libName, ".a");
  }

  private <A extends Arg> BuildRule createSharedLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      A args) {

    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);

    BuildTarget target = params.getBuildTarget();
    String soname = getSoname(target, cxxPlatform, args.soname, args.libName);
    Path staticLibraryPath = getStaticLibraryPath(target, cxxPlatform, args.libDir, args.libName);

    // Otherwise, we need to build it from the static lib.
    BuildTarget sharedTarget = BuildTarget
        .builder(params.getBuildTarget())
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();

    // If not, setup a single link rule to link it from the static lib.
    Path builtSharedLibraryPath = BuildTargets.getScratchPath(sharedTarget, "%s").resolve(soname);
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxPlatform,
        params,
        pathResolver,
        /* extraCxxLdFlags */ ImmutableList.<String>of(),
        /* extraLdFlags */ ImmutableList.<String>of(),
        sharedTarget,
        Linker.LinkType.SHARED,
        Optional.of(soname),
        builtSharedLibraryPath,
        ImmutableList.<SourcePath>of(
            new PathSourcePath(params.getProjectFilesystem(), staticLibraryPath)),
        Linker.LinkableDepType.SHARED,
        params.getDeps());
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {

    // See if we're building a particular "type" of this library, and if so, extract
    // it as an enum.
    Optional<Map.Entry<Flavor, Type>> type;
    Optional<Map.Entry<Flavor, CxxPlatform>> platform;
    try {
      type = LIBRARY_TYPE.getFlavorAndValue(
          ImmutableSet.copyOf(params.getBuildTarget().getFlavors()));
      platform = cxxPlatforms.getFlavorAndValue(
          ImmutableSet.copyOf(params.getBuildTarget().getFlavors()));
    } catch (FlavorDomainException e) {
      throw new HumanReadableException("%s: %s", params.getBuildTarget(), e.getMessage());
    }

    // If we *are* building a specific type of this lib, call into the type specific
    // rule builder methods.  Currently, we only support building a shared lib from the
    // pre-existing static lib, which we do here.
    if (type.isPresent()) {
      Preconditions.checkState(type.get().getValue() == Type.SHARED);
      Preconditions.checkState(platform.isPresent());
      return createSharedLibraryBuildRule(params, resolver, platform.get().getValue(), args);
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    final BuildTarget target = params.getBuildTarget();

    // Resolve all the target-base-path-relative include paths to their full paths.
    Function<String, Path> fullPathFn = new Function<String, Path>() {
      @Override
      public Path apply(String input) {
        return target.getBasePath().resolve(input);
      }
    };
    final ImmutableList<Path> includeDirs = FluentIterable
        .from(args.includeDirs.or(ImmutableList.of("include")))
        .transform(fullPathFn)
        .toList();

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return new PrebuiltCxxLibrary(
        params,
        resolver,
        pathResolver,
        includeDirs,
        args.libDir,
        args.libName,
        args.exportedLinkerFlags.get(),
        args.exportedPlatformLinkerFlags.get(),
        args.soname,
        args.headerOnly.or(false),
        args.linkWhole.or(false),
        args.provided.or(false));
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<ImmutableList<String>> includeDirs;
    public Optional<String> libName;
    public Optional<String> libDir;
    public Optional<Boolean> headerOnly;
    public Optional<Boolean> provided;
    public Optional<Boolean> linkWhole;
    public Optional<ImmutableList<String>> exportedLinkerFlags;
    public Optional<ImmutableList<Pair<String, ImmutableList<String>>>> exportedPlatformLinkerFlags;
    public Optional<String> soname;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }

}
