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
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** A generator of fine-grained OCaml build rules */
public class OcamlBuildRulesGenerator {

  private static final Flavor DEBUG_FLAVOR = InternalFlavor.of("debug");

  private final BuildRuleParams params;
  private final BuildRuleResolver resolver;
  private final SourcePathRuleFinder ruleFinder;
  private final SourcePathResolver pathResolver;
  private final OcamlBuildContext ocamlContext;
  private final ImmutableMap<Path, ImmutableList<Path>> mlInput;
  private final ImmutableList<SourcePath> cInput;

  private final Compiler cCompiler;
  private final Compiler cxxCompiler;
  private final boolean bytecodeOnly;
  private final boolean buildNativePlugin;

  private BuildRule cleanRule;

  public OcamlBuildRulesGenerator(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      BuildRuleResolver resolver,
      OcamlBuildContext ocamlContext,
      ImmutableMap<Path, ImmutableList<Path>> mlInput,
      ImmutableList<SourcePath> cInput,
      Compiler cCompiler,
      Compiler cxxCompiler,
      boolean bytecodeOnly,
      boolean buildNativePlugin) {
    this.params = params;
    this.pathResolver = pathResolver;
    this.ruleFinder = ruleFinder;
    this.resolver = resolver;
    this.ocamlContext = ocamlContext;
    this.mlInput = mlInput;
    this.cInput = cInput;
    this.cCompiler = cCompiler;
    this.cxxCompiler = cxxCompiler;
    this.bytecodeOnly = bytecodeOnly;
    this.buildNativePlugin = buildNativePlugin;
    this.cleanRule = generateCleanBuildRule(params, ocamlContext);
  }

  /** Generates build rules for both the native and bytecode outputs */
  OcamlGeneratedBuildRules generate() {

    // TODO(): The order of rules added to "rules" matters - the OcamlRuleBuilder
    // object currently assumes that the native or bytecode compilation rule will
    // be the first one in the list. We should eliminate this restriction.
    ImmutableList.Builder<BuildRule> rules = ImmutableList.builder();
    ImmutableList.Builder<BuildRule> nativeCompileDeps = ImmutableList.builder();
    ImmutableList.Builder<BuildRule> bytecodeCompileDeps = ImmutableList.builder();

    ImmutableList<SourcePath> objFiles = generateCCompilation(cInput);

    if (!this.bytecodeOnly) {
      ImmutableList<SourcePath> cmxFiles = generateMLNativeCompilation(mlInput);
      nativeCompileDeps.addAll(ruleFinder.filterBuildRuleInputs(cmxFiles));
      BuildRule nativeLink =
          generateNativeLinking(
              ImmutableList.<SourcePath>builder()
                  .addAll(Iterables.concat(cmxFiles, objFiles))
                  .build());
      rules.add(nativeLink);
    }

    ImmutableList<SourcePath> cmoFiles = generateMLBytecodeCompilation(mlInput);
    bytecodeCompileDeps.addAll(ruleFinder.filterBuildRuleInputs(cmoFiles));
    BuildRule bytecodeLink =
        generateBytecodeLinking(
            ImmutableList.<SourcePath>builder()
                .addAll(Iterables.concat(cmoFiles, objFiles))
                .build());
    rules.add(bytecodeLink);

    if (!ocamlContext.isLibrary()) {
      rules.add(generateDebugLauncherRule());
    }

    return OcamlGeneratedBuildRules.builder()
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
    Preconditions.checkArgument(OcamlCompilables.SOURCE_EXTENSIONS.contains(ext));
    return base + ".o";
  }

  public static BuildTarget createCCompileBuildTarget(BuildTarget target, String name) {
    return BuildTarget.builder(target)
        .addFlavors(
            InternalFlavor.of(
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
      BuildTarget target = createCCompileBuildTarget(params.getBuildTarget(), name);

      BuildRuleParams cCompileParams =
          params
              .withBuildTarget(target)
              .copyReplacingDeclaredAndExtraDeps(
                  Suppliers.ofInstance(
                      ImmutableSortedSet.<BuildRule>naturalOrder()
                          // Depend on the rule that generates the sources and headers we're compiling.
                          .addAll(ruleFinder.filterBuildRuleInputs(cSrc))
                          // Add any deps from the C/C++ preprocessor input.
                          .addAll(cxxPreprocessorInput.getDeps(resolver, ruleFinder))
                          // Add the clean rule, to ensure that any shared output folders shared with
                          // OCaml build artifacts are properly cleaned.
                          .add(this.cleanRule)
                          // Add deps from the C compiler, since we're calling it.
                          .addAll(cCompiler.getDeps(ruleFinder))
                          .addAll(params.getDeclaredDeps().get())
                          .build()),
                  params.getExtraDeps());

      Path outputPath = ocamlContext.getCOutput(pathResolver.getRelativePath(cSrc));
      OcamlCCompile compileRule =
          new OcamlCCompile(
              cCompileParams,
              new OcamlCCompileStep.Args(
                  cCompiler.getEnvironment(pathResolver),
                  cCompiler.getCommandPrefix(pathResolver),
                  ocamlContext.getOcamlCompiler().get(),
                  ocamlContext.getOcamlInteropIncludesDir(),
                  outputPath,
                  cSrc,
                  cCompileFlags.build(),
                  cxxPreprocessorInput.getIncludes()));
      resolver.addToIndex(compileRule);
      objects.add(compileRule.getSourcePathToOutput());
    }
    return objects.build();
  }

  private BuildRule generateCleanBuildRule(BuildRuleParams params, OcamlBuildContext ocamlContext) {
    BuildTarget cleanTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(
                InternalFlavor.of(
                    String.format("clean-%s", params.getBuildTarget().getShortName())))
            .build();

    BuildRuleParams cleanParams = params.withBuildTarget(cleanTarget);

    BuildRule cleanRule = new OcamlClean(cleanParams, ocamlContext);
    resolver.addToIndex(cleanRule);
    return cleanRule;
  }

  public static BuildTarget addDebugFlavor(BuildTarget target) {
    return BuildTarget.builder(target).addFlavors(DEBUG_FLAVOR).build();
  }

  private BuildRule generateDebugLauncherRule() {
    BuildRuleParams debugParams =
        params
            .withBuildTarget(addDebugFlavor(params.getBuildTarget()))
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(ImmutableSortedSet.of()),
                Suppliers.ofInstance(ImmutableSortedSet.of()));

    OcamlDebugLauncher debugLauncher =
        new OcamlDebugLauncher(
            debugParams,
            new OcamlDebugLauncherStep.Args(
                ocamlContext.getOcamlDebug().get(),
                ocamlContext.getBytecodeOutput(),
                ocamlContext.getOcamlInput(),
                ocamlContext.getBytecodeIncludeFlags()));
    resolver.addToIndex(debugLauncher);
    return debugLauncher;
  }

  /** Links the .cmx files generated by the native compilation */
  private BuildRule generateNativeLinking(ImmutableList<SourcePath> allInputs) {
    BuildRuleParams linkParams =
        params.copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(ruleFinder.filterBuildRuleInputs(allInputs))
                    .addAll(
                        ocamlContext
                            .getNativeLinkableInput()
                            .getArgs()
                            .stream()
                            .flatMap(arg -> arg.getDeps(ruleFinder).stream())
                            .iterator())
                    .addAll(
                        ocamlContext
                            .getCLinkableInput()
                            .getArgs()
                            .stream()
                            .flatMap(arg -> arg.getDeps(ruleFinder).stream())
                            .iterator())
                    .addAll(cxxCompiler.getDeps(ruleFinder))
                    .build()),
            Suppliers.ofInstance(ImmutableSortedSet.of()));

    ImmutableList.Builder<Arg> flags = ImmutableList.builder();
    flags.addAll(ocamlContext.getFlags());
    flags.addAll(StringArg.from(ocamlContext.getCommonCLinkerFlags()));

    OcamlLink link =
        new OcamlLink(
            linkParams,
            allInputs,
            cxxCompiler.getEnvironment(pathResolver),
            cxxCompiler.getCommandPrefix(pathResolver),
            ocamlContext.getOcamlCompiler().get(),
            flags.build(),
            ocamlContext.getOcamlInteropIncludesDir(),
            ocamlContext.getNativeOutput(),
            ocamlContext.getNativePluginOutput(),
            ocamlContext.getNativeLinkableInput().getArgs(),
            ocamlContext.getCLinkableInput().getArgs(),
            ocamlContext.isLibrary(),
            /* isBytecode */ false,
            buildNativePlugin);
    resolver.addToIndex(link);
    return link;
  }

  private static final Flavor BYTECODE_FLAVOR = InternalFlavor.of("bytecode");

  public static BuildTarget addBytecodeFlavor(BuildTarget target) {
    return BuildTarget.builder(target).addFlavors(BYTECODE_FLAVOR).build();
  }

  /** Links the .cmo files generated by the bytecode compilation */
  private BuildRule generateBytecodeLinking(ImmutableList<SourcePath> allInputs) {
    BuildRuleParams linkParams =
        params
            .withBuildTarget(addBytecodeFlavor(params.getBuildTarget()))
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(ruleFinder.filterBuildRuleInputs(allInputs))
                        .addAll(ocamlContext.getBytecodeLinkDeps())
                        .addAll(
                            Stream.concat(
                                    ocamlContext.getBytecodeLinkableInput().getArgs().stream(),
                                    ocamlContext.getCLinkableInput().getArgs().stream())
                                .flatMap(arg -> arg.getDeps(ruleFinder).stream())
                                .filter(rule -> !(rule instanceof OcamlBuild))
                                .iterator())
                        .addAll(cxxCompiler.getDeps(ruleFinder))
                        .build()),
                Suppliers.ofInstance(ImmutableSortedSet.of()));

    ImmutableList.Builder<Arg> flags = ImmutableList.builder();
    flags.addAll(ocamlContext.getFlags());
    flags.addAll(StringArg.from(ocamlContext.getCommonCLinkerFlags()));

    OcamlLink link =
        new OcamlLink(
            linkParams,
            allInputs,
            cxxCompiler.getEnvironment(pathResolver),
            cxxCompiler.getCommandPrefix(pathResolver),
            ocamlContext.getOcamlBytecodeCompiler().get(),
            flags.build(),
            ocamlContext.getOcamlInteropIncludesDir(),
            ocamlContext.getBytecodeOutput(),
            ocamlContext.getNativePluginOutput(),
            ocamlContext.getBytecodeLinkableInput().getArgs(),
            ocamlContext.getCLinkableInput().getArgs(),
            ocamlContext.isLibrary(),
            /* isBytecode */ true,
            /* buildNativePlugin */ false);
    resolver.addToIndex(link);
    return link;
  }

  private ImmutableList<Arg> getCompileFlags(boolean isBytecode, boolean excludeDeps) {
    String output =
        isBytecode
            ? ocamlContext.getCompileBytecodeOutputDir().toString()
            : ocamlContext.getCompileNativeOutputDir().toString();
    ImmutableList.Builder<Arg> flagBuilder = ImmutableList.builder();
    flagBuilder.addAll(StringArg.from(ocamlContext.getIncludeFlags(isBytecode, excludeDeps)));
    flagBuilder.addAll(ocamlContext.getFlags());
    flagBuilder.add(StringArg.of(OcamlCompilables.OCAML_INCLUDE_FLAG), StringArg.of(output));
    return flagBuilder.build();
  }

  /** The native-code executable */
  private static String getMLNativeOutputName(String name) {
    String base = Files.getNameWithoutExtension(name);
    String ext = Files.getFileExtension(name);
    Preconditions.checkArgument(
        OcamlCompilables.SOURCE_EXTENSIONS.contains(ext), "Unexpected extension: " + ext);
    String dotExt = "." + ext;
    if (dotExt.equals(OcamlCompilables.OCAML_ML) || dotExt.equals(OcamlCompilables.OCAML_RE)) {
      return base + OcamlCompilables.OCAML_CMX;
    } else if (dotExt.equals(OcamlCompilables.OCAML_MLI)
        || dotExt.equals(OcamlCompilables.OCAML_REI)) {
      return base + OcamlCompilables.OCAML_CMI;
    } else {
      Preconditions.checkState(false, "Unexpected extension: " + ext);
      return base;
    }
  }

  /** The bytecode output (which is also executable) */
  private static String getMLBytecodeOutputName(String name) {
    String base = Files.getNameWithoutExtension(name);
    String ext = Files.getFileExtension(name);
    Preconditions.checkArgument(OcamlCompilables.SOURCE_EXTENSIONS.contains(ext));
    String dotExt = "." + ext;
    if (dotExt.equals(OcamlCompilables.OCAML_ML) || dotExt.equals(OcamlCompilables.OCAML_RE)) {
      return base + OcamlCompilables.OCAML_CMO;
    } else if (dotExt.equals(OcamlCompilables.OCAML_MLI)
        || dotExt.equals(OcamlCompilables.OCAML_REI)) {
      return base + OcamlCompilables.OCAML_CMI;
    } else {
      Preconditions.checkState(false, "Unexpected extension: " + ext);
      return base;
    }
  }

  public static BuildTarget createMLNativeCompileBuildTarget(BuildTarget target, String name) {
    return BuildTarget.builder(target)
        .addFlavors(
            InternalFlavor.of(
                String.format(
                    "ml-compile-%s",
                    getMLNativeOutputName(name)
                        .replace('/', '-')
                        .replace('.', '-')
                        .replace('+', '-')
                        .replace(' ', '-'))))
        .build();
  }

  public static BuildTarget createMLBytecodeCompileBuildTarget(BuildTarget target, String name) {
    return BuildTarget.builder(target)
        .addFlavors(
            InternalFlavor.of(
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

    final Map<Path, ImmutableSortedSet<BuildRule>> sourceToRule = new HashMap<>();

    for (ImmutableMap.Entry<Path, ImmutableList<Path>> mlSource : mlSources.entrySet()) {
      generateSingleMLNativeCompilation(
          sourceToRule, cmxFiles, mlSource.getKey(), mlSources, ImmutableList.of());
    }
    return cmxFiles.build();
  }

  /** Compiles a single .ml file to a .cmx */
  private void generateSingleMLNativeCompilation(
      Map<Path, ImmutableSortedSet<BuildRule>> sourceToRule,
      ImmutableList.Builder<SourcePath> cmxFiles,
      Path mlSource,
      ImmutableMap<Path, ImmutableList<Path>> sources,
      ImmutableList<Path> cycleDetector) {

    ImmutableList<Path> newCycleDetector =
        ImmutableList.<Path>builder().addAll(cycleDetector).add(mlSource).build();

    if (cycleDetector.contains(mlSource)) {
      throw new HumanReadableException(
          "Dependency cycle detected: %s", Joiner.on(" -> ").join(newCycleDetector));
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

    BuildTarget buildTarget = createMLNativeCompileBuildTarget(params.getBuildTarget(), name);

    BuildRuleParams compileParams =
        params
            .withBuildTarget(buildTarget)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(params.getDeclaredDeps().get())
                        .add(this.cleanRule)
                        .addAll(deps)
                        .addAll(ocamlContext.getNativeCompileDeps())
                        .addAll(cCompiler.getDeps(ruleFinder))
                        .build()),
                params.getExtraDeps());

    String outputFileName = getMLNativeOutputName(name);
    Path outputPath = ocamlContext.getCompileNativeOutputDir().resolve(outputFileName);
    final ImmutableList<Arg> compileFlags =
        getCompileFlags(/* isBytecode */ false, /* excludeDeps */ false);
    OcamlMLCompile compile =
        new OcamlMLCompile(
            compileParams,
            new OcamlMLCompileStep.Args(
                params.getProjectFilesystem()::resolve,
                cCompiler.getEnvironment(pathResolver),
                cCompiler.getCommandPrefix(pathResolver),
                ocamlContext.getOcamlCompiler().get(),
                ocamlContext.getOcamlInteropIncludesDir(),
                outputPath,
                mlSource,
                compileFlags));
    resolver.addToIndex(compile);
    sourceToRule.put(
        mlSource, ImmutableSortedSet.<BuildRule>naturalOrder().add(compile).addAll(deps).build());
    if (!outputFileName.endsWith(OcamlCompilables.OCAML_CMI)) {
      cmxFiles.add(compile.getSourcePathToOutput());
    }
  }

  private ImmutableList<SourcePath> generateMLBytecodeCompilation(
      ImmutableMap<Path, ImmutableList<Path>> mlSources) {
    ImmutableList.Builder<SourcePath> cmoFiles = ImmutableList.builder();

    final Map<Path, ImmutableSortedSet<BuildRule>> sourceToRule = new HashMap<>();

    for (ImmutableMap.Entry<Path, ImmutableList<Path>> mlSource : mlSources.entrySet()) {
      generateSingleMLBytecodeCompilation(
          sourceToRule, cmoFiles, mlSource.getKey(), mlSources, ImmutableList.of());
    }
    return cmoFiles.build();
  }

  /** Compiles a single .ml file to a .cmo */
  private void generateSingleMLBytecodeCompilation(
      Map<Path, ImmutableSortedSet<BuildRule>> sourceToRule,
      ImmutableList.Builder<SourcePath> cmoFiles,
      Path mlSource,
      ImmutableMap<Path, ImmutableList<Path>> sources,
      ImmutableList<Path> cycleDetector) {

    ImmutableList<Path> newCycleDetector =
        ImmutableList.<Path>builder().addAll(cycleDetector).add(mlSource).build();

    if (cycleDetector.contains(mlSource)) {
      throw new HumanReadableException(
          "Dependency cycle detected: %s", Joiner.on(" -> ").join(newCycleDetector));
    }
    if (sourceToRule.containsKey(mlSource)) {
      return;
    }

    ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
    if (sources.containsKey(mlSource)) {
      for (Path dep : checkNotNull(sources.get(mlSource))) {
        generateSingleMLBytecodeCompilation(sourceToRule, cmoFiles, dep, sources, newCycleDetector);
        depsBuilder.addAll(checkNotNull(sourceToRule.get(dep)));
      }
    }
    ImmutableSortedSet<BuildRule> deps = depsBuilder.build();

    String name = mlSource.toFile().getName();
    BuildTarget buildTarget = createMLBytecodeCompileBuildTarget(params.getBuildTarget(), name);

    BuildRuleParams compileParams =
        params
            .withBuildTarget(buildTarget)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .add(this.cleanRule)
                        .addAll(params.getDeclaredDeps().get())
                        .addAll(deps)
                        .addAll(ocamlContext.getBytecodeCompileDeps())
                        .addAll(cCompiler.getDeps(ruleFinder))
                        .build()),
                params.getExtraDeps());

    String outputFileName = getMLBytecodeOutputName(name);
    Path outputPath = ocamlContext.getCompileBytecodeOutputDir().resolve(outputFileName);
    final ImmutableList<Arg> compileFlags =
        getCompileFlags(/* isBytecode */ true, /* excludeDeps */ false);
    BuildRule compileBytecode =
        new OcamlMLCompile(
            compileParams,
            new OcamlMLCompileStep.Args(
                params.getProjectFilesystem()::resolve,
                cCompiler.getEnvironment(pathResolver),
                cCompiler.getCommandPrefix(pathResolver),
                ocamlContext.getOcamlBytecodeCompiler().get(),
                ocamlContext.getOcamlInteropIncludesDir(),
                outputPath,
                mlSource,
                compileFlags));
    resolver.addToIndex(compileBytecode);
    sourceToRule.put(
        mlSource,
        ImmutableSortedSet.<BuildRule>naturalOrder().add(compileBytecode).addAll(deps).build());
    if (!outputFileName.endsWith(OcamlCompilables.OCAML_CMI)) {
      cmoFiles.add(compileBytecode.getSourcePathToOutput());
    }
  }
}
