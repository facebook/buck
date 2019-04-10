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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.core.util.graph.TopologicalSort;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
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

  public static Function<BuildRule, ImmutableList<String>> getLibInclude(
      OcamlPlatform platform, boolean isBytecode) {
    return input -> {
      if (input instanceof OcamlLibrary) {
        OcamlLibrary library = (OcamlLibrary) input;
        if (isBytecode) {
          return ImmutableList.copyOf(library.getBytecodeIncludeDirs(platform));
        } else {
          return ImmutableList.of(library.getIncludeLibDir(platform).toString());
        }
      } else {
        return ImmutableList.of();
      }
    };
  }

  @VisibleForTesting
  protected static BuildTarget createStaticLibraryBuildTarget(BuildTarget target) {
    return target.withAppendedFlavors(OCAML_STATIC_FLAVOR);
  }

  @VisibleForTesting
  protected static BuildTarget createOcamlLinkTarget(BuildTarget target) {
    return target.withAppendedFlavors(OCAML_LINK_BINARY_FLAVOR);
  }

  static boolean shouldUseFineGrainedRules(
      BuildRuleResolver resolver, ImmutableList<SourcePath> srcs) {
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    boolean noYaccOrLexSources =
        srcs.stream()
            .noneMatch(
                OcamlUtil.sourcePathExt(
                    pathResolver, OcamlCompilables.OCAML_MLL, OcamlCompilables.OCAML_MLY));
    boolean noGeneratedSources =
        FluentIterable.from(srcs).filter(BuildTargetSourcePath.class).isEmpty();
    return noYaccOrLexSources && noGeneratedSources;
  }

  private static ImmutableList<OcamlLibrary> getTransitiveOcamlLibraryDeps(
      OcamlPlatform platform, Iterable<? extends BuildRule> deps) {
    MutableDirectedGraph<OcamlLibrary> graph = new MutableDirectedGraph<>();

    new AbstractBreadthFirstTraversal<OcamlLibrary>(
        RichStream.from(deps).filter(OcamlLibrary.class).toImmutableList()) {
      @Override
      public Iterable<OcamlLibrary> visit(OcamlLibrary node) {
        graph.addNode(node);
        Iterable<OcamlLibrary> deps =
            RichStream.from(node.getOcamlLibraryDeps(platform))
                .filter(OcamlLibrary.class)
                .toImmutableList();
        for (OcamlLibrary dep : deps) {
          graph.addEdge(node, dep);
        }
        return deps;
      }
    }.start();

    return TopologicalSort.sort(new DirectedAcyclicGraph<>(graph));
  }

  private static NativeLinkableInput getNativeLinkableInput(
      OcamlPlatform platform, Iterable<BuildRule> deps) {
    List<NativeLinkableInput> inputs = new ArrayList<>();

    // Add in the linkable input from OCaml libraries.
    ImmutableList<OcamlLibrary> ocamlDeps = getTransitiveOcamlLibraryDeps(platform, deps);
    for (OcamlLibrary dep : ocamlDeps) {
      inputs.add(dep.getNativeLinkableInput(platform));
    }

    return NativeLinkableInput.concat(inputs);
  }

  private static NativeLinkableInput getBytecodeLinkableInput(
      OcamlPlatform platform, Iterable<BuildRule> deps) {
    List<NativeLinkableInput> inputs = new ArrayList<>();

    // Add in the linkable input from OCaml libraries.
    ImmutableList<OcamlLibrary> ocamlDeps = getTransitiveOcamlLibraryDeps(platform, deps);
    for (OcamlLibrary dep : ocamlDeps) {
      inputs.add(dep.getBytecodeLinkableInput(platform));
    }

    return NativeLinkableInput.concat(inputs);
  }

  private static NativeLinkableInput getCLinkableInput(
      OcamlPlatform platform,
      ActionGraphBuilder graphBuilder,
      TargetConfiguration targetConfiguration,
      Iterable<BuildRule> deps) {
    return NativeLinkables.getTransitiveNativeLinkableInput(
        platform.getCxxPlatform(),
        graphBuilder,
        targetConfiguration,
        deps,
        Linker.LinkableDepType.STATIC,
        r ->
            r instanceof OcamlLibrary
                ? Optional.of(((OcamlLibrary) r).getOcamlLibraryDeps(platform))
                : Optional.empty());
  }

  static OcamlBuild createBulkCompileRule(
      BuildTarget buildTarget,
      OcamlPlatform ocamlPlatform,
      BuildTarget compileBuildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      Iterable<BuildRule> deps,
      ImmutableList<SourcePath> srcs,
      boolean isLibrary,
      boolean bytecodeOnly,
      ImmutableList<Arg> argFlags,
      ImmutableList<String> ocamlDepFlags) {
    CxxPreprocessorInput cxxPreprocessorInputFromDeps =
        CxxPreprocessorInput.concat(
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                ocamlPlatform.getCxxPlatform(),
                graphBuilder,
                FluentIterable.from(deps).filter(CxxPreprocessorDep.class::isInstance)));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    ImmutableList<String> nativeIncludes =
        FluentIterable.from(deps)
            .transformAndConcat(getLibInclude(ocamlPlatform, false)::apply)
            .toList();

    ImmutableList<String> bytecodeIncludes =
        FluentIterable.from(deps)
            .transformAndConcat(getLibInclude(ocamlPlatform, true)::apply)
            .toList();

    NativeLinkableInput nativeLinkableInput = getNativeLinkableInput(ocamlPlatform, deps);
    NativeLinkableInput bytecodeLinkableInput = getBytecodeLinkableInput(ocamlPlatform, deps);
    NativeLinkableInput cLinkableInput =
        getCLinkableInput(ocamlPlatform, graphBuilder, buildTarget.getTargetConfiguration(), deps);

    ImmutableList<OcamlLibrary> ocamlInput = getTransitiveOcamlLibraryDeps(ocamlPlatform, deps);

    ImmutableSortedSet.Builder<BuildRule> allDepsBuilder = ImmutableSortedSet.naturalOrder();
    allDepsBuilder.addAll(ruleFinder.filterBuildRuleInputs(srcs));
    allDepsBuilder.addAll(
        Stream.of(nativeLinkableInput, bytecodeLinkableInput, cLinkableInput)
            .flatMap(input -> input.getArgs().stream())
            .flatMap(arg -> BuildableSupport.getDeps(arg, ruleFinder))
            .iterator());
    for (OcamlLibrary library : ocamlInput) {
      allDepsBuilder.addAll(library.getNativeCompileDeps(ocamlPlatform));
      allDepsBuilder.addAll(library.getBytecodeCompileDeps(ocamlPlatform));
      allDepsBuilder.addAll(library.getBytecodeLinkDeps(ocamlPlatform));
    }
    allDepsBuilder.addAll(
        BuildableSupport.getDepsCollection(
            ocamlPlatform
                .getCCompiler()
                .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
            ruleFinder));
    allDepsBuilder.addAll(
        BuildableSupport.getDepsCollection(
            ocamlPlatform
                .getCxxCompiler()
                .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
            ruleFinder));
    allDepsBuilder.addAll(
        argFlags.stream().flatMap(arg -> BuildableSupport.getDeps(arg, ruleFinder)).iterator());

    // The bulk rule will do preprocessing on sources, and so needs deps from the preprocessor
    // input object.
    allDepsBuilder.addAll(cxxPreprocessorInputFromDeps.getDeps(graphBuilder, ruleFinder));

    ImmutableSortedSet<BuildRule> allDeps = allDepsBuilder.build();

    ImmutableList.Builder<Arg> flagsBuilder = ImmutableList.builder();
    flagsBuilder.addAll(argFlags);

    ImmutableSortedSet.Builder<BuildRule> nativeCompileDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<BuildRule> bytecodeCompileDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<BuildRule> bytecodeLinkDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<String> transitiveBytecodeIncludesBuilder =
        ImmutableSortedSet.naturalOrder();
    for (OcamlLibrary library : ocamlInput) {
      nativeCompileDepsBuilder.addAll(library.getNativeCompileDeps(ocamlPlatform));
      bytecodeCompileDepsBuilder.addAll(library.getBytecodeCompileDeps(ocamlPlatform));
      bytecodeLinkDepsBuilder.addAll(library.getBytecodeLinkDeps(ocamlPlatform));
      transitiveBytecodeIncludesBuilder.addAll(library.getBytecodeIncludeDirs(ocamlPlatform));
    }
    OcamlBuildContext ocamlContext =
        OcamlBuildContext.builder(ocamlPlatform, graphBuilder, buildTarget.getTargetConfiguration())
            .setProjectFilesystem(projectFilesystem)
            .setSourcePathResolver(pathResolver)
            .setFlags(flagsBuilder.build())
            .setOcamlDepFlags(ocamlDepFlags)
            .setNativeIncludes(nativeIncludes)
            .setBytecodeIncludes(bytecodeIncludes)
            .setTransitiveBytecodeIncludes(transitiveBytecodeIncludesBuilder.build())
            .setNativeLinkableInput(nativeLinkableInput)
            .setBytecodeLinkableInput(bytecodeLinkableInput)
            .setCLinkableInput(cLinkableInput)
            .setBuildTarget(buildTarget)
            .setLibrary(isLibrary)
            .setCxxPreprocessorInput(cxxPreprocessorInputFromDeps)
            .setInput(srcs)
            .setNativeCompileDeps(nativeCompileDepsBuilder.build())
            .setBytecodeCompileDeps(bytecodeCompileDepsBuilder.build())
            .setBytecodeLinkDeps(bytecodeLinkDepsBuilder.build())
            .setCPreprocessor(
                ocamlPlatform
                    .getCPreprocessor()
                    .resolve(graphBuilder, buildTarget.getTargetConfiguration()))
            .build();

    return graphBuilder.addToIndex(
        new OcamlBuild(
            compileBuildTarget,
            projectFilesystem,
            params.withDeclaredDeps(allDeps).withoutExtraDeps(),
            ocamlContext,
            ocamlPlatform
                .getCCompiler()
                .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
            ocamlPlatform
                .getCxxCompiler()
                .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
            bytecodeOnly));
  }

  static OcamlGeneratedBuildRules createFineGrainedBuildRules(
      BuildTarget buildTarget,
      OcamlPlatform ocamlPlatform,
      BuildTarget compileBuildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      Iterable<BuildRule> deps,
      ImmutableList<SourcePath> srcs,
      boolean isLibrary,
      boolean bytecodeOnly,
      ImmutableList<Arg> argFlags,
      ImmutableList<String> ocamlDepFlags,
      boolean buildNativePlugin) {

    CxxPreprocessorInput cxxPreprocessorInputFromDeps =
        CxxPreprocessorInput.concat(
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                ocamlPlatform.getCxxPlatform(),
                graphBuilder,
                FluentIterable.from(deps).filter(CxxPreprocessorDep.class::isInstance)));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    ImmutableList<String> nativeIncludes =
        FluentIterable.from(deps)
            .transformAndConcat(getLibInclude(ocamlPlatform, false)::apply)
            .toList();

    ImmutableList<String> bytecodeIncludes =
        FluentIterable.from(deps)
            .transformAndConcat(getLibInclude(ocamlPlatform, true)::apply)
            .toList();

    NativeLinkableInput nativeLinkableInput = getNativeLinkableInput(ocamlPlatform, deps);
    NativeLinkableInput bytecodeLinkableInput = getBytecodeLinkableInput(ocamlPlatform, deps);
    NativeLinkableInput cLinkableInput =
        getCLinkableInput(ocamlPlatform, graphBuilder, buildTarget.getTargetConfiguration(), deps);

    ImmutableList<OcamlLibrary> ocamlInput = getTransitiveOcamlLibraryDeps(ocamlPlatform, deps);

    BuildRuleParams compileParams =
        params
            .withDeclaredDeps(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(ruleFinder.filterBuildRuleInputs(srcs))
                    .addAll(
                        Stream.of(nativeLinkableInput, bytecodeLinkableInput, cLinkableInput)
                            .flatMap(input -> input.getArgs().stream())
                            .flatMap(arg -> BuildableSupport.getDeps(arg, ruleFinder))
                            .iterator())
                    .addAll(
                        argFlags.stream()
                            .flatMap(arg -> BuildableSupport.getDeps(arg, ruleFinder))
                            .iterator())
                    .addAll(
                        BuildableSupport.getDepsCollection(
                            ocamlPlatform
                                .getCCompiler()
                                .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
                            ruleFinder))
                    .addAll(
                        BuildableSupport.getDepsCollection(
                            ocamlPlatform
                                .getCxxCompiler()
                                .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
                            ruleFinder))
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
    ImmutableSortedSet.Builder<String> transitiveBytecodeIncludesBuilder =
        ImmutableSortedSet.naturalOrder();
    for (OcamlLibrary library : ocamlInput) {
      nativeCompileDepsBuilder.addAll(library.getNativeCompileDeps(ocamlPlatform));
      bytecodeCompileDepsBuilder.addAll(library.getBytecodeCompileDeps(ocamlPlatform));
      bytecodeLinkDepsBuilder.addAll(library.getBytecodeLinkDeps(ocamlPlatform));
      transitiveBytecodeIncludesBuilder.addAll(library.getBytecodeIncludeDirs(ocamlPlatform));
    }
    OcamlBuildContext ocamlContext =
        OcamlBuildContext.builder(ocamlPlatform, graphBuilder, buildTarget.getTargetConfiguration())
            .setProjectFilesystem(projectFilesystem)
            .setSourcePathResolver(pathResolver)
            .setFlags(flagsBuilder.build())
            .setOcamlDepFlags(ocamlDepFlags)
            .setNativeIncludes(nativeIncludes)
            .setBytecodeIncludes(bytecodeIncludes)
            .setTransitiveBytecodeIncludes(transitiveBytecodeIncludesBuilder.build())
            .setNativeLinkableInput(nativeLinkableInput)
            .setBytecodeLinkableInput(bytecodeLinkableInput)
            .setCLinkableInput(cLinkableInput)
            .setBuildTarget(buildTarget)
            .setLibrary(isLibrary)
            .setCxxPreprocessorInput(cxxPreprocessorInputFromDeps)
            .setInput(srcs)
            .setNativeCompileDeps(nativeCompileDepsBuilder.build())
            .setBytecodeCompileDeps(bytecodeCompileDepsBuilder.build())
            .setBytecodeLinkDeps(bytecodeLinkDepsBuilder.build())
            .setCPreprocessor(
                ocamlPlatform
                    .getCPreprocessor()
                    .resolve(graphBuilder, buildTarget.getTargetConfiguration()))
            .build();

    Path baseDir = projectFilesystem.getRootPath().toAbsolutePath();
    ImmutableMap<Path, ImmutableList<Path>> mlInput = getMLInputWithDeps(baseDir, ocamlContext);

    ImmutableList<SourcePath> cInput = getCInput(pathResolver, srcs);

    OcamlBuildRulesGenerator generator =
        new OcamlBuildRulesGenerator(
            compileBuildTarget,
            projectFilesystem,
            compileParams,
            pathResolver,
            ruleFinder,
            graphBuilder,
            ocamlContext,
            mlInput,
            cInput,
            ocamlPlatform
                .getCCompiler()
                .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
            ocamlPlatform
                .getCxxCompiler()
                .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
            bytecodeOnly,
            buildNativePlugin);

    return generator.generate();
  }

  private static ImmutableList<SourcePath> getCInput(
      SourcePathResolver resolver, ImmutableList<SourcePath> input) {
    return input.stream()
        .filter(OcamlUtil.sourcePathExt(resolver, OcamlCompilables.OCAML_C))
        .collect(ImmutableList.toImmutableList());
  }

  private static ImmutableMap<Path, ImmutableList<Path>> getMLInputWithDeps(
      Path baseDir, OcamlBuildContext ocamlContext) {

    ImmutableList<String> ocamlDepFlags =
        ImmutableList.<String>builder()
            .addAll(ocamlContext.getIncludeFlags(/* isBytecode */ false, /* excludeDeps */ true))
            .addAll(ocamlContext.getOcamlDepFlags())
            .build();

    OcamlDepToolStep depToolStep =
        new OcamlDepToolStep(
            baseDir,
            ocamlContext.getSourcePathResolver(),
            ocamlContext.getOcamlDepTool().get(),
            ocamlContext.getMLInput(),
            ocamlDepFlags);
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
      Set<Path> mlInput, ImmutableMap<Path, ImmutableList<Path>> deps) {
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

  static ImmutableList<Arg> getFlags(
      BuildTarget target,
      CellPathResolver cellRoots,
      ActionGraphBuilder graphBuilder,
      OcamlPlatform platform,
      ImmutableList<StringWithMacros> compilerFlags,
      Optional<String> warningsFlags) {
    ImmutableList.Builder<Arg> flags = ImmutableList.builder();
    flags.addAll(
        OcamlDescriptionEnhancer.toStringWithMacrosArgs(
            target, cellRoots, graphBuilder, compilerFlags));
    if (platform.getWarningsFlags().isPresent() || warningsFlags.isPresent()) {
      flags.addAll(
          StringArg.from("-w", platform.getWarningsFlags().orElse("") + warningsFlags.orElse("")));
    }
    return flags.build();
  }
}
