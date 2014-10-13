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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.nio.file.Path;

public class CxxCompilableEnhancer {

  private CxxCompilableEnhancer() {}

  private static final BuildRuleType COMPILE_TYPE = new BuildRuleType("compile");

  /**
   * Prefixes each of the given assembler arguments with "-Xassembler" so that the compiler
   * assembler driver will pass these arguments directly down to the linker rather than
   * interpreting them itself.
   *
   * e.g. ["--fatal-warnings"] -> ["-Xassembler", "--fatal-warnings"]
   *
   * @param args arguments for the assembler.
   * @return arguments to be passed to the compiler assembler driver.
   */
  public static Iterable<String> iXassembler(Iterable<String> args) {
    return MoreIterables.zipAndConcat(
        Iterables.cycle("-Xassembler"),
        args);
  }

  /**
   * Resolve the map of names to SourcePaths to a map of names to CxxSource objects.
   */
  public static ImmutableMap<String, CxxSource> resolveCxxSources(
      ImmutableMap<String, SourcePath> sources) {

    ImmutableMap.Builder<String, CxxSource> cxxSources = ImmutableMap.builder();

    // For each entry in the input C/C++ source, build a CxxSource object to wrap
    // it's name, input path, and output object file path.
    for (ImmutableMap.Entry<String, SourcePath> ent : sources.entrySet()) {
      String extension = Files.getFileExtension(ent.getKey());
      Optional<CxxSource.Type> type = CxxSource.Type.fromExtension(extension);
      if (!type.isPresent()) {
        throw new HumanReadableException(
            "invalid extension \"%s\": %s",
            extension,
            ent.getKey());
      }
      cxxSources.put(
          ent.getKey(),
          new CxxSource(
              type.get(),
              ent.getValue()));
    }

    return cxxSources.build();
  }

  /**
   * @return the object file name for the given source name.
   */
  private static String getOutputName(String name) {
    return Files.getNameWithoutExtension(name) + ".o";
  }

  /**
   * @return a build target for a {@link CxxCompile} rule for the source with the given name.
   */
  public static BuildTarget createCompileBuildTarget(
      BuildTarget target,
      String name,
      boolean pic) {
    return BuildTargets.extendFlavoredBuildTarget(
        target,
        new Flavor(String.format(
            "compile-%s%s",
            pic ? "pic-" : "",
            getOutputName(name).replace('/', '-').replace('.', '-'))));
  }

  /**
   * @return the output path for an object file compiled from the source with the given name.
   */
  public static Path getCompileOutputPath(BuildTarget target, String name) {
    return BuildTargets.getBinPath(target, "%s").resolve(getOutputName(name));
  }

  /**
   * @return a {@link CxxCompile} rule that preprocesses, compiles, and assembles the given
   *    {@link CxxSource}.
   */
  public static CxxCompile createCompileBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform platform,
      CxxPreprocessorInput preprocessorInput,
      ImmutableList<String> compilerFlags,
      boolean pic,
      String name,
      CxxSource source) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    BuildTarget target = createCompileBuildTarget(
        params.getBuildTarget(),
        name,
        pic);

    ImmutableSortedSet.Builder<BuildRule> dependencies = ImmutableSortedSet.naturalOrder();

    // If a build rule generates our input source, add that as a dependency.
    dependencies.addAll(pathResolver.filterBuildRuleInputs(ImmutableList.of(source.getPath())));

    // Add additional dependencies only for preprocessing.
    if (CxxSourceTypes.isPreprocessableType(source.getType())) {

      // Depend on the rule that generates the sources and headers we're compiling.
      dependencies.addAll(
          pathResolver.filterBuildRuleInputs(
              ImmutableList.<SourcePath>builder()
                  .add(source.getPath())
                  .addAll(preprocessorInput.getIncludes().values())
                  .build()));

      // Also add in extra deps from the preprocessor input, such as the symlink tree
      // rules.
      dependencies.addAll(
          BuildRules.toBuildRulesFor(
              params.getBuildTarget(),
              resolver,
              preprocessorInput.getRules(),
              false));
    }

    // Pick the compiler to use.  Basically, if we're dealing with C++ sources, use the C++
    // compiler, and the C compiler for everything.
    SourcePath compiler;
    if (CxxSourceTypes.needsCxxCompiler(source.getType())) {
      compiler = platform.getCxx();
    } else {
      compiler = platform.getCc();
    }

    ImmutableList.Builder<String> args = ImmutableList.builder();

    // We explicitly identify our source rather then let the compiler guess based on the
    // extension.
    args.add("-x", source.getType().getLanguage());

    args.addAll(preprocessorInput.getPreprocessorFlags().get(source.getType()));

    // If we're dealing with a C++ source that can be preprocessed, add in the various C++
    // preprocessor flags.
    if (source.getType() == CxxSource.Type.CXX) {
      args.addAll(platform.getCxxppflags());
    }

    // If we're dealing with a C source that can be preprocessed, add in the various C
    // preprocessor flags.
    if (source.getType() == CxxSource.Type.C) {
      args.addAll(platform.getCppflags());
    }

    // If we're dealing with assembly source that can be preprocessed, add in the platform
    // specific preprocessor flags.
    if (source.getType() == CxxSource.Type.ASSEMBLER_WITH_CPP) {
      args.addAll(platform.getAsppflags());
    }

    // If we're dealing with a C source that can be compiled, add the platform C compiler flags.
    if (source.getType() == CxxSource.Type.C ||
        source.getType() == CxxSource.Type.C_CPP_OUTPUT) {
      args.addAll(platform.getCflags());
    }

    // If we're dealing with a C++ source that can be compiled, add the platform C++ compiler
    // flags.
    if (source.getType() == CxxSource.Type.CXX ||
        source.getType() == CxxSource.Type.CXX_CPP_OUTPUT) {
      args.addAll(platform.getCxxflags());
    }

    // Add in explicit additional compiler flags, if we're compiling.
    if (source.getType() == CxxSource.Type.C ||
        source.getType() == CxxSource.Type.C_CPP_OUTPUT ||
        source.getType() == CxxSource.Type.CXX ||
        source.getType() == CxxSource.Type.CXX_CPP_OUTPUT) {
      args.addAll(compilerFlags);
    }

    // All source types require assembling, so add in platform-specific assembler flags.
    args.addAll(iXassembler(platform.getAsflags()));

    // If we're using pic, add in the appropriate flag.
    if (pic) {
      args.add("-fPIC");
    }

    // Build the CxxCompile rule and add it to our sorted set of build rules.
    return new CxxCompile(
        params.copyWithChanges(
            COMPILE_TYPE,
            target,
            dependencies.build(),
            ImmutableSortedSet.<BuildRule>of()),
        pathResolver,
        compiler,
        Optional.<CxxCompile.Plugin>absent(),
        args.build(),
        getCompileOutputPath(target, name),
        source.getPath(),
        preprocessorInput.getIncludeRoots(),
        preprocessorInput.getSystemIncludeRoots(),
        preprocessorInput.getIncludes());
  }

  /**
   * @return a set of {@link CxxCompile} rules preprocessing, compiling, and assembling the
   *    given input {@link CxxSource} sources.
   */
  public static ImmutableSortedSet<BuildRule> createCompileBuildRules(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform platform,
      CxxPreprocessorInput preprocessorInput,
      ImmutableList<String> compilerFlags,
      boolean pic,
      ImmutableMap<String, CxxSource> sources) {

    ImmutableSortedSet.Builder<BuildRule> rules = ImmutableSortedSet.naturalOrder();

    // Iterate over the input C/C++ sources that we need to preprocess, assemble, and compile,
    // and generate compile rules for them.
    for (ImmutableMap.Entry<String, CxxSource> entry : sources.entrySet()) {
      rules.add(createCompileBuildRule(
          params,
          resolver,
          platform,
          preprocessorInput,
          compilerFlags,
          pic,
          entry.getKey(),
          entry.getValue()));
    }

    return rules.build();
  }

}
