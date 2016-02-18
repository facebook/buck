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

package com.facebook.buck.ocaml;

import static com.google.common.base.Preconditions.checkNotNull;

import com.facebook.buck.cxx.Compiler;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.io.Files;

import java.nio.file.Path;
import java.util.Map;

/**
 * A generator of fine-grained OCaml build rules
 */
public class OCamlBuildRulesGenerator {

  private static final Flavor DEBUG_FLAVOR = ImmutableFlavor.of("debug");

  private final BuildRuleParams params;
  private final BuildRuleResolver resolver;
  private final SourcePathResolver pathResolver;
  private final OCamlBuildContext ocamlContext;
  private final ImmutableMap<Path, ImmutableList<Path>> mlInput;
  private final ImmutableList<SourcePath> cInput;

  private final Compiler cCompiler;
  private final Compiler cxxCompiler;
  private final boolean bytecodeOnly;

  public OCamlBuildRulesGenerator(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      BuildRuleResolver resolver,
      OCamlBuildContext ocamlContext,
      ImmutableMap<Path, ImmutableList<Path>> mlInput,
      ImmutableList<SourcePath> cInput,
      Compiler cCompiler,
      Compiler cxxCompiler,
      boolean bytecodeOnly) {
    this.params = params;
    this.pathResolver = pathResolver;
    this.resolver = resolver;
    this.ocamlContext = ocamlContext;
    this.mlInput = mlInput;
    this.cInput = cInput;
    this.cCompiler = cCompiler;
    this.cxxCompiler = cxxCompiler;
    this.bytecodeOnly = bytecodeOnly;
  }

  /**
   * Generates build rules for both the native and bytecode outputs
   */
  OCamlGeneratedBuildRules generate() {

    ImmutableList.Builder<BuildRule> rules = ImmutableList.builder();
    ImmutableList.Builder<BuildRule> nativeCompileDeps = ImmutableList.builder();
    ImmutableList.Builder<BuildRule> bytecodeCompileDeps = ImmutableList.builder();

    ImmutableList<SourcePath> objFiles = generateCCompilation(cInput);

    if (!this.bytecodeOnly) {
      ImmutableList<SourcePath> cmxFiles = generateMLNativeCompilation(mlInput);
      nativeCompileDeps.addAll(pathResolver.filterBuildRuleInputs(cmxFiles));
      BuildRule nativeLink = generateNativeLinking(
          ImmutableList.<SourcePath>builder()
              .addAll(Iterables.concat(cmxFiles, objFiles))
              .build()
      );
      rules.add(nativeLink);
    }

    ImmutableList<SourcePath> cmoFiles = generateMLBytecodeCompilation(mlInput);
    bytecodeCompileDeps.addAll(pathResolver.filterBuildRuleInputs(cmoFiles));
    BuildRule bytecodeLink = generateBytecodeLinking(
        ImmutableList.<SourcePath>builder()
            .addAll(Iterables.concat(cmoFiles, objFiles))
            .build()
    );
    rules.add(bytecodeLink);

    if (!ocamlContext.isLibrary()) {
      rules.add(generateDebugLauncherRule());
    }

    return OCamlGeneratedBuildRules.builder()
        .setRules(rules.build())
        .setNativeCompileDeps(ImmutableSortedSet.copyOf(nativeCompileDeps.build()))
        .setBytecodeCompileDeps(ImmutableSortedSet.copyOf(bytecodeCompileDeps.build()))
        .setObjectFiles(objFiles)
        .setBytecodeLink(bytecodeLink)
        .build();
  }

  private static String getCOutputName(String name) {
    String base = Files.getNameWithoutExtension(name);
    String ext = Files.getFileExtension(name);
    Preconditions.checkArgument(OCamlCompilables.SOURCE_EXTENSIONS.contains(ext));
    return base + ".o";
  }

  public static BuildTarget createCCompileBuildTarget(
      BuildTarget target,
      String name) {
    return BuildTarget
        .builder(target)
        .addFlavors(
            ImmutableFlavor.of(
                String.format(
                    "compile-%s",
                    getCOutputName(name)
                        .replace('/', '-')
                        .replace('.', '-')
                        .replace('+', '-')
                        .replace(' ', '-'))))
        .build();
  }

  private ImmutableList<SourcePath> generateCCompilation(ImmutableList<SourcePath> cInput) {

    ImmutableList.Builder<SourcePath> objects = ImmutableList.builder();

    ImmutableList.Builder<String> cCompileFlags = ImmutableList.builder();
    cCompileFlags.addAll(ocamlContext.getCCompileFlags());
    cCompileFlags.addAll(ocamlContext.getCommonCFlags());

    CxxPreprocessorInput cxxPreprocessorInput = ocamlContext.getCxxPreprocessorInput();

    for (SourcePath cSrc : cInput) {
      String name = pathResolver.getAbsolutePath(cSrc).toFile().getName();
      BuildTarget target = createCCompileBuildTarget(
          params.getBuildTarget(),
          name);

      BuildRuleParams cCompileParams = params.copyWithChanges(
          target,
        /* declaredDeps */ Suppliers.ofInstance(
              ImmutableSortedSet.<BuildRule>naturalOrder()
                  // Depend on the rule that generates the sources and headers we're compiling.
                  .addAll(
                      pathResolver.filterBuildRuleInputs(
                          ImmutableList.<SourcePath>builder()
                              .add(cSrc)
                              .addAll(
                                  cxxPreprocessorInput.getIncludes().getNameToPathMap().values())
                              .build()))
                      // Also add in extra deps from the preprocessor input, such as the symlink
                      // tree rules.
                  .addAll(
                      BuildRules.toBuildRulesFor(
                          params.getBuildTarget(),
                          resolver,
                          cxxPreprocessorInput.getRules()))
                  .addAll(params.getDeclaredDeps().get())
                  .build()),
        /* extraDeps */ params.getExtraDeps());

      Path outputPath = ocamlContext.getCOutput(pathResolver.getRelativePath(cSrc));
      OCamlCCompile compileRule = new OCamlCCompile(
          cCompileParams,
          pathResolver,
          new OCamlCCompileStep.Args(
              cCompiler.getEnvironment(pathResolver),
              cCompiler.getCommandPrefix(pathResolver),
              ocamlContext.getOcamlCompiler().get(),
              outputPath,
              cSrc,
              cCompileFlags.build(),
              ImmutableMap.copyOf(cxxPreprocessorInput.getIncludes().getNameToPathMap())));
      resolver.addToIndex(compileRule);
      objects.add(
          new BuildTargetSourcePath(compileRule.getBuildTarget()));
    }
    return objects.build();
  }

  public static BuildTarget addDebugFlavor(BuildTarget target) {
    return BuildTarget.builder(target).addFlavors(DEBUG_FLAVOR).build();
  }

  private BuildRule generateDebugLauncherRule() {
    BuildRuleParams debugParams = params.copyWithChanges(
        addDebugFlavor(params.getBuildTarget()),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));

    OCamlDebugLauncher debugLauncher = new OCamlDebugLauncher(
        debugParams,
        pathResolver,
        new OCamlDebugLauncherStep.Args(
            ocamlContext.getOcamlDebug().get(),
            ocamlContext.getBytecodeOutput(),
            ocamlContext.getOCamlInput(),
            ocamlContext.getBytecodeIncludeFlags()
        )
    );
    resolver.addToIndex(debugLauncher);
    return debugLauncher;
  }

  /**
   * Links the .cmx files generated by the native compilation
   */
  private BuildRule generateNativeLinking(ImmutableList<SourcePath> allInputs) {
    BuildRuleParams linkParams = params.copyWithChanges(
        params.getBuildTarget(),
        Suppliers.ofInstance(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(pathResolver.filterBuildRuleInputs(allInputs))
                .addAll(
                    FluentIterable.from(ocamlContext.getNativeLinkableInput().getArgs())
                        .transformAndConcat(Arg.getDepsFunction(pathResolver)))
                .addAll(
                    FluentIterable.from(ocamlContext.getCLinkableInput().getArgs())
                        .transformAndConcat(Arg.getDepsFunction(pathResolver)))
                .build()),
        Suppliers.ofInstance(
            ImmutableSortedSet.<BuildRule>of()));

    ImmutableList.Builder<String> flags = ImmutableList.builder();
    flags.addAll(ocamlContext.getFlags());
    flags.addAll(ocamlContext.getCommonCLinkerFlags());

    OCamlLink link = new OCamlLink(
        linkParams,
        pathResolver,
        allInputs,
        cxxCompiler.getEnvironment(pathResolver),
        cxxCompiler.getCommandPrefix(pathResolver),
        ocamlContext.getOcamlCompiler().get(),
        flags.build(),
        ocamlContext.getNativeOutput(),
        ocamlContext.getNativeLinkableInput().getArgs(),
        ocamlContext.getCLinkableInput().getArgs(),
        ocamlContext.isLibrary(),
        /* isBytecode */ false);
    resolver.addToIndex(link);
    return link;
  }

  private static final Flavor BYTECODE_FLAVOR = ImmutableFlavor.of("bytecode");

  public static BuildTarget addBytecodeFlavor(BuildTarget target) {
    return BuildTarget.builder(target).addFlavors(BYTECODE_FLAVOR).build();
  }

  /**
   * Links the .cmo files generated by the bytecode compilation
   */
  private BuildRule generateBytecodeLinking(ImmutableList<SourcePath> allInputs) {
    BuildRuleParams linkParams = params.copyWithChanges(
        addBytecodeFlavor(params.getBuildTarget()),
        Suppliers.ofInstance(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(pathResolver.filterBuildRuleInputs(allInputs))
                .addAll(ocamlContext.getBytecodeLinkDeps())
                .addAll(
                    FluentIterable.from(ocamlContext.getBytecodeLinkableInput().getArgs())
                        .append(ocamlContext.getCLinkableInput().getArgs())
                        .transformAndConcat(Arg.getDepsFunction(pathResolver))
                        .filter(Predicates.not(Predicates.instanceOf(OCamlBuild.class))))
                .build()),
    Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));

    ImmutableList.Builder<String> flags = ImmutableList.builder();
    flags.addAll(ocamlContext.getFlags());
    flags.addAll(ocamlContext.getCommonCLinkerFlags());

    OCamlLink link = new OCamlLink(
        linkParams,
        pathResolver,
        allInputs,
        cxxCompiler.getEnvironment(pathResolver),
        cxxCompiler.getCommandPrefix(pathResolver),
        ocamlContext.getOcamlBytecodeCompiler().get(),
        flags.build(),
        ocamlContext.getBytecodeOutput(),
        ocamlContext.getBytecodeLinkableInput().getArgs(),
        ocamlContext.getCLinkableInput().getArgs(),
        ocamlContext.isLibrary(),
        /* isBytecode */ true);
    resolver.addToIndex(link);
    return link;
  }

  private ImmutableList<String> getCompileFlags(boolean isBytecode, boolean excludeDeps) {
    String output = isBytecode ? ocamlContext.getCompileBytecodeOutputDir().toString() :
        ocamlContext.getCompileNativeOutputDir().toString();
    ImmutableList.Builder<String> flagBuilder = ImmutableList.builder();
    flagBuilder.addAll(ocamlContext.getIncludeFlags(isBytecode,  excludeDeps));
    flagBuilder.addAll(ocamlContext.getFlags());
    flagBuilder.add(
        OCamlCompilables.OCAML_INCLUDE_FLAG,
        output
    );
    return flagBuilder.build();
  }

  /**
   * The native-code executable
   */
  private static String getMLNativeOutputName(String name) {
    String base = Files.getNameWithoutExtension(name);
    String ext = Files.getFileExtension(name);
    Preconditions.checkArgument(OCamlCompilables.SOURCE_EXTENSIONS.contains(ext),
        "Unexpected extension: " + ext);
    String dotExt = "." + ext;
    if (dotExt.equals(OCamlCompilables.OCAML_ML)) {
      return base + OCamlCompilables.OCAML_CMX;
    } else if (dotExt.equals(OCamlCompilables.OCAML_MLI)) {
      return base + OCamlCompilables.OCAML_CMI;
    } else {
      Preconditions.checkState(false, "Unexpected extension: " + ext);
      return base;
    }
  }

  /**
   * The bytecode output (which is also executable)
   */
  private static String getMLBytecodeOutputName(String name) {
    String base = Files.getNameWithoutExtension(name);
    String ext = Files.getFileExtension(name);
    Preconditions.checkArgument(OCamlCompilables.SOURCE_EXTENSIONS.contains(ext));
    String dotExt = "." + ext;
    if (dotExt.equals(OCamlCompilables.OCAML_ML)) {
      return base + OCamlCompilables.OCAML_CMO;
    } else if (dotExt.equals(OCamlCompilables.OCAML_MLI)) {
      return base + OCamlCompilables.OCAML_CMI;
    } else {
      Preconditions.checkState(false, "Unexpected extension: " + ext);
      return base;
    }
  }

  public static BuildTarget createMLNativeCompileBuildTarget(
      BuildTarget target,
      String name) {
    return BuildTarget
        .builder(target)
        .addFlavors(
            ImmutableFlavor.of(
                String.format(
                    "ml-compile-%s",
                    getMLNativeOutputName(name)
                        .replace('/', '-')
                        .replace('.', '-')
                        .replace('+', '-')
                        .replace(' ', '-'))))
        .build();
  }

  public static BuildTarget createMLBytecodeCompileBuildTarget(
      BuildTarget target,
      String name) {
    return BuildTarget
        .builder(target)
        .addFlavors(
            ImmutableFlavor.of(
                String.format(
                    "ml-bytecode-compile-%s",
                    getMLBytecodeOutputName(name)
                        .replace('/', '-')
                        .replace('.', '-')
                        .replace('+', '-')
                        .replace(' ', '-'))))
        .build();
  }

  ImmutableList<SourcePath> generateMLNativeCompilation(
      ImmutableMap<Path, ImmutableList<Path>> mlSources) {
    ImmutableList.Builder<SourcePath> cmxFiles = ImmutableList.builder();

    final Map<Path, ImmutableSortedSet<BuildRule>> sourceToRule = Maps.newHashMap();

    for (ImmutableMap.Entry<Path, ImmutableList<Path>>
        mlSource : mlSources.entrySet()) {
      generateSingleMLNativeCompilation(
          sourceToRule,
          cmxFiles,
          mlSource.getKey(),
          mlSources,
          ImmutableList.<Path>of());
    }
    return cmxFiles.build();
  }

  /**
   * Compiles a single .ml file to a .cmx
   */
  private void generateSingleMLNativeCompilation(
      Map<Path, ImmutableSortedSet<BuildRule>> sourceToRule,
      ImmutableList.Builder<SourcePath> cmxFiles,
      Path mlSource,
      ImmutableMap<Path, ImmutableList<Path>> sources,
      ImmutableList<Path> cycleDetector) {

    ImmutableList<Path> newCycleDetector = ImmutableList.<Path>builder()
        .addAll(cycleDetector)
        .add(mlSource)
        .build();

    if (cycleDetector.contains(mlSource)) {
      throw new HumanReadableException("Dependency cycle detected: %s",
         Joiner.on(" -> ").join(newCycleDetector));
    }

    if (sourceToRule.containsKey(mlSource)) {
      return;
    }

    ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
    if (sources.containsKey(mlSource)) {
      for (Path dep : checkNotNull(sources.get(mlSource))) {
        generateSingleMLNativeCompilation(sourceToRule, cmxFiles, dep, sources, newCycleDetector);
        depsBuilder.addAll(checkNotNull(sourceToRule.get(dep)));
      }
    }
    ImmutableSortedSet<BuildRule> deps = depsBuilder.build();

    String name = mlSource.toFile().getName();

    BuildTarget buildTarget = createMLNativeCompileBuildTarget(
        params.getBuildTarget(),
        name);

    BuildRuleParams compileParams = params.copyWithChanges(
        buildTarget,
        Suppliers.ofInstance(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(params.getDeclaredDeps().get())
                .addAll(deps)
                .addAll(ocamlContext.getNativeCompileDeps())
                .build()),
        params.getExtraDeps());

    String outputFileName = getMLNativeOutputName(name);
    Path outputPath = ocamlContext.getCompileNativeOutputDir()
        .resolve(outputFileName);
    final ImmutableList<String> compileFlags = getCompileFlags(
        /* isBytecode */ false,
        /* excludeDeps */ false);
    OCamlMLCompile compile = new OCamlMLCompile(
        compileParams,
        pathResolver,
        new OCamlMLCompileStep.Args(
            params.getProjectFilesystem().getAbsolutifier(),
            cCompiler.getEnvironment(pathResolver),
            cCompiler.getCommandPrefix(pathResolver),
            ocamlContext.getOcamlCompiler().get(),
            outputPath,
            mlSource,
            compileFlags));
    resolver.addToIndex(compile);
    sourceToRule.put(
        mlSource,
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .add(compile)
            .addAll(deps)
            .build());
    if (!outputFileName.endsWith(OCamlCompilables.OCAML_CMI)) {
      cmxFiles.add(
          new BuildTargetSourcePath(compile.getBuildTarget()));
    }
  }

  private ImmutableList<SourcePath> generateMLBytecodeCompilation(
      ImmutableMap<Path, ImmutableList<Path>> mlSources) {
    ImmutableList.Builder<SourcePath> cmoFiles = ImmutableList.builder();

    final Map<Path, ImmutableSortedSet<BuildRule>> sourceToRule = Maps.newHashMap();

    for (ImmutableMap.Entry<Path, ImmutableList<Path>>
        mlSource : mlSources.entrySet()) {
      generateSingleMLBytecodeCompilation(
          sourceToRule,
          cmoFiles,
          mlSource.getKey(),
          mlSources,
          ImmutableList.<Path>of());
    }
    return cmoFiles.build();
  }

  /**
   * Compiles a single .ml file to a .cmo
   */
  private void generateSingleMLBytecodeCompilation(
      Map<Path, ImmutableSortedSet<BuildRule>> sourceToRule,
      ImmutableList.Builder<SourcePath> cmoFiles,
      Path mlSource,
      ImmutableMap<Path, ImmutableList<Path>> sources,
      ImmutableList<Path> cycleDetector) {

    ImmutableList<Path> newCycleDetector = ImmutableList.<Path>builder()
        .addAll(cycleDetector)
        .add(mlSource)
        .build();

    if (cycleDetector.contains(mlSource)) {
      throw new HumanReadableException("Dependency cycle detected: %s",
          Joiner.on(" -> ").join(newCycleDetector));
    }
    if (sourceToRule.containsKey(mlSource)) {
      return;
    }

    ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
    if (sources.containsKey(mlSource)) {
      for (Path dep : checkNotNull(sources.get(mlSource))) {
        generateSingleMLBytecodeCompilation(
            sourceToRule,
            cmoFiles,
            dep,
            sources,
            newCycleDetector);
        depsBuilder.addAll(checkNotNull(sourceToRule.get(dep)));
      }
    }
    ImmutableSortedSet<BuildRule> deps = depsBuilder.build();

    String name = mlSource.toFile().getName();
    BuildTarget buildTarget = createMLBytecodeCompileBuildTarget(
        params.getBuildTarget(),
        name);

    BuildRuleParams compileParams = params.copyWithChanges(
        buildTarget,
        Suppliers.ofInstance(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(params.getDeclaredDeps().get())
                .addAll(deps)
                .addAll(ocamlContext.getBytecodeCompileDeps())
                .build()),
        params.getExtraDeps());

    String outputFileName = getMLBytecodeOutputName(name);
    Path outputPath = ocamlContext.getCompileBytecodeOutputDir()
        .resolve(outputFileName);
    final ImmutableList<String> compileFlags = getCompileFlags(
        /* isBytecode */ true,
        /* excludeDeps */ false);
    BuildRule compileBytecode = new OCamlMLCompile(
        compileParams,
        pathResolver,
        new OCamlMLCompileStep.Args(
            params.getProjectFilesystem().getAbsolutifier(),
            cCompiler.getEnvironment(pathResolver),
            cCompiler.getCommandPrefix(pathResolver),
            ocamlContext.getOcamlBytecodeCompiler().get(),
            outputPath,
            mlSource,
            compileFlags));
    resolver.addToIndex(compileBytecode);
    sourceToRule.put(
        mlSource,
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .add(compileBytecode)
            .addAll(deps)
            .build());
    if (!outputFileName.endsWith(OCamlCompilables.OCAML_CMI)) {
      cmoFiles.add(
          new BuildTargetSourcePath(compileBytecode.getBuildTarget()));
    }
  }

}
