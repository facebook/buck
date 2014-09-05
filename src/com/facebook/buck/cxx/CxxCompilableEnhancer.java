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
import com.facebook.buck.rules.SourcePaths;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;

import java.nio.file.Path;

public class CxxCompilableEnhancer {

  private CxxCompilableEnhancer() {}

  private static final BuildRuleType COMPILE_TYPE = new BuildRuleType("compile");

  /**
   * Resolve the map of names to SourcePaths to a list of CxxSource objects.
   */
  public static ImmutableList<CxxSource> resolveCxxSources(
      ImmutableMap<String, SourcePath> sources) {

    ImmutableList.Builder<CxxSource> cxxSources = ImmutableList.builder();

    // For each entry in the input C/C++ source, build a CxxSource object to wrap
    // it's name, input path, and output object file path.
    for (ImmutableMap.Entry<String, SourcePath> ent : sources.entrySet()) {
      cxxSources.add(
          new CxxSource(
              ent.getKey(),
              ent.getValue()));
    }

    return cxxSources.build();
  }

  /**
   * @return the object file name for the given source name.
   */
  private static String getOutputName(String name) {
    String base = Files.getNameWithoutExtension(name);
    String ext = Files.getFileExtension(name);
    Preconditions.checkArgument(CxxCompilables.SOURCE_EXTENSIONS.contains(ext));
    return base + ".o";
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
      Path compiler,
      CxxPreprocessorInput preprocessorInput,
      ImmutableList<String> compilerFlags,
      boolean pic,
      CxxSource source) {

    BuildTarget target = createCompileBuildTarget(
        params.getBuildTarget(),
        source.getName(),
        pic);

    boolean cxx = !Files.getFileExtension(source.getName()).equals("c");

    // The customized build rule params for each compilation.
    BuildRuleParams compileParams = params.copyWithChanges(
        COMPILE_TYPE,
        target,
        // Compile rules don't inherit any of the declared deps.
        /* declaredDeps */ ImmutableSortedSet.<BuildRule>naturalOrder()
            // Depend on the rule that generates the sources and headers we're compiling.
            .addAll(
                SourcePaths.filterBuildRuleInputs(
                    ImmutableList.<SourcePath>builder()
                        .add(source.getSource())
                        .addAll(preprocessorInput.getIncludes().values())
                        .build()))
                // Also add in extra deps from the preprocessor input, such as the symlink tree
                // rules.
            .addAll(
                BuildRules.toBuildRulesFor(
                    params.getBuildTarget(),
                    resolver,
                    preprocessorInput.getRules(),
                    false))
            .build(),
        /* extraDeps */ ImmutableSortedSet.<BuildRule>of());

    // Build the CxxCompile rule and add it to our sorted set of build rules.
    return new CxxCompile(
        compileParams,
        compiler,
        ImmutableList.<String>builder()
            .add("-x", cxx ? "c++" : "c")
            .addAll((pic ? Optional.of("-fPIC") : Optional.<String>absent()).asSet())
            .addAll(cxx ? preprocessorInput.getCxxppflags() : preprocessorInput.getCppflags())
            .addAll(compilerFlags)
            .build(),
        getCompileOutputPath(target, source.getName()),
        source.getSource(),
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
      Path compiler,
      CxxPreprocessorInput preprocessorInput,
      ImmutableList<String> compilerFlags,
      boolean pic,
      Iterable<CxxSource> sources) {

    ImmutableSortedSet.Builder<BuildRule> rules = ImmutableSortedSet.naturalOrder();

    // Iterate over the input C/C++ sources that we need to preprocess, assemble, and compile,
    // and generate compile rules for them.
    for (CxxSource source : sources) {
      rules.add(createCompileBuildRule(
          params,
          resolver,
          compiler,
          preprocessorInput,
          compilerFlags,
          pic,
          source));
    }

    return rules.build();
  }

}
