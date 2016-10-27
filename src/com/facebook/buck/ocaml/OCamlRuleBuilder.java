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

package com.facebook.buck.ocaml;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleDependencyVisitors;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.coercer.OCamlSource;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Compute transitive dependencies and generate ocaml build rules
 */
public class OCamlRuleBuilder {

  private static final Flavor OCAML_STATIC_FLAVOR = ImmutableFlavor.of("static");
  private static final Flavor OCAML_LINK_BINARY_FLAVOR = ImmutableFlavor.of("binary");

  private OCamlRuleBuilder() {
  }

  public static Function<BuildRule, ImmutableList<String>> getLibInclude(
      final boolean isBytecode) {
    return
        input -> {
          if (input instanceof OCamlLibrary) {
            OCamlLibrary library = (OCamlLibrary) input;
            if (isBytecode) {
                return ImmutableList.copyOf(library.getBytecodeIncludeDirs());
            } else {
              return ImmutableList.of(library.getIncludeLibDir().toString());
            }
          } else {
            return ImmutableList.of();
          }
        };
  }

  public static ImmutableList<SourcePath> getInput(Iterable<OCamlSource> source) {
    return ImmutableList.copyOf(
        FluentIterable.from(source)
            .transform(
                OCamlSource::getSource)
    );
  }

  @VisibleForTesting
  protected static BuildTarget createStaticLibraryBuildTarget(BuildTarget target) {
    return BuildTarget.builder(target).addFlavors(OCAML_STATIC_FLAVOR).build();
  }

  @VisibleForTesting
  protected static BuildTarget createOCamlLinkTarget(BuildTarget target) {
    return BuildTarget.builder(target).addFlavors(OCAML_LINK_BINARY_FLAVOR).build();
  }

  public static AbstractBuildRule createBuildRule(
      OCamlBuckConfig ocamlBuckConfig,
      final BuildRuleParams params,
      BuildRuleResolver resolver,
      ImmutableList<OCamlSource> srcs,
      boolean isLibrary,
      boolean bytecodeOnly,
      ImmutableList<String> argFlags,
      final ImmutableList<String> linkerFlags) throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    boolean noYaccOrLexSources = FluentIterable.from(srcs).transform(OCamlSource::getSource)
        .filter(OCamlUtil.sourcePathExt(
                  pathResolver,
                  OCamlCompilables.OCAML_MLL,
                  OCamlCompilables.OCAML_MLY))
        .isEmpty();
    boolean noGeneratedSources = FluentIterable.from(srcs).transform(OCamlSource::getSource)
        .filter(BuildTargetSourcePath.class)
        .isEmpty();
    if (noYaccOrLexSources && noGeneratedSources) {
      return createFineGrainedBuildRule(
          ocamlBuckConfig,
          params,
          resolver,
          srcs,
          isLibrary,
          bytecodeOnly,
          argFlags,
          linkerFlags);
    } else {
      return createBulkBuildRule(
          ocamlBuckConfig,
          params,
          resolver,
          srcs,
          isLibrary,
          bytecodeOnly,
          argFlags,
          linkerFlags);
    }
  }

  private static ImmutableList<BuildRule> getTransitiveOCamlLibraryDeps(Iterable<BuildRule> deps) {
    return TopologicalSort.sort(
        BuildRuleDependencyVisitors.getBuildRuleDirectedGraphFilteredBy(
            deps,
            OCamlLibrary.class::isInstance,
            OCamlLibrary.class::isInstance),
        x -> true);
  }

  private static NativeLinkableInput getNativeLinkableInput(Iterable<BuildRule> deps) {
    List<NativeLinkableInput> inputs = Lists.newArrayList();

    // Add in the linkable input from OCaml libraries.
    ImmutableList<BuildRule> ocamlDeps = getTransitiveOCamlLibraryDeps(deps);
    for (BuildRule dep : ocamlDeps) {
      inputs.add(((OCamlLibrary) dep).getNativeLinkableInput());
    }

    return NativeLinkableInput.concat(inputs);
  }

  private static NativeLinkableInput getBytecodeLinkableInput(Iterable<BuildRule> deps) {
    List<NativeLinkableInput> inputs = Lists.newArrayList();

    // Add in the linkable input from OCaml libraries.
    ImmutableList<BuildRule> ocamlDeps = getTransitiveOCamlLibraryDeps(deps);
    for (BuildRule dep : ocamlDeps) {
      inputs.add(((OCamlLibrary) dep).getBytecodeLinkableInput());
    }

    return NativeLinkableInput.concat(inputs);
  }

  private static NativeLinkableInput getCLinkableInput(
      CxxPlatform cxxPlatform,
      Iterable<BuildRule> deps) throws NoSuchBuildTargetException {
    return NativeLinkables.getTransitiveNativeLinkableInput(
        cxxPlatform,
        deps,
        Linker.LinkableDepType.STATIC,
        OCamlLibrary.class::isInstance);
  }

  public static AbstractBuildRule createBulkBuildRule(
      OCamlBuckConfig ocamlBuckConfig,
      final BuildRuleParams params,
      BuildRuleResolver resolver,
      ImmutableList<OCamlSource> srcs,
      boolean isLibrary,
      boolean bytecodeOnly,
      ImmutableList<String> argFlags,
      final ImmutableList<String> linkerFlags) throws NoSuchBuildTargetException {
    CxxPreprocessorInput cxxPreprocessorInputFromDeps =
      CxxPreprocessorInput.concat(
          CxxPreprocessables.getTransitiveCxxPreprocessorInput(
              ocamlBuckConfig.getCxxPlatform(),
              FluentIterable.from(params.getDeps())
                  .filter(CxxPreprocessorDep.class::isInstance)));

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    ImmutableList<String> nativeIncludes = FluentIterable.from(params.getDeps())
        .transformAndConcat(getLibInclude(false))
        .toList();

    ImmutableList<String> bytecodeIncludes = FluentIterable.from(params.getDeps())
        .transformAndConcat(getLibInclude(true))
        .toList();

    NativeLinkableInput nativeLinkableInput =
        getNativeLinkableInput(params.getDeps());
    NativeLinkableInput bytecodeLinkableInput =
        getBytecodeLinkableInput(params.getDeps());
    NativeLinkableInput cLinkableInput =
        getCLinkableInput(ocamlBuckConfig.getCxxPlatform(), params.getDeps());

    ImmutableList<OCamlLibrary> ocamlInput = OCamlUtil.getTransitiveOCamlInput(params.getDeps());

    ImmutableSortedSet.Builder<BuildRule> allDepsBuilder = ImmutableSortedSet.naturalOrder();
    allDepsBuilder.addAll(pathResolver.filterBuildRuleInputs(getInput(srcs)));
    allDepsBuilder.addAll(
        Stream.of(nativeLinkableInput, bytecodeLinkableInput, cLinkableInput)
            .flatMap(input -> input.getArgs().stream())
            .flatMap(arg -> arg.getDeps(pathResolver).stream())
            .iterator());
    for (OCamlLibrary library : ocamlInput) {
      allDepsBuilder.addAll(library.getNativeCompileDeps());
      allDepsBuilder.addAll(library.getBytecodeCompileDeps());
    }
    allDepsBuilder.addAll(
        pathResolver.filterBuildRuleInputs(
            ocamlBuckConfig.getCCompiler().resolve(resolver).getInputs()));
    allDepsBuilder.addAll(
        pathResolver.filterBuildRuleInputs(
            ocamlBuckConfig.getCxxCompiler().resolve(resolver).getInputs()));

    // The bulk rule will do preprocessing on sources, and so needs deps from the preprocessor
    // input object.
    allDepsBuilder.addAll(cxxPreprocessorInputFromDeps.getDeps(resolver, pathResolver));

    ImmutableSortedSet<BuildRule> allDeps = allDepsBuilder.build();

    BuildTarget buildTarget =
        isLibrary ? createStaticLibraryBuildTarget(params.getBuildTarget())
            : createOCamlLinkTarget(params.getBuildTarget());
    final BuildRuleParams compileParams = params.copyWithChanges(
        buildTarget,
        /* declaredDeps */ Suppliers.ofInstance(allDeps),
        /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.of()));

    ImmutableList.Builder<String> flagsBuilder = ImmutableList.builder();
    flagsBuilder.addAll(argFlags);

    ImmutableSortedSet.Builder<BuildRule> nativeCompileDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<BuildRule> bytecodeCompileDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<BuildRule> bytecodeLinkDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    for (OCamlLibrary library : ocamlInput) {
      nativeCompileDepsBuilder.addAll(library.getNativeCompileDeps());
      bytecodeCompileDepsBuilder.addAll(library.getBytecodeCompileDeps());
      bytecodeLinkDepsBuilder.addAll(library.getBytecodeLinkDeps());
    }
    OCamlBuildContext ocamlContext =
        OCamlBuildContext.builder(ocamlBuckConfig)
            .setProjectFilesystem(params.getProjectFilesystem())
            .setSourcePathResolver(pathResolver)
            .setFlags(flagsBuilder.build())
            .setNativeIncludes(nativeIncludes)
            .setBytecodeIncludes(bytecodeIncludes)
            .setOCamlInput(ocamlInput)
            .setNativeLinkableInput(nativeLinkableInput)
            .setBytecodeLinkableInput(bytecodeLinkableInput)
            .setCLinkableInput(cLinkableInput)
            .setBuildTarget(buildTarget.getUnflavoredBuildTarget())
            .setLibrary(isLibrary)
            .setCxxPreprocessorInput(cxxPreprocessorInputFromDeps)
            .setInput(getInput(srcs))
            .setNativeCompileDeps(nativeCompileDepsBuilder.build())
            .setBytecodeCompileDeps(bytecodeCompileDepsBuilder.build())
            .setBytecodeLinkDeps(bytecodeLinkDepsBuilder.build())
            .setCPreprocessor(ocamlBuckConfig.getCPreprocessor().resolve(resolver))
            .build();

    final OCamlBuild ocamlLibraryBuild = new OCamlBuild(
        compileParams,
        pathResolver,
        ocamlContext,
        ocamlBuckConfig.getCCompiler().resolve(resolver),
        ocamlBuckConfig.getCxxCompiler().resolve(resolver),
        bytecodeOnly);
    resolver.addToIndex(ocamlLibraryBuild);

    if (isLibrary) {
      return new OCamlStaticLibrary(
          params.copyWithDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(params.getDeclaredDeps().get())
                      .add(ocamlLibraryBuild)
                      .build()),
              params.getExtraDeps()),
          pathResolver,
          compileParams,
          linkerFlags,
          FluentIterable.from(srcs)
              .transform(OCamlSource::getSource)
              .transform(pathResolver::getAbsolutePath)
              .filter(OCamlUtil.ext(OCamlCompilables.OCAML_C))
              .transform(ocamlContext::getCOutput)
              .transform(SourcePaths.getToBuildTargetSourcePath(compileParams.getBuildTarget()))
              .toList(),
          ocamlContext,
          ocamlLibraryBuild,
          ImmutableSortedSet.of(ocamlLibraryBuild),
          ImmutableSortedSet.of(ocamlLibraryBuild),
          ImmutableSortedSet.of(ocamlLibraryBuild));
    } else {
      return new OCamlBinary(
          params.copyWithDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(params.getDeclaredDeps().get())
                      .add(ocamlLibraryBuild)
                      .build()),
              params.getExtraDeps()),
          pathResolver,
          ocamlLibraryBuild);
    }
  }

  public static AbstractBuildRule createFineGrainedBuildRule(
      OCamlBuckConfig ocamlBuckConfig,
      final BuildRuleParams params,
      BuildRuleResolver resolver,
      ImmutableList<OCamlSource> srcs,
      boolean isLibrary,
      boolean bytecodeOnly,
      ImmutableList<String> argFlags,
      final ImmutableList<String> linkerFlags) throws NoSuchBuildTargetException {
    CxxPreprocessorInput cxxPreprocessorInputFromDeps =
      CxxPreprocessorInput.concat(
          CxxPreprocessables.getTransitiveCxxPreprocessorInput(
              ocamlBuckConfig.getCxxPlatform(),
              FluentIterable.from(params.getDeps())
                  .filter(CxxPreprocessorDep.class::isInstance)));

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    ImmutableList<String> nativeIncludes = FluentIterable.from(params.getDeps())
        .transformAndConcat(getLibInclude(false))
        .toList();

    ImmutableList<String> bytecodeIncludes = FluentIterable.from(params.getDeps())
        .transformAndConcat(getLibInclude(true))
        .toList();

    NativeLinkableInput nativeLinkableInput =
        getNativeLinkableInput(params.getDeps());
    NativeLinkableInput bytecodeLinkableInput =
        getBytecodeLinkableInput(params.getDeps());
    NativeLinkableInput cLinkableInput =
        getCLinkableInput(ocamlBuckConfig.getCxxPlatform(), params.getDeps());

    ImmutableList<OCamlLibrary> ocamlInput = OCamlUtil.getTransitiveOCamlInput(params.getDeps());

    BuildTarget buildTarget =
        isLibrary ? createStaticLibraryBuildTarget(params.getBuildTarget())
            : createOCamlLinkTarget(params.getBuildTarget());

    final BuildRuleParams compileParams = params.copyWithChanges(
        buildTarget,
        /* declaredDeps */ Suppliers.ofInstance(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(pathResolver.filterBuildRuleInputs(getInput(srcs)))
                .addAll(
                    Stream.of(nativeLinkableInput, bytecodeLinkableInput, cLinkableInput)
                    .flatMap(input -> input.getArgs().stream())
                    .flatMap(arg -> arg.getDeps(pathResolver).stream())
                    .iterator())
                .addAll(
                    pathResolver.filterBuildRuleInputs(
                        ocamlBuckConfig.getCCompiler().resolve(resolver).getInputs()))
                .addAll(
                    pathResolver.filterBuildRuleInputs(
                        ocamlBuckConfig.getCxxCompiler().resolve(resolver).getInputs()))
                .build()),
        /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.of()));

    ImmutableList.Builder<String> flagsBuilder = ImmutableList.builder();
    flagsBuilder.addAll(argFlags);

    ImmutableSortedSet.Builder<BuildRule> nativeCompileDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<BuildRule> bytecodeCompileDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<BuildRule> bytecodeLinkDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    for (OCamlLibrary library : ocamlInput) {
      nativeCompileDepsBuilder.addAll(library.getNativeCompileDeps());
      bytecodeCompileDepsBuilder.addAll(library.getBytecodeCompileDeps());
      bytecodeLinkDepsBuilder.addAll(library.getBytecodeLinkDeps());
    }
    OCamlBuildContext ocamlContext =
        OCamlBuildContext.builder(ocamlBuckConfig)
            .setProjectFilesystem(params.getProjectFilesystem())
            .setSourcePathResolver(pathResolver)
            .setFlags(flagsBuilder.build())
            .setNativeIncludes(nativeIncludes)
            .setBytecodeIncludes(bytecodeIncludes)
            .setOCamlInput(ocamlInput)
            .setNativeLinkableInput(nativeLinkableInput)
            .setBytecodeLinkableInput(bytecodeLinkableInput)
            .setCLinkableInput(cLinkableInput)
            .setBuildTarget(buildTarget.getUnflavoredBuildTarget())
            .setLibrary(isLibrary)
            .setCxxPreprocessorInput(cxxPreprocessorInputFromDeps)
            .setInput(getInput(srcs))
            .setNativeCompileDeps(nativeCompileDepsBuilder.build())
            .setBytecodeCompileDeps(bytecodeCompileDepsBuilder.build())
            .setBytecodeLinkDeps(bytecodeLinkDepsBuilder.build())
            .setCPreprocessor(ocamlBuckConfig.getCPreprocessor().resolve(resolver))
            .build();

    Path baseDir = params.getProjectFilesystem().getRootPath().toAbsolutePath();
    ImmutableMap<Path, ImmutableList<Path>> mlInput = getMLInputWithDeps(
        baseDir,
        ocamlContext);

    ImmutableList<SourcePath> cInput = getCInput(pathResolver, getInput(srcs));

    OCamlBuildRulesGenerator generator = new OCamlBuildRulesGenerator(
        compileParams,
        pathResolver,
        resolver,
        ocamlContext,
        mlInput,
        cInput,
        ocamlBuckConfig.getCCompiler().resolve(resolver),
        ocamlBuckConfig.getCxxCompiler().resolve(resolver),
        bytecodeOnly);

    OCamlGeneratedBuildRules result = generator.generate();

    if (isLibrary) {
      return new OCamlStaticLibrary(
          params.copyWithDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(params.getDeclaredDeps().get())
                      .addAll(result.getRules())
                      .build()),
              params.getExtraDeps()),
          pathResolver,
          compileParams,
          linkerFlags,
          result.getObjectFiles(),
          ocamlContext,
          result.getRules().get(0),
          result.getNativeCompileDeps(),
          result.getBytecodeCompileDeps(),
          ImmutableSortedSet.<BuildRule>naturalOrder()
              .add(result.getBytecodeLink())
              .addAll(pathResolver.filterBuildRuleInputs(result.getObjectFiles()))
              .build());
    } else {
      return new OCamlBinary(
          params.copyWithDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(params.getDeclaredDeps().get())
                      .addAll(result.getRules())
                      .build()),
              params.getExtraDeps()),
          pathResolver,
          result.getRules().get(0));
    }
  }

  private static ImmutableList<SourcePath> getCInput(
      SourcePathResolver resolver,
      ImmutableList<SourcePath> input) {
    return FluentIterable
        .from(input)
        .filter(OCamlUtil.sourcePathExt(resolver, OCamlCompilables.OCAML_C))
        .toList();
  }

  private static ImmutableMap<Path, ImmutableList<Path>> getMLInputWithDeps(
      Path baseDir,
      OCamlBuildContext ocamlContext) {
    OCamlDepToolStep depToolStep = new OCamlDepToolStep(
        baseDir,
        ocamlContext.getSourcePathResolver(),
        ocamlContext.getOcamlDepTool().get(),
        ocamlContext.getMLInput(),
        ocamlContext.getIncludeFlags(/* isBytecode */ false, /* excludeDeps */ true));
    ImmutableList<String> cmd = depToolStep.getShellCommandInternal(null);
    Optional<String> depsString;
    try {
      depsString = executeProcessAndGetStdout(baseDir, cmd);
    } catch (IOException e) {
      throw new HumanReadableException(
          e,
          "Unable to execute ocamldep due to io error: %s",
          Joiner.on(" ").join(cmd));
    } catch (InterruptedException e) {
      throw new HumanReadableException(e,
          "Unable to calculate dependencies. ocamldep is interrupted: %s",
          Joiner.on(" ").join(cmd));
    }
    if (depsString.isPresent()) {
      OCamlDependencyGraphGenerator graphGenerator = new OCamlDependencyGraphGenerator();
      return filterCurrentRuleInput(
          ocamlContext.getSourcePathResolver().getAllAbsolutePaths(ocamlContext.getMLInput()),
          graphGenerator.generateDependencyMap(depsString.get()));
    } else {
      throw new HumanReadableException("ocamldep execution failed");
    }
  }

  private static ImmutableMap<Path, ImmutableList<Path>> filterCurrentRuleInput(
      final List<Path> mlInput,
      ImmutableMap<Path, ImmutableList<Path>> deps) {
    ImmutableMap.Builder<Path, ImmutableList<Path>> builder = ImmutableMap.builder();
    for (ImmutableMap.Entry<Path, ImmutableList<Path>> entry : deps.entrySet()) {
      if (mlInput.contains(entry.getKey())) {
        builder.put(entry.getKey(),
            FluentIterable.from(entry.getValue())
              .filter(mlInput::contains).toList()
            );
      }
    }
    return builder.build();
  }

  private static Optional<String> executeProcessAndGetStdout(
      Path baseDir,
      ImmutableList<String> cmd) throws IOException, InterruptedException {
    ImmutableSet.Builder<ProcessExecutor.Option> options = ImmutableSet.builder();
    options.add(ProcessExecutor.Option.EXPECTING_STD_OUT);
    ProcessExecutor exe = new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutorParams params = ProcessExecutorParams.builder()
        .setCommand(cmd)
        .setDirectory(baseDir)
        .build();
    ProcessExecutor.Result result = exe.launchAndExecute(
        params,
        options.build(),
        /* stdin */ Optional.empty(),
        /* timeOutMs */ Optional.empty(),
        /* timeOutHandler */ Optional.empty());
    if (result.getExitCode() != 0) {
      throw new HumanReadableException(result.getStderr().get());
    }
    return result.getStdout();
  }
}
