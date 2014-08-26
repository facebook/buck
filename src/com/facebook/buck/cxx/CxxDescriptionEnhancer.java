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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class CxxDescriptionEnhancer {

  private static final Flavor HEADER_FLAVOR = new Flavor("header");
  private static final Flavor HEADER_SYMLINK_TREE_FLAVOR = new Flavor("header-symlink-tree");

  private CxxDescriptionEnhancer() {}

  /**
   * @return the {@link BuildTarget} to use for the {@link BuildRule} tracking the headers for
   *    this rule.
   */
  public static BuildTarget createHeaderTarget(BuildTarget target) {
    return BuildTargets.extendFlavoredBuildTarget(target, HEADER_FLAVOR);
  }

  /**
   * @return the {@link BuildTarget} to use for the {@link BuildRule} generating the
   *    symlink tree of headers.
   */
  public static BuildTarget createHeaderSymlinkTreeTarget(BuildTarget target) {
    return BuildTargets.extendFlavoredBuildTarget(target, HEADER_SYMLINK_TREE_FLAVOR);
  }

  /**
   * @return the {@link Path} to use for the symlink tree of headers.
   */
  public static Path getHeaderSymlinkTreePath(BuildTarget target) {
    return BuildTargets.getGenPath(
        createHeaderSymlinkTreeTarget(target),
        "%s");
  }

  /**
   * @return a map of header locations to input {@link SourcePath} objects formed by parsing the
   *    input {@link SourcePath} objects for the "headers" parameter.
   */
  public static ImmutableMap<Path, SourcePath> parseHeaders(
      BuildTarget target,
      Iterable<SourcePath> inputs) {

    return CxxPreprocessables.resolveHeaderMap(
        target,
        SourcePaths.getSourcePathNames(
            target,
            "headers",
            inputs));
  }

  /**
   * @return a list {@link CxxSource} objects formed by parsing the input {@link SourcePath}
   *    objects for the "srcs" parameter.
   */
  public static ImmutableList<CxxSource> parseCxxSources(
      BuildTarget target,
      Iterable<SourcePath> inputs) {

    return CxxCompilableEnhancer.resolveCxxSources(
        SourcePaths.getSourcePathNames(
            target,
            "srcs",
            inputs));
  }

  public static CxxPreprocessorInput createHeaderBuildRules(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxBuckConfig config,
      ImmutableList<String> preprocessorFlags,
      ImmutableMap<Path, SourcePath> headers) {

    // Setup the header and symlink tree rules
    BuildTarget headerTarget = createHeaderTarget(params.getBuildTarget());
    BuildTarget headerSymlinkTreeTarget = createHeaderSymlinkTreeTarget(params.getBuildTarget());
    Path headerSymlinkTreeRoot = getHeaderSymlinkTreePath(params.getBuildTarget());
    ImmutableSortedSet<BuildRule> headerRules = CxxPreprocessables.createHeaderBuildRules(
        headerTarget,
        headerSymlinkTreeTarget,
        headerSymlinkTreeRoot,
        params,
        headers);
    resolver.addAllToIndex(headerRules);

    // Write the compile rules for all C/C++ sources in this rule.
    CxxPreprocessorInput cxxPreprocessorInputFromDeps =
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(
            FluentIterable.from(params.getDeps())
                .filter(Predicates.instanceOf(CxxPreprocessorDep.class)));

    return CxxPreprocessorInput.concat(
        ImmutableList.of(
            new CxxPreprocessorInput(
                ImmutableSet.of(headerTarget, headerSymlinkTreeTarget),
                /* cppflags */ ImmutableList.<String>builder()
                .addAll(config.getCppFlags())
                .addAll(preprocessorFlags)
                .build(),
                /* cxxppflags */ ImmutableList.<String>builder()
                .addAll(config.getCxxppFlags())
                .addAll(preprocessorFlags)
                .build(),
                /* includes */ ImmutableList.of(headerSymlinkTreeRoot),
                /* systemIncludes */ ImmutableList.<Path>of()),
            cxxPreprocessorInputFromDeps));

  }

  /**
   * Build up the rules to track headers and compile sources for descriptions which handle C/C++
   * sources and headers.
   *
   * @return a list of {@link SourcePath} objects representing the object files from the result of
   *    compiling the given C/C++ source.
   */
  public static ImmutableList<SourcePath> createPreprocessAndCompileBuildRules(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxBuckConfig config,
      CxxPreprocessorInput cxxPreprocessorInput,
      ImmutableList<String> compilerFlags,
      boolean pic,
      ImmutableList<CxxSource> sources) {

    ImmutableSortedSet<BuildRule> objectRules = CxxCompilableEnhancer.createCompileBuildRules(
        params,
        resolver,
        config.getCompiler().or(CxxCompilables.DEFAULT_CXX_COMPILER),
        cxxPreprocessorInput,
        compilerFlags,
        pic,
        sources);
    resolver.addAllToIndex(objectRules);

    return FluentIterable.from(objectRules)
        .transform(SourcePaths.TO_BUILD_RULE_SOURCE_PATH)
        .toList();
  }

  private static final Flavor STATIC_FLAVOR = new Flavor("static");
  private static final Flavor SHARED_FLAVOR = new Flavor("shared");

  public static BuildTarget createStaticLibraryBuildTarget(BuildTarget target) {
    return BuildTargets.extendFlavoredBuildTarget(target, STATIC_FLAVOR);
  }

  public static BuildTarget createSharedLibraryBuildTarget(BuildTarget target) {
    return BuildTargets.extendFlavoredBuildTarget(target, SHARED_FLAVOR);
  }

  public static CxxLibrary createCxxLibraryBuildRules(
      final BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxBuckConfig cxxBuckConfig,
      ImmutableList<String> preprocessorFlags,
      final ImmutableList<String> propagatedPpFlags,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableList<String> compilerFlags,
      ImmutableList<CxxSource> sources,
      final boolean linkWhole) {

    CxxPreprocessorInput cxxPreprocessorInput = createHeaderBuildRules(
        params,
        resolver,
        cxxBuckConfig,
        preprocessorFlags,
        headers);

    // Create rules for compiling the non-PIC object files.
    ImmutableList<SourcePath> objects = createPreprocessAndCompileBuildRules(
        params,
        resolver,
        cxxBuckConfig,
        cxxPreprocessorInput,
        compilerFlags,
        /* pic */ false,
        sources);

    // Write a build rule to create the archive for this C/C++ library.
    final BuildTarget staticLibraryTarget = createStaticLibraryBuildTarget(params.getBuildTarget());
    final Path staticLibraryPath =  Archives.getArchiveOutputPath(staticLibraryTarget);
    Archive archive = Archives.createArchiveRule(
        staticLibraryTarget,
        params,
        cxxBuckConfig.getAr().or(Archives.DEFAULT_ARCHIVE_PATH),
        staticLibraryPath,
        objects);
    resolver.addToIndex(archive);

    // Create rules for compiling the PIC object files.
    ImmutableList<SourcePath> picObjects = createPreprocessAndCompileBuildRules(
        params,
        resolver,
        cxxBuckConfig,
        cxxPreprocessorInput,
        compilerFlags,
        /* pic */ true,
        sources);

    // Setup the rules to link the shared library.
    final BuildTarget sharedLibraryTarget = createSharedLibraryBuildTarget(params.getBuildTarget());
    String sharedLibraryName = String.format("lib%s.so", sharedLibraryTarget.getShortNameOnly());
    final String sharedLibrarySoname = String.format(
        "lib%s_%s.so",
        params.getBuildTarget().getBaseName().substring(2).replace('/', '_'),
        params.getBuildTarget().getShortNameOnly());
    final Path sharedLibraryPath = BuildTargets.getBinPath(
        sharedLibraryTarget,
        "%s/" + sharedLibraryName);
    final CxxLink sharedLibraryBuildRule = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        params,
        resolver,
        cxxBuckConfig.getLd().or(CxxLinkables.DEFAULT_LINKER_PATH),
        cxxBuckConfig.getCxxLdFlags(),
        cxxBuckConfig.getLdFlags(),
        sharedLibraryTarget,
        CxxLinkableEnhancer.LinkType.SHARED,
        Optional.of(sharedLibrarySoname),
        sharedLibraryPath,
        picObjects,
        NativeLinkable.Type.SHARED,
        params.getDeps());
    resolver.addToIndex(sharedLibraryBuildRule);

    // Create the CppLibrary rule that dependents can references from the action graph
    // to get information about this rule (e.g. how this rule contributes to the C/C++
    // preprocessor or linker).  Long-term this should probably be collapsed into the
    // TargetGraph when it becomes exposed to build rule creation.
    return new CxxLibrary(params) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput() {
        return new CxxPreprocessorInput(
            ImmutableSet.of(
                CxxDescriptionEnhancer.createHeaderTarget(params.getBuildTarget()),
                CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(params.getBuildTarget())),
            propagatedPpFlags,
            propagatedPpFlags,
            ImmutableList.of(
                CxxDescriptionEnhancer.getHeaderSymlinkTreePath(params.getBuildTarget())),
            ImmutableList.<Path>of());
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(NativeLinkable.Type type) {

        // Build up the arguments used to link this library.  If we're linking the
        // whole archive, wrap the library argument in the necessary "ld" flags.
        ImmutableList.Builder<String> linkerArgsBuilder = ImmutableList.builder();
        if (linkWhole && type == Type.STATIC) {
          linkerArgsBuilder.add("--whole-archive");
        }
        linkerArgsBuilder.add(
            type == Type.STATIC ?
                staticLibraryPath.toString() :
                sharedLibraryPath.toString());
        if (linkWhole && type == Type.STATIC) {
          linkerArgsBuilder.add("--no-whole-archive");
        }
        final ImmutableList<String> linkerArgs = linkerArgsBuilder.build();

        return new NativeLinkableInput(
            ImmutableSet.of(type == Type.STATIC ? staticLibraryTarget : sharedLibraryTarget),
            ImmutableList.<Path>of(),
            linkerArgs);
      }

    };
  }

}
