/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.d;

import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

/**
 * Utility functions for use in D Descriptions.
 */
abstract class DDescriptionUtils {
  /**
   * Creates a BuildTarget, based on an existing build target, but flavored with a CxxPlatform
   * and an additional flavor created by combining a prefix and an output file name.
   *
   * @param existingTarget the existing target
   * @param flavorPrefix prefix to be used for added flavor
   * @param fileName filename to be used for added flavor
   * @param cxxPlatform the C++ platform to compile for
   * @return the new BuildTarget
   */
  public static BuildTarget createBuildTargetForFile(
      BuildTarget existingTarget,
      String flavorPrefix,
      String fileName,
      CxxPlatform cxxPlatform) {
    return BuildTarget.builder(existingTarget)
        .addFlavors(
            cxxPlatform.getFlavor(),
            ImmutableFlavor.of(
                flavorPrefix + Flavor.replaceInvalidCharacters(fileName)))
        .build();
  }

  /**
   * Creates a new BuildTarget, based on an existing target, for a file to be compiled.
   * @param existingTarget the existing target
   * @param src the source file to be compiled
   * @param cxxPlatform the C++ platform to compile the file for
   * @return a BuildTarget to compile a D source file to an object file
   */
  public static BuildTarget createDCompileBuildTarget(
      BuildTarget existingTarget,
      SourcePath src,
      CxxPlatform cxxPlatform) {
    return createBuildTargetForFile(
        existingTarget,
        "compile-",
        DCompileStep.getObjectNameForSourceName(src.toString()),
        cxxPlatform);
  }

  /**
   * Creates a {@link com.facebook.buck.cxx.NativeLinkable} using sources compiled by
   * the D compiler.
   *
   * @param params build parameters for the build target
   * @param sources source files to compile
   * @param compilerFlags flags to pass to the compiler
   * @param buildRuleResolver resolver for build rules
   * @param cxxPlatform the C++ platform to compile for
   * @param dBuckConfig the Buck configuration for D
   * @return the new build rule
   */
  public static CxxLink createNativeLinkable(
      BuildRuleParams params,
      Iterable<SourcePath> sources,
      ImmutableList<String> compilerFlags,
      BuildRuleResolver buildRuleResolver,
      CxxPlatform cxxPlatform,
      DBuckConfig dBuckConfig) throws NoSuchBuildTargetException {

    BuildTarget buildTarget = params.getBuildTarget();
    SourcePathResolver sourcePathResolver = new SourcePathResolver(buildRuleResolver);

    ImmutableList<SourcePath> sourcePaths = sourcePathsForCompiledSources(
        sources,
        compilerFlags,
        params,
        buildRuleResolver,
        sourcePathResolver,
        cxxPlatform,
        dBuckConfig);

    // Return a rule to link the .o for the binary together with its
    // dependencies.
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxPlatform,
        params,
        sourcePathResolver,
        buildTarget,
        Linker.LinkType.EXECUTABLE,
        Optional.<String>absent(),
        BuildTargets.getGenPath(buildTarget, "%s/" + buildTarget.getShortName()),
        ImmutableList.<Arg>builder()
            .addAll(StringArg.from(dBuckConfig.getLinkerFlagsForBinary()))
            .addAll(SourcePathArg.from(sourcePathResolver, sourcePaths))
            .build(),
        Linker.LinkableDepType.STATIC,
        params.getDeps(),
        /* bundleLoader */ Optional.<SourcePath>absent(),
        ImmutableSet.<BuildTarget>of(),
        ImmutableSet.<FrameworkPath>of());
  }

  /**
   * Ensures that a DCompileBuildRule exists for the given target, creating a DCompileBuildRule
   * if neccesary.
   * @param params build parameters for the rule
   * @param buildRuleResolver BuildRuleResolver the rule should be in
   * @param sourcePathResolver used to resolve source paths
   * @param src the source file to be compiled
   * @param compilerFlags flags to pass to the compiler
   * @param compileTarget the target the rule should be for
   * @param dBuckConfig the Buck configuration for D
   * @return the build rule
   */
  public static DCompileBuildRule requireBuildRule(
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      SourcePathResolver sourcePathResolver,
      SourcePath src,
      ImmutableList<String> compilerFlags,
      BuildTarget compileTarget,
      DBuckConfig dBuckConfig) {
    Optional<BuildRule> existingRule = buildRuleResolver.getRuleOptional(compileTarget);
    if (existingRule.isPresent()) {
      return (DCompileBuildRule) existingRule.get();
    } else {
      ImmutableList<String> flags = ImmutableList.<String>builder()
          .addAll(compilerFlags)
          .add("-c")
          .build();
      DCompileBuildRule rule = new DCompileBuildRule(
          params.copyWithBuildTarget(compileTarget),
          sourcePathResolver,
          ImmutableSortedSet.of(src),
          flags,
          dBuckConfig.getDCompiler());
      buildRuleResolver.addToIndex(rule);
      return rule;
    }
  }

  /**
   * Generates BuildTargets and BuildRules to compile D sources to object files, and
   * returns a list of SourcePaths referring to the generated object files.
   * @param sources source files to compile
   * @param compilerFlags flags to pass to the compiler
   * @param params build parameters for the compilation
   * @param buildRuleResolver resolver for build rules
   * @param sourcePathResolver resolver for source paths
   * @param cxxPlatform the C++ platform to compile for
   * @param dBuckConfig the Buck configuration for D
   * @return SourcePaths of the generated object files
   */
  public static ImmutableList<SourcePath> sourcePathsForCompiledSources(
      Iterable<SourcePath> sources,
      ImmutableList<String> compilerFlags,
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      SourcePathResolver sourcePathResolver,
      CxxPlatform cxxPlatform,
      DBuckConfig dBuckConfig) {
    ImmutableList.Builder<SourcePath> sourcePaths = ImmutableList.builder();
    for (SourcePath source : sources) {
      BuildTarget compileTarget = createDCompileBuildTarget(
          params.getBuildTarget(), source, cxxPlatform);
      requireBuildRule(
          params, buildRuleResolver, sourcePathResolver, source,
          compilerFlags, compileTarget, dBuckConfig);
      sourcePaths.add(new BuildTargetSourcePath(compileTarget));
    }
    return sourcePaths.build();
  }
}
