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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Map;

public class CxxSourceRuleFactory {

  private static final BuildRuleType PREPROCESS_TYPE = BuildRuleType.of("preprocess");
  private static final BuildRuleType COMPILE_TYPE = BuildRuleType.of("compile");

  private CxxSourceRuleFactory() {}


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
   * @return the preprocessed file name for the given source name.
   */
  private static String getPreprocessOutputName(CxxSource.Type type, String name) {

    String extension;
    switch (type) {
      case ASSEMBLER_WITH_CPP:
        extension = "s";
        break;
      case C:
        extension = "i";
        break;
      case CXX:
        extension = "ii";
        break;
      case OBJC:
        extension = "mi";
        break;
      case OBJCXX:
        extension = "mii";
        break;
      // $CASES-OMITTED$
      default:
        throw new IllegalStateException(String.format("unexpected type: %s", type));
    }

    return name + "." + extension;
  }

  /**
   * @return a {@link BuildTarget} used for the rule that preprocesses the source by the given
   *     name and type.
   */
  public static BuildTarget createPreprocessBuildTarget(
      BuildTarget target,
      Flavor platform,
      CxxSource.Type type,
      boolean pic,
      String name) {
    String outputName = Flavor.replaceInvalidCharacters(getPreprocessOutputName(type, name));
    return BuildTarget
        .builder(target)
        .addFlavors(platform)
        .addFlavors(
            ImmutableFlavor.of(
                String.format(
                    "preprocess-%s%s",
                    pic ? "pic-" : "",
                    outputName)))
        .build();
  }

  /**
   * @return the output path for an object file compiled from the source with the given name.
   */
  public static Path getPreprocessOutputPath(BuildTarget target, CxxSource.Type type, String name) {
    return BuildTargets.getBinPath(target, "%s").resolve(getPreprocessOutputName(type, name));
  }

  /**
   * Generate a build rule that preprocesses the given source.
   *
   * @return a pair of the output name and source.
   */
  public static Map.Entry<String, CxxSource> createPreprocessBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      CxxPreprocessorInput preprocessorInput,
      boolean pic,
      String name,
      CxxSource source) {

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    ImmutableSortedSet.Builder<BuildRule> dependencies = ImmutableSortedSet.naturalOrder();

    // If a build rule generates our input source, add that as a dependency.
    dependencies.addAll(pathResolver.filterBuildRuleInputs(ImmutableList.of(source.getPath())));

    // Depend on the rule that generates the sources and headers we're compiling.
    dependencies.addAll(
        pathResolver.filterBuildRuleInputs(
            ImmutableList.<SourcePath>builder()
                .add(source.getPath())
                .addAll(preprocessorInput.getIncludes().getPrefixHeaders())
                .addAll(preprocessorInput.getIncludes().getNameToPathMap().values())
                .build()));

    // Also add in extra deps from the preprocessor input, such as the symlink tree
    // rules.
    dependencies.addAll(
        BuildRules.toBuildRulesFor(
            params.getBuildTarget(),
            resolver,
            preprocessorInput.getRules(),
            false));

    Tool preprocessor;
    ImmutableList.Builder<String> args = ImmutableList.builder();
    CxxSource.Type outputType;

    // We explicitly identify our source rather then let the compiler guess based on the
    // extension.
    args.add("-x", source.getType().getLanguage());

    // Pick the compiler to use.  Basically, if we're dealing with C++ sources, use the C++
    // compiler, and the C compiler for everything.
    switch (source.getType()) {
      case ASSEMBLER_WITH_CPP:
        preprocessor = cxxPlatform.getAspp();
        args.addAll(cxxPlatform.getAsppflags());
        args.addAll(
            preprocessorInput.getPreprocessorFlags().get(CxxSource.Type.ASSEMBLER_WITH_CPP));
        outputType = CxxSource.Type.ASSEMBLER;
        break;
      case C:
        preprocessor = cxxPlatform.getCpp();
        args.addAll(cxxPlatform.getCppflags());
        args.addAll(preprocessorInput.getPreprocessorFlags().get(CxxSource.Type.C));
        outputType = CxxSource.Type.C_CPP_OUTPUT;
        break;
      case CXX:
        preprocessor = cxxPlatform.getCxxpp();
        args.addAll(cxxPlatform.getCxxppflags());
        args.addAll(preprocessorInput.getPreprocessorFlags().get(CxxSource.Type.CXX));
        outputType = CxxSource.Type.CXX_CPP_OUTPUT;
        break;
      case OBJC:
        preprocessor = cxxPlatform.getCpp();
        args.addAll(cxxPlatform.getCppflags());
        args.addAll(preprocessorInput.getPreprocessorFlags().get(CxxSource.Type.OBJC));
        outputType = CxxSource.Type.OBJC_CPP_OUTPUT;
        break;
      case OBJCXX:
        preprocessor = cxxPlatform.getCxxpp();
        args.addAll(cxxPlatform.getCxxppflags());
        args.addAll(preprocessorInput.getPreprocessorFlags().get(CxxSource.Type.OBJCXX));
        outputType = CxxSource.Type.OBJCXX_CPP_OUTPUT;
        break;
      // $CASES-OMITTED$
      default:
        throw new IllegalStateException(String.format("unexpected type: %s", source.getType()));
    }

    // Add dependencies on any build rules used to create the preprocessor.
    dependencies.addAll(preprocessor.getBuildRules(pathResolver));

    // If we're using pic, add in the appropriate flag.
    if (pic) {
      args.add("-fPIC");
    }

    // Add custom per-file flags.
    args.addAll(source.getFlags());

    // Build the CxxCompile rule and add it to our sorted set of build rules.
    BuildTarget target =
        createPreprocessBuildTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor(),
            source.getType(),
            pic,
            name);
    CxxPreprocess cxxPreprocess = new CxxPreprocess(
        params.copyWithChanges(
            PREPROCESS_TYPE,
            target,
            Suppliers.ofInstance(dependencies.build()),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        pathResolver,
        preprocessor,
        args.build(),
        getPreprocessOutputPath(target, source.getType(), name),
        source.getPath(),
        ImmutableList.copyOf(preprocessorInput.getIncludeRoots()),
        ImmutableList.copyOf(preprocessorInput.getSystemIncludeRoots()),
        ImmutableList.copyOf(preprocessorInput.getFrameworkRoots()),
        preprocessorInput.getIncludes(),
        cxxPlatform.getDebugPathSanitizer());
    resolver.addToIndex(cxxPreprocess);

    // Return the output name and source pair.
    return new AbstractMap.SimpleEntry<String, CxxSource>(
        name,
        ImmutableCxxSource.of(
            outputType,
            new BuildTargetSourcePath(
                cxxPreprocess.getProjectFilesystem(),
                cxxPreprocess.getBuildTarget()),
            source.getFlags()));
  }

  /**
   * Generate build rules which preprocess the given input sources.
   */
  public static ImmutableMap<String, CxxSource> createPreprocessBuildRules(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform config,
      CxxPreprocessorInput cxxPreprocessorInput,
      boolean pic,
      ImmutableMap<String, CxxSource> sources) {

    ImmutableMap.Builder<String, CxxSource> preprocessedSources = ImmutableMap.builder();

    for (Map.Entry<String, CxxSource> entry : sources.entrySet()) {
      if (CxxSourceTypes.isPreprocessableType(entry.getValue().getType())) {
        entry = createPreprocessBuildRule(
            params,
            resolver,
            config,
            cxxPreprocessorInput,
            pic,
            entry.getKey(),
            entry.getValue());
      }
      preprocessedSources.put(entry);
    }

    return preprocessedSources.build();
  }

  /**
   * @return the object file name for the given source name.
   */
  private static String getCompileOutputName(String name) {
    return name + ".o";
  }

  /**
   * @return a build target for a {@link CxxCompile} rule for the source with the given name.
   */
  public static BuildTarget createCompileBuildTarget(
      BuildTarget target,
      Flavor platform,
      String name,
      boolean pic) {
    String outputName = Flavor.replaceInvalidCharacters(getCompileOutputName(name));
    return BuildTarget
        .builder(target)
        .addFlavors(platform)
        .addFlavors(
            ImmutableFlavor.of(
                String.format(
                    "compile-%s%s",
                    pic ? "pic-" : "",
                    outputName)))
        .build();
  }

  /**
   * @return the output path for an object file compiled from the source with the given name.
   */
  public static Path getCompileOutputPath(BuildTarget target, String name) {
    return BuildTargets.getBinPath(target, "%s").resolve(getCompileOutputName(name));
  }

  /**
   * @return a {@link CxxCompile} rule that preprocesses, compiles, and assembles the given
   *    {@link CxxSource}.
   */
  public static CxxCompile createCompileBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform platform,
      ImmutableList<String> compilerFlags,
      boolean pic,
      String name,
      CxxSource source) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    Preconditions.checkArgument(CxxSourceTypes.isCompilableType(source.getType()));

    BuildTarget target = createCompileBuildTarget(
        params.getBuildTarget(),
        platform.getFlavor(),
        name,
        pic);

    ImmutableSortedSet.Builder<BuildRule> dependencies = ImmutableSortedSet.naturalOrder();

    // If a build rule generates our input source, add that as a dependency.
    dependencies.addAll(pathResolver.filterBuildRuleInputs(ImmutableList.of(source.getPath())));

    // Pick the compiler to use.  Basically, if we're dealing with C++ sources, use the C++
    // compiler, and the C compiler for everything.
    Tool compiler;
    if (CxxSourceTypes.needsCxxCompiler(source.getType())) {
      compiler = platform.getCxx();
    } else {
      compiler = platform.getCc();
    }

    ImmutableList.Builder<String> args = ImmutableList.builder();

    // TODO(#5393669): We need to handle compiler drivers that don't support certain language
    // options (e.g. the android NDK compilers don't support "c-cpp-output", although they can
    // auto-detect via the extension).  For the time being, we just fall back to the default
    // of letting the compiler driver auto-detecting the language type via the extensions which
    // should work, since we require proper extensions in the descriptions.
    //args.add("-x", source.getType().getLanguage());

    // If we're dealing with a C source that can be compiled, add the platform C compiler flags.
    if (source.getType() == CxxSource.Type.C_CPP_OUTPUT ||
        source.getType() == CxxSource.Type.OBJC_CPP_OUTPUT) {
      args.addAll(platform.getCflags());
    }

    // If we're dealing with a C++ source that can be compiled, add the platform C++ compiler
    // flags.
    if (source.getType() == CxxSource.Type.CXX_CPP_OUTPUT ||
        source.getType() == CxxSource.Type.OBJCXX_CPP_OUTPUT) {
      args.addAll(platform.getCxxflags());
    }

    // Add in explicit additional compiler flags, if we're compiling.
    if (source.getType() == CxxSource.Type.C_CPP_OUTPUT ||
        source.getType() == CxxSource.Type.OBJC_CPP_OUTPUT ||
        source.getType() == CxxSource.Type.CXX_CPP_OUTPUT ||
        source.getType() == CxxSource.Type.OBJCXX_CPP_OUTPUT) {
      args.addAll(compilerFlags);
    }

    // All source types require assembling, so add in platform-specific assembler flags.
    args.addAll(iXassembler(platform.getAsflags()));

    // If we're using pic, add in the appropriate flag.
    if (pic) {
      args.add("-fPIC");
    }

    // Add custom per-file flags.
    args.addAll(source.getFlags());

    // Build the CxxCompile rule and add it to our sorted set of build rules.
    return new CxxCompile(
        params.copyWithChanges(
            COMPILE_TYPE,
            target,
            Suppliers.ofInstance(dependencies.build()),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        pathResolver,
        compiler,
        Optional.<CxxCompile.Plugin>absent(),
        args.build(),
        getCompileOutputPath(target, name),
        source.getPath(),
        platform.getDebugPathSanitizer());
  }

  /**
   * @return a set of {@link CxxCompile} rules which compile and assemble the given input
   *     {@link CxxSource} sources.
   */
  public static ImmutableSortedSet<BuildRule> createCompileBuildRules(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform platform,
      ImmutableList<String> compilerFlags,
      boolean pic,
      ImmutableMap<String, CxxSource> sources) {

    ImmutableSortedSet.Builder<BuildRule> rules = ImmutableSortedSet.naturalOrder();

    // Iterate over the input C/C++ sources that we need to compile and assemble and generate
    // build rules for them.
    for (ImmutableMap.Entry<String, CxxSource> entry : sources.entrySet()) {
      rules.add(createCompileBuildRule(
              params,
              resolver,
              platform,
              compilerFlags,
              pic,
              entry.getKey(),
              entry.getValue()));
    }

    return rules.build();
  }

}
