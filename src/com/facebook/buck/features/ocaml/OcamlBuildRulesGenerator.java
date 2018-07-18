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

package com.facebook.buck.features.ocaml;

import static com.google.common.base.Preconditions.checkNotNull;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.toolchain.Compiler;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.RichStream;
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

  private final BuildTarget buildTarget;
  private final ProjectFilesystem projectFilesystem;
  private final BuildRuleParams params;
  private final ActionGraphBuilder graphBuilder;
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
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      ActionGraphBuilder graphBuilder,
      OcamlBuildContext ocamlContext,
      ImmutableMap<Path, ImmutableList<Path>> mlInput,
      ImmutableList<SourcePath> cInput,
      Compiler cCompiler,
      Compiler cxxCompiler,
      boolean bytecodeOnly,
      boolean buildNativePlugin) {
    this.buildTarget = buildTarget;
    this.projectFilesystem = projectFilesystem;
    this.params = params;
    this.pathResolver = pathResolver;
    this.ruleFinder = ruleFinder;
    this.graphBuilder = graphBuilder;
    this.ocamlContext = ocamlContext;
    this.mlInput = mlInput;
    this.cInput = cInput;
    this.cCompiler = cCompiler;
    this.cxxCompiler = cxxCompiler;
    this.bytecodeOnly = bytecodeOnly;
    this.buildNativePlugin = buildNativePlugin;
    this.cleanRule = generateCleanBuildRule(buildTarget, projectFilesystem, params, ocamlContext);
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
        .setOcamlContext(ocamlContext)
        .build();
  }

  private static String getCOutputName(String name) {
    String base = Files.getNameWithoutExtension(name);
    String ext = Files.getFileExtension(name);
    Preconditions.checkArgument(OcamlCompilables.SOURCE_EXTENSIONS.contains(ext));
    return base + ".o";
  }

  public static BuildTarget createCCompileBuildTarget(BuildTarget target, String name) {
    return target.withAppendedFlavors(
        InternalFlavor.of(
            String.format(
                "compile-%s",
                getCOutputName(name)
                    .replace('/', '-')
                    .replace('.', '-')
                    .replace('+', '-')
                    .replace(' ', '-'))));
  }

  private ImmutableList<SourcePath> generateCCompilation(ImmutableList<SourcePath> cInput) {

    ImmutableList.Builder<SourcePath> objects = ImmutableList.builder();

    ImmutableList.Builder<Arg> cCompileFlags = ImmutableList.builder();
    cCompileFlags.addAll(ocamlContext.getCCompileFlags());
    cCompileFlags.addAll(StringArg.from(ocamlContext.getCommonCFlags()));

    CxxPreprocessorInput cxxPreprocessorInput = ocamlContext.getCxxPreprocessorInput();

    for (SourcePath cSrc : cInput) {
      String name = pathResolver.getAbsolutePath(cSrc).toFile().getName();

      BuildRuleParams cCompileParams =
          params.withDeclaredDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      // Depend on the rule that generates the sources and headers we're compiling.
                      .addAll(ruleFinder.filterBuildRuleInputs(cSrc))
                      // Add any deps from the C/C++ preprocessor input.
                      .addAll(cxxPreprocessorInput.getDeps(graphBuilder, ruleFinder))
                      // Add the clean rule, to ensure that any shared output folders shared with
                      // OCaml build artifacts are properly cleaned.
                      .add(this.cleanRule)
                      // Add deps from the C compiler, since we're calling it.
                      .addAll(BuildableSupport.getDepsCollection(cCompiler, ruleFinder))
                      .addAll(params.getDeclaredDeps().get())
                      .addAll(
                          RichStream.from(ocamlContext.getCCompileFlags())
                              .flatMap(f -> BuildableSupport.getDeps(f, ruleFinder))
                              .toImmutableList())
                      .build()));

      Path outputPath = ocamlContext.getCOutput(pathResolver.getRelativePath(cSrc));
      OcamlCCompile compileRule =
          new OcamlCCompile(
              createCCompileBuildTarget(buildTarget, name),
              projectFilesystem,
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
      graphBuilder.addToIndex(compileRule);
      objects.add(compileRule.getSourcePathToOutput());
    }
    return objects.build();
  }

  private BuildRule generateCleanBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      OcamlBuildContext ocamlContext) {
    BuildTarget cleanTarget =
        buildTarget.withAppendedFlavors(
            InternalFlavor.of(String.format("clean-%s", buildTarget.getShortName())));

    BuildRule cleanRule = new OcamlClean(cleanTarget, projectFilesystem, params, ocamlContext);
    graphBuilder.addToIndex(cleanRule);
    return cleanRule;
  }

  public static BuildTarget addDebugFlavor(BuildTarget target) {
    return target.withAppendedFlavors(DEBUG_FLAVOR);
  }

  private BuildRule generateDebugLauncherRule() {
    BuildTarget debugBuildTarget = addDebugFlavor(buildTarget);

    OcamlDebugLauncher debugLauncher =
        new OcamlDebugLauncher(
            debugBuildTarget,
            projectFilesystem,
            params.withoutDeclaredDeps().withoutExtraDeps(),
            new OcamlDebugLauncherStep.Args(
                ocamlContext.getOcamlDebug().get(),
                ocamlContext.getBytecodeOutput(),
                ocamlContext.getTransitiveBytecodeIncludes(),
                ocamlContext.getBytecodeIncludeFlags()));
    graphBuilder.addToIndex(debugLauncher);
    return debugLauncher;
  }

  /** Links the .cmx files generated by the native compilation */
  private BuildRule generateNativeLinking(ImmutableList<SourcePath> allInputs) {
    BuildRuleParams linkParams =
        params
            .withDeclaredDeps(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(ruleFinder.filterBuildRuleInputs(allInputs))
                    .addAll(
                        ocamlContext
                            .getNativeLinkableInput()
                            .getArgs()
                            .stream()
                            .flatMap(arg -> BuildableSupport.getDeps(arg, ruleFinder))
                            .iterator())
                    .addAll(
                        ocamlContext
                            .getCLinkableInput()
                            .getArgs()
                            .stream()
                            .flatMap(arg -> BuildableSupport.getDeps(arg, ruleFinder))
                            .iterator())
                    .addAll(BuildableSupport.getDepsCollection(cxxCompiler, ruleFinder))
                    .build())
            .withoutExtraDeps();

    ImmutableList.Builder<Arg> flags = ImmutableList.builder();
    flags.addAll(ocamlContext.getFlags());
    flags.addAll(StringArg.from(ocamlContext.getCommonCLinkerFlags()));

    OcamlLink link =
        new OcamlLink(
            buildTarget,
            projectFilesystem,
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
    graphBuilder.addToIndex(link);
    return link;
  }

  private static final Flavor BYTECODE_FLAVOR = InternalFlavor.of("bytecode");

  public static BuildTarget addBytecodeFlavor(BuildTarget target) {
    return target.withAppendedFlavors(BYTECODE_FLAVOR);
  }

  /** Links the .cmo files generated by the bytecode compilation */
  private BuildRule generateBytecodeLinking(ImmutableList<SourcePath> allInputs) {
    BuildRuleParams linkParams =
        params
            .withDeclaredDeps(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(ruleFinder.filterBuildRuleInputs(allInputs))
                    .addAll(ocamlContext.getBytecodeLinkDeps())
                    .addAll(
                        Stream.concat(
                                ocamlContext.getBytecodeLinkableInput().getArgs().stream(),
                                ocamlContext.getCLinkableInput().getArgs().stream())
                            .flatMap(arg -> BuildableSupport.getDeps(arg, ruleFinder))
                            .filter(rule -> !(rule instanceof OcamlBuild))
                            .iterator())
                    .addAll(BuildableSupport.getDepsCollection(cxxCompiler, ruleFinder))
                    .build())
            .withoutExtraDeps();

    ImmutableList.Builder<Arg> flags = ImmutableList.builder();
    flags.addAll(ocamlContext.getFlags());
    flags.addAll(StringArg.from(ocamlContext.getCommonCLinkerFlags()));

    OcamlLink link =
        new OcamlLink(
            addBytecodeFlavor(buildTarget),
            projectFilesystem,
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
    graphBuilder.addToIndex(link);
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
    return target.withAppendedFlavors(
        InternalFlavor.of(
            String.format(
                "ml-compile-%s",
                getMLNativeOutputName(name)
                    .replace('/', '-')
                    .replace('.', '-')
                    .replace('+', '-')
                    .replace(' ', '-'))));
  }

  public static BuildTarget createMLBytecodeCompileBuildTarget(BuildTarget target, String name) {
    return target.withAppendedFlavors(
        InternalFlavor.of(
            String.format(
                "ml-bytecode-compile-%s",
                getMLBytecodeOutputName(name)
                    .replace('/', '-')
                    .replace('.', '-')
                    .replace('+', '-')
                    .replace(' ', '-'))));
  }

  ImmutableList<SourcePath> generateMLNativeCompilation(
      ImmutableMap<Path, ImmutableList<Path>> mlSources) {
    ImmutableList.Builder<SourcePath> cmxFiles = ImmutableList.builder();

    Map<Path, ImmutableSortedSet<BuildRule>> sourceToRule = new HashMap<>();

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

    BuildRuleParams compileParams =
        params.withDeclaredDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(params.getDeclaredDeps().get())
                    .add(this.cleanRule)
                    .addAll(deps)
                    .addAll(ocamlContext.getNativeCompileDeps())
                    .addAll(BuildableSupport.getDepsCollection(cCompiler, ruleFinder))
                    .build()));

    String outputFileName = getMLNativeOutputName(name);
    Path outputPath = ocamlContext.getCompileNativeOutputDir().resolve(outputFileName);
    ImmutableList<Arg> compileFlags =
        getCompileFlags(/* isBytecode */ false, /* excludeDeps */ false);
    OcamlMLCompile compile =
        new OcamlMLCompile(
            createMLNativeCompileBuildTarget(buildTarget, name),
            projectFilesystem,
            compileParams,
            new OcamlMLCompileStep.Args(
                cCompiler.getEnvironment(pathResolver),
                cCompiler.getCommandPrefix(pathResolver),
                ocamlContext.getOcamlCompiler().get(),
                ocamlContext.getOcamlInteropIncludesDir(),
                outputPath,
                mlSource,
                compileFlags));
    graphBuilder.addToIndex(compile);
    sourceToRule.put(
        mlSource, ImmutableSortedSet.<BuildRule>naturalOrder().add(compile).addAll(deps).build());
    if (!outputFileName.endsWith(OcamlCompilables.OCAML_CMI)) {
      cmxFiles.add(compile.getSourcePathToOutput());
    }
  }

  private ImmutableList<SourcePath> generateMLBytecodeCompilation(
      ImmutableMap<Path, ImmutableList<Path>> mlSources) {
    ImmutableList.Builder<SourcePath> cmoFiles = ImmutableList.builder();

    Map<Path, ImmutableSortedSet<BuildRule>> sourceToRule = new HashMap<>();

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

    BuildRuleParams compileParams =
        params.withDeclaredDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .add(this.cleanRule)
                    .addAll(params.getDeclaredDeps().get())
                    .addAll(deps)
                    .addAll(ocamlContext.getBytecodeCompileDeps())
                    .addAll(BuildableSupport.getDepsCollection(cCompiler, ruleFinder))
                    .build()));

    String outputFileName = getMLBytecodeOutputName(name);
    Path outputPath = ocamlContext.getCompileBytecodeOutputDir().resolve(outputFileName);
    ImmutableList<Arg> compileFlags =
        getCompileFlags(/* isBytecode */ true, /* excludeDeps */ false);
    BuildRule compileBytecode =
        new OcamlMLCompile(
            createMLBytecodeCompileBuildTarget(buildTarget, name),
            projectFilesystem,
            compileParams,
            new OcamlMLCompileStep.Args(
                cCompiler.getEnvironment(pathResolver),
                cCompiler.getCommandPrefix(pathResolver),
                ocamlContext.getOcamlBytecodeCompiler().get(),
                ocamlContext.getOcamlInteropIncludesDir(),
                outputPath,
                mlSource,
                compileFlags));
    graphBuilder.addToIndex(compileBytecode);
    sourceToRule.put(
        mlSource,
        ImmutableSortedSet.<BuildRule>naturalOrder().add(compileBytecode).addAll(deps).build());
    if (!outputFileName.endsWith(OcamlCompilables.OCAML_CMI)) {
      cmoFiles.add(compileBytecode.getSourcePathToOutput());
    }
  }
}
