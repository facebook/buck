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

import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleDependencyVisitors;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.OcamlSource;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/** Compute transitive dependencies and generate ocaml build rules */
public class OcamlRuleBuilder {

  private static final Flavor OCAML_STATIC_FLAVOR = InternalFlavor.of("static");
  private static final Flavor OCAML_LINK_BINARY_FLAVOR = InternalFlavor.of("binary");

  private OcamlRuleBuilder() {}

  public static Function<BuildRule, ImmutableList<String>> getLibInclude(final boolean isBytecode) {
    return input -> {
      if (input instanceof OcamlLibrary) {
        OcamlLibrary library = (OcamlLibrary) input;
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

  public static ImmutableList<SourcePath> getInput(Iterable<OcamlSource> source) {
    return ImmutableList.copyOf(FluentIterable.from(source).transform(OcamlSource::getSource));
  }

  @VisibleForTesting
  protected static BuildTarget createStaticLibraryBuildTarget(BuildTarget target) {
    return target.withAppendedFlavors(OCAML_STATIC_FLAVOR);
  }

  @VisibleForTesting
  protected static BuildTarget createOcamlLinkTarget(BuildTarget target) {
    return target.withAppendedFlavors(OCAML_LINK_BINARY_FLAVOR);
  }

  public static BuildRule createBuildRule(
      ToolchainProvider toolchainProvider,
      OcamlBuckConfig ocamlBuckConfig,
      BuildTarget buildTarget,
      final ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ImmutableList<OcamlSource> srcs,
      boolean isLibrary,
      boolean bytecodeOnly,
      ImmutableList<Arg> argFlags,
      final ImmutableList<String> linkerFlags,
      boolean buildNativePlugin) {
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    boolean noYaccOrLexSources =
        srcs.stream()
            .map(OcamlSource::getSource)
            .noneMatch(
                OcamlUtil.sourcePathExt(
                    pathResolver, OcamlCompilables.OCAML_MLL, OcamlCompilables.OCAML_MLY));
    boolean noGeneratedSources =
        FluentIterable.from(srcs)
            .transform(OcamlSource::getSource)
            .filter(BuildTargetSourcePath.class)
            .isEmpty();
    if (noYaccOrLexSources && noGeneratedSources) {
      return createFineGrainedBuildRule(
          toolchainProvider,
          ocamlBuckConfig,
          buildTarget,
          projectFilesystem,
          params,
          resolver,
          srcs,
          isLibrary,
          bytecodeOnly,
          argFlags,
          linkerFlags,
          buildNativePlugin);
    } else {
      return createBulkBuildRule(
          toolchainProvider,
          ocamlBuckConfig,
          buildTarget,
          projectFilesystem,
          params,
          resolver,
          srcs,
          isLibrary,
          bytecodeOnly,
          argFlags,
          linkerFlags);
    }
  }

  private static ImmutableList<BuildRule> getTransitiveOcamlLibraryDeps(Iterable<BuildRule> deps) {
    return TopologicalSort.sort(
        BuildRuleDependencyVisitors.getBuildRuleDirectedGraphFilteredBy(
            deps, OcamlLibrary.class::isInstance, OcamlLibrary.class::isInstance));
  }

  private static NativeLinkableInput getNativeLinkableInput(Iterable<BuildRule> deps) {
    List<NativeLinkableInput> inputs = new ArrayList<>();

    // Add in the linkable input from OCaml libraries.
    ImmutableList<BuildRule> ocamlDeps = getTransitiveOcamlLibraryDeps(deps);
    for (BuildRule dep : ocamlDeps) {
      inputs.add(((OcamlLibrary) dep).getNativeLinkableInput());
    }

    return NativeLinkableInput.concat(inputs);
  }

  private static NativeLinkableInput getBytecodeLinkableInput(Iterable<BuildRule> deps) {
    List<NativeLinkableInput> inputs = new ArrayList<>();

    // Add in the linkable input from OCaml libraries.
    ImmutableList<BuildRule> ocamlDeps = getTransitiveOcamlLibraryDeps(deps);
    for (BuildRule dep : ocamlDeps) {
      inputs.add(((OcamlLibrary) dep).getBytecodeLinkableInput());
    }

    return NativeLinkableInput.concat(inputs);
  }

  private static NativeLinkableInput getCLinkableInput(
      CxxPlatform cxxPlatform, Iterable<BuildRule> deps) {
    return NativeLinkables.getTransitiveNativeLinkableInput(
        cxxPlatform,
        deps,
        Linker.LinkableDepType.STATIC,
        r -> r instanceof OcamlLibrary ? Optional.of(r.getBuildDeps()) : Optional.empty());
  }

  public static BuildRule createBulkBuildRule(
      ToolchainProvider toolchainProvider,
      OcamlBuckConfig ocamlBuckConfig,
      BuildTarget buildTarget,
      final ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ImmutableList<OcamlSource> srcs,
      boolean isLibrary,
      boolean bytecodeOnly,
      ImmutableList<Arg> argFlags,
      final ImmutableList<String> linkerFlags) {
    CxxPlatform defaultCxxPlatform =
        toolchainProvider
            .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
            .getDefaultCxxPlatform();
    CxxPreprocessorInput cxxPreprocessorInputFromDeps =
        CxxPreprocessorInput.concat(
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                defaultCxxPlatform,
                FluentIterable.from(params.getBuildDeps())
                    .filter(CxxPreprocessorDep.class::isInstance)));
    OcamlToolchain ocamlToolchain =
        toolchainProvider.getByName(OcamlToolchain.DEFAULT_NAME, OcamlToolchain.class);

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    ImmutableList<String> nativeIncludes =
        FluentIterable.from(params.getBuildDeps())
            .transformAndConcat(getLibInclude(false)::apply)
            .toList();

    ImmutableList<String> bytecodeIncludes =
        FluentIterable.from(params.getBuildDeps())
            .transformAndConcat(getLibInclude(true)::apply)
            .toList();

    NativeLinkableInput nativeLinkableInput = getNativeLinkableInput(params.getBuildDeps());
    NativeLinkableInput bytecodeLinkableInput = getBytecodeLinkableInput(params.getBuildDeps());
    NativeLinkableInput cLinkableInput =
        getCLinkableInput(defaultCxxPlatform, params.getBuildDeps());

    ImmutableList<OcamlLibrary> ocamlInput =
        OcamlUtil.getTransitiveOcamlInput(params.getBuildDeps());

    ImmutableSortedSet.Builder<BuildRule> allDepsBuilder = ImmutableSortedSet.naturalOrder();
    allDepsBuilder.addAll(ruleFinder.filterBuildRuleInputs(getInput(srcs)));
    allDepsBuilder.addAll(
        Stream.of(nativeLinkableInput, bytecodeLinkableInput, cLinkableInput)
            .flatMap(input -> input.getArgs().stream())
            .flatMap(arg -> BuildableSupport.getDepsCollection(arg, ruleFinder).stream())
            .iterator());
    for (OcamlLibrary library : ocamlInput) {
      allDepsBuilder.addAll(library.getNativeCompileDeps());
      allDepsBuilder.addAll(library.getBytecodeCompileDeps());
    }
    allDepsBuilder.addAll(
        BuildableSupport.getDepsCollection(
            ocamlToolchain.getCCompiler().resolve(resolver), ruleFinder));
    allDepsBuilder.addAll(
        BuildableSupport.getDepsCollection(
            ocamlToolchain.getCxxCompiler().resolve(resolver), ruleFinder));
    allDepsBuilder.addAll(
        argFlags
            .stream()
            .flatMap(arg -> BuildableSupport.getDepsCollection(arg, ruleFinder).stream())
            .iterator());

    // The bulk rule will do preprocessing on sources, and so needs deps from the preprocessor
    // input object.
    allDepsBuilder.addAll(cxxPreprocessorInputFromDeps.getDeps(resolver, ruleFinder));

    ImmutableSortedSet<BuildRule> allDeps = allDepsBuilder.build();

    BuildTarget compileBuildTarget =
        isLibrary
            ? createStaticLibraryBuildTarget(buildTarget)
            : createOcamlLinkTarget(buildTarget);

    ImmutableList.Builder<Arg> flagsBuilder = ImmutableList.builder();
    flagsBuilder.addAll(argFlags);

    ImmutableSortedSet.Builder<BuildRule> nativeCompileDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<BuildRule> bytecodeCompileDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<BuildRule> bytecodeLinkDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    for (OcamlLibrary library : ocamlInput) {
      nativeCompileDepsBuilder.addAll(library.getNativeCompileDeps());
      bytecodeCompileDepsBuilder.addAll(library.getBytecodeCompileDeps());
      bytecodeLinkDepsBuilder.addAll(library.getBytecodeLinkDeps());
    }
    OcamlBuildContext ocamlContext =
        OcamlBuildContext.builder(ocamlToolchain, ocamlBuckConfig)
            .setProjectFilesystem(projectFilesystem)
            .setSourcePathResolver(pathResolver)
            .setFlags(flagsBuilder.build())
            .setNativeIncludes(nativeIncludes)
            .setBytecodeIncludes(bytecodeIncludes)
            .setOcamlInput(ocamlInput)
            .setNativeLinkableInput(nativeLinkableInput)
            .setBytecodeLinkableInput(bytecodeLinkableInput)
            .setCLinkableInput(cLinkableInput)
            .setBuildTarget(compileBuildTarget.getUnflavoredBuildTarget())
            .setLibrary(isLibrary)
            .setCxxPreprocessorInput(cxxPreprocessorInputFromDeps)
            .setInput(getInput(srcs))
            .setNativeCompileDeps(nativeCompileDepsBuilder.build())
            .setBytecodeCompileDeps(bytecodeCompileDepsBuilder.build())
            .setBytecodeLinkDeps(bytecodeLinkDepsBuilder.build())
            .setCPreprocessor(ocamlToolchain.getCPreprocessor().resolve(resolver))
            .build();

    final OcamlBuild ocamlLibraryBuild =
        new OcamlBuild(
            compileBuildTarget,
            projectFilesystem,
            params.withDeclaredDeps(allDeps).withoutExtraDeps(),
            ocamlContext,
            ocamlToolchain.getCCompiler().resolve(resolver),
            ocamlToolchain.getCxxCompiler().resolve(resolver),
            bytecodeOnly);
    resolver.addToIndex(ocamlLibraryBuild);

    if (isLibrary) {
      return new OcamlStaticLibrary(
          buildTarget,
          compileBuildTarget,
          projectFilesystem,
          params.withDeclaredDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(params.getDeclaredDeps().get())
                      .add(ocamlLibraryBuild)
                      .build())),
          linkerFlags,
          srcs.stream()
              .map(OcamlSource::getSource)
              .map(pathResolver::getAbsolutePath)
              .filter(OcamlUtil.ext(OcamlCompilables.OCAML_C))
              .map(ocamlContext::getCOutput)
              .map(input -> ExplicitBuildTargetSourcePath.of(compileBuildTarget, input))
              .collect(ImmutableList.toImmutableList()),
          ocamlContext,
          ocamlLibraryBuild,
          ImmutableSortedSet.of(ocamlLibraryBuild),
          ImmutableSortedSet.of(ocamlLibraryBuild),
          ImmutableSortedSet.of(ocamlLibraryBuild));
    } else {
      return new OcamlBinary(
          buildTarget,
          projectFilesystem,
          params.withDeclaredDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(params.getDeclaredDeps().get())
                      .add(ocamlLibraryBuild)
                      .build())),
          ocamlLibraryBuild);
    }
  }

  public static BuildRule createFineGrainedBuildRule(
      ToolchainProvider toolchainProvider,
      OcamlBuckConfig ocamlBuckConfig,
      BuildTarget buildTarget,
      final ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ImmutableList<OcamlSource> srcs,
      boolean isLibrary,
      boolean bytecodeOnly,
      ImmutableList<Arg> argFlags,
      final ImmutableList<String> linkerFlags,
      boolean buildNativePlugin) {
    CxxPlatform defaultCxxPlatform =
        toolchainProvider
            .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
            .getDefaultCxxPlatform();
    OcamlToolchain ocamlToolchain =
        toolchainProvider.getByName(OcamlToolchain.DEFAULT_NAME, OcamlToolchain.class);

    CxxPreprocessorInput cxxPreprocessorInputFromDeps =
        CxxPreprocessorInput.concat(
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                defaultCxxPlatform,
                FluentIterable.from(params.getBuildDeps())
                    .filter(CxxPreprocessorDep.class::isInstance)));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    ImmutableList<String> nativeIncludes =
        FluentIterable.from(params.getBuildDeps())
            .transformAndConcat(getLibInclude(false)::apply)
            .toList();

    ImmutableList<String> bytecodeIncludes =
        FluentIterable.from(params.getBuildDeps())
            .transformAndConcat(getLibInclude(true)::apply)
            .toList();

    NativeLinkableInput nativeLinkableInput = getNativeLinkableInput(params.getBuildDeps());
    NativeLinkableInput bytecodeLinkableInput = getBytecodeLinkableInput(params.getBuildDeps());
    NativeLinkableInput cLinkableInput =
        getCLinkableInput(defaultCxxPlatform, params.getBuildDeps());

    ImmutableList<OcamlLibrary> ocamlInput =
        OcamlUtil.getTransitiveOcamlInput(params.getBuildDeps());

    BuildTarget compileBuildTarget =
        isLibrary
            ? createStaticLibraryBuildTarget(buildTarget)
            : createOcamlLinkTarget(buildTarget);

    final BuildRuleParams compileParams =
        params
            .withDeclaredDeps(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(ruleFinder.filterBuildRuleInputs(getInput(srcs)))
                    .addAll(
                        Stream.of(nativeLinkableInput, bytecodeLinkableInput, cLinkableInput)
                            .flatMap(input -> input.getArgs().stream())
                            .flatMap(
                                arg -> BuildableSupport.getDepsCollection(arg, ruleFinder).stream())
                            .iterator())
                    .addAll(
                        argFlags
                            .stream()
                            .flatMap(
                                arg -> BuildableSupport.getDepsCollection(arg, ruleFinder).stream())
                            .iterator())
                    .addAll(
                        BuildableSupport.getDepsCollection(
                            ocamlToolchain.getCCompiler().resolve(resolver), ruleFinder))
                    .addAll(
                        BuildableSupport.getDepsCollection(
                            ocamlToolchain.getCxxCompiler().resolve(resolver), ruleFinder))
                    .build())
            .withoutExtraDeps();

    ImmutableList.Builder<Arg> flagsBuilder = ImmutableList.builder();
    flagsBuilder.addAll(argFlags);

    ImmutableSortedSet.Builder<BuildRule> nativeCompileDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<BuildRule> bytecodeCompileDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<BuildRule> bytecodeLinkDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    for (OcamlLibrary library : ocamlInput) {
      nativeCompileDepsBuilder.addAll(library.getNativeCompileDeps());
      bytecodeCompileDepsBuilder.addAll(library.getBytecodeCompileDeps());
      bytecodeLinkDepsBuilder.addAll(library.getBytecodeLinkDeps());
    }
    OcamlBuildContext ocamlContext =
        OcamlBuildContext.builder(ocamlToolchain, ocamlBuckConfig)
            .setProjectFilesystem(projectFilesystem)
            .setSourcePathResolver(pathResolver)
            .setFlags(flagsBuilder.build())
            .setNativeIncludes(nativeIncludes)
            .setBytecodeIncludes(bytecodeIncludes)
            .setOcamlInput(ocamlInput)
            .setNativeLinkableInput(nativeLinkableInput)
            .setBytecodeLinkableInput(bytecodeLinkableInput)
            .setCLinkableInput(cLinkableInput)
            .setBuildTarget(compileBuildTarget.getUnflavoredBuildTarget())
            .setLibrary(isLibrary)
            .setCxxPreprocessorInput(cxxPreprocessorInputFromDeps)
            .setInput(getInput(srcs))
            .setNativeCompileDeps(nativeCompileDepsBuilder.build())
            .setBytecodeCompileDeps(bytecodeCompileDepsBuilder.build())
            .setBytecodeLinkDeps(bytecodeLinkDepsBuilder.build())
            .setCPreprocessor(ocamlToolchain.getCPreprocessor().resolve(resolver))
            .build();

    Path baseDir = projectFilesystem.getRootPath().toAbsolutePath();
    ImmutableMap<Path, ImmutableList<Path>> mlInput =
        getMLInputWithDeps(compileBuildTarget, baseDir, ocamlContext);

    ImmutableList<SourcePath> cInput = getCInput(pathResolver, getInput(srcs));

    OcamlBuildRulesGenerator generator =
        new OcamlBuildRulesGenerator(
            compileBuildTarget,
            projectFilesystem,
            compileParams,
            pathResolver,
            ruleFinder,
            resolver,
            ocamlContext,
            mlInput,
            cInput,
            ocamlToolchain.getCCompiler().resolve(resolver),
            ocamlToolchain.getCxxCompiler().resolve(resolver),
            bytecodeOnly,
            buildNativePlugin);

    OcamlGeneratedBuildRules result = generator.generate();

    if (isLibrary) {
      return new OcamlStaticLibrary(
          buildTarget,
          compileBuildTarget,
          projectFilesystem,
          params.withDeclaredDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(params.getDeclaredDeps().get())
                      .addAll(result.getRules())
                      .build())),
          linkerFlags,
          result.getObjectFiles(),
          ocamlContext,
          result.getRules().get(0),
          result.getNativeCompileDeps(),
          result.getBytecodeCompileDeps(),
          ImmutableSortedSet.<BuildRule>naturalOrder()
              .add(result.getBytecodeLink())
              .addAll(ruleFinder.filterBuildRuleInputs(result.getObjectFiles()))
              .build());
    } else {
      return new OcamlBinary(
          buildTarget,
          projectFilesystem,
          params.withDeclaredDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(params.getDeclaredDeps().get())
                      .addAll(result.getRules())
                      .build())),
          result.getRules().get(0));
    }
  }

  private static ImmutableList<SourcePath> getCInput(
      SourcePathResolver resolver, ImmutableList<SourcePath> input) {
    return input
        .stream()
        .filter(OcamlUtil.sourcePathExt(resolver, OcamlCompilables.OCAML_C))
        .collect(ImmutableList.toImmutableList());
  }

  private static ImmutableMap<Path, ImmutableList<Path>> getMLInputWithDeps(
      BuildTarget target, Path baseDir, OcamlBuildContext ocamlContext) {
    OcamlDepToolStep depToolStep =
        new OcamlDepToolStep(
            target,
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
          e, "Unable to execute ocamldep due to io error: %s", Joiner.on(" ").join(cmd));
    } catch (InterruptedException e) {
      throw new HumanReadableException(
          e,
          "Unable to calculate dependencies. ocamldep is interrupted: %s",
          Joiner.on(" ").join(cmd));
    }
    if (depsString.isPresent()) {
      OcamlDependencyGraphGenerator graphGenerator = new OcamlDependencyGraphGenerator();
      return filterCurrentRuleInput(
          ocamlContext.getSourcePathResolver().getAllAbsolutePaths(ocamlContext.getMLInput()),
          graphGenerator.generateDependencyMap(depsString.get()));
    } else {
      throw new HumanReadableException("ocamldep execution failed");
    }
  }

  private static ImmutableMap<Path, ImmutableList<Path>> filterCurrentRuleInput(
      final Set<Path> mlInput, ImmutableMap<Path, ImmutableList<Path>> deps) {
    ImmutableMap.Builder<Path, ImmutableList<Path>> builder = ImmutableMap.builder();
    for (ImmutableMap.Entry<Path, ImmutableList<Path>> entry : deps.entrySet()) {
      if (mlInput.contains(entry.getKey())) {
        builder.put(
            entry.getKey(),
            FluentIterable.from(entry.getValue()).filter(mlInput::contains).toList());
      }
    }
    return builder.build();
  }

  private static Optional<String> executeProcessAndGetStdout(
      Path baseDir, ImmutableList<String> cmd) throws IOException, InterruptedException {
    ImmutableSet.Builder<ProcessExecutor.Option> options = ImmutableSet.builder();
    options.add(ProcessExecutor.Option.EXPECTING_STD_OUT);
    ProcessExecutor exe = new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutorParams params =
        ProcessExecutorParams.builder().setCommand(cmd).setDirectory(baseDir).build();
    ProcessExecutor.Result result =
        exe.launchAndExecute(
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
