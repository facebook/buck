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
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.OCamlSource;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Compute transitive dependencies and generate ocaml build rules
 */
public class OCamlRuleBuilder {

  public static final BuildRuleType TYPE = BuildRuleType.of("ocaml_library");
  private static final Flavor OCAML_STATIC_FLAVOR = ImmutableFlavor.of("static");
  private static final Flavor OCAML_LINK_BINARY_FLAVOR = ImmutableFlavor.of("binary");

  private OCamlRuleBuilder() {
  }

  public static final Function<BuildRule, ImmutableList<String>> getLibInclude(
      final boolean isBytecode) {
    return
      new Function<BuildRule, ImmutableList<String>>() {
        @Override
        public ImmutableList<String> apply(BuildRule input) {
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
        }
      };
  }

  public static ImmutableList<SourcePath> getInput(Iterable<OCamlSource> source) {
    return ImmutableList.copyOf(
        FluentIterable.from(source)
            .transform(
                new Function<OCamlSource, SourcePath>() {
                  @Override
                  public SourcePath apply(OCamlSource input) {
                    return input.getSource();
                  }
                })
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
      ImmutableList<String> argFlags,
      final ImmutableList<String> linkerFlags) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    boolean noYaccOrLexSources = FluentIterable.from(srcs).transform(OCamlSource.TO_SOURCE_PATH)
        .filter(OCamlUtil.sourcePathExt(
                  pathResolver,
                  OCamlCompilables.OCAML_MLL,
                  OCamlCompilables.OCAML_MLY))
        .isEmpty();
    if (noYaccOrLexSources) {
      return createFineGrainedBuildRule(
          ocamlBuckConfig,
          params,
          resolver,
          srcs,
          isLibrary,
          argFlags,
          linkerFlags);
    } else {
      return createBulkBuildRule(
          ocamlBuckConfig,
          params,
          resolver,
          srcs,
          isLibrary,
          argFlags,
          linkerFlags);
    }
  }

  public static AbstractBuildRule createBulkBuildRule(
      OCamlBuckConfig ocamlBuckConfig,
      final BuildRuleParams params,
      BuildRuleResolver resolver,
      ImmutableList<OCamlSource> srcs,
      boolean isLibrary,
      ImmutableList<String> argFlags,
      final ImmutableList<String> linkerFlags) {
    CxxPreprocessorInput cxxPreprocessorInputFromDeps;
    try {
      cxxPreprocessorInputFromDeps =
          CxxPreprocessables.getTransitiveCxxPreprocessorInput(
              ocamlBuckConfig.getCxxPlatform(),
              FluentIterable.from(params.getDeps())
                  .filter(Predicates.instanceOf(CxxPreprocessorDep.class)));
    } catch (CxxPreprocessorInput.ConflictingHeadersException e) {
      throw e.getHumanReadableExceptionForBuildTarget(params.getBuildTarget());
    }

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    ImmutableList<String> includes = FluentIterable.from(params.getDeps())
        .transformAndConcat(getLibInclude(false))
        .toList();

    ImmutableList<String> bytecodeIncludes = FluentIterable.from(params.getDeps())
        .transformAndConcat(getLibInclude(true))
        .toList();

    final FluentIterable<SourcePath> srcSourcePaths = FluentIterable.from(srcs).transform(
        OCamlSource.TO_SOURCE_PATH);
    final FluentIterable<Path> srcPaths =
        srcSourcePaths.transform(pathResolver.getPathFunction());

    NativeLinkableInput linkableInput = NativeLinkables.getTransitiveNativeLinkableInput(
        ocamlBuckConfig.getCxxPlatform(),
        params.getDeps(),
        Linker.LinkableDepType.STATIC,
        /* reverse */ false);

    ImmutableList<OCamlLibrary> ocamlInput = OCamlUtil.getTransitiveOCamlInput(params.getDeps());

    ImmutableList<SourcePath> allInputs =
        ImmutableList.<SourcePath>builder()
            .addAll(getInput(srcs))
            .addAll(linkableInput.getInputs())
            .build();

    BuildTarget buildTarget =
        isLibrary ? createStaticLibraryBuildTarget(params.getBuildTarget())
            : createOCamlLinkTarget(params.getBuildTarget());
    final BuildRuleParams compileParams = params.copyWithChanges(
        NativeLinkable.NATIVE_LINKABLE_TYPE,
        buildTarget,
        /* declaredDeps */ Suppliers.ofInstance(
            ImmutableSortedSet.copyOf(pathResolver.filterBuildRuleInputs(allInputs))),
        /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));

    ImmutableList.Builder<String> flagsBuilder = ImmutableList.builder();
    flagsBuilder.addAll(argFlags);

    final OCamlBuildContext ocamlContext = OCamlBuildContext.builder(ocamlBuckConfig, pathResolver)
        .setFlags(flagsBuilder.build())
        .setIncludes(includes)
        .setBytecodeIncludes(bytecodeIncludes)
        .setOcamlInput(ocamlInput)
        .setLinkableInput(linkableInput)
        .setUpDirectories(buildTarget, isLibrary)
        .setCxxPreprocessorInput(cxxPreprocessorInputFromDeps)
        .setInput(getInput(srcs))
        .build();

    final OCamlBuild ocamlLibraryBuild = new OCamlBuild(
        compileParams,
        pathResolver,
        ocamlContext,
        ocamlBuckConfig.getCCompiler(),
        ocamlBuckConfig.getCxxCompiler());

    resolver.addToIndex(ocamlLibraryBuild);

    if (isLibrary) {
      return new OCamlStaticLibrary(
          params.copyWithDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(params.getDeclaredDeps())
                      .add(ocamlLibraryBuild)
                      .build()),
              Suppliers.ofInstance(params.getExtraDeps())),
          pathResolver,
          compileParams,
          linkerFlags,
          srcPaths,
          ocamlContext,
          ocamlLibraryBuild);
    } else {
      return new OCamlBinary(
          params.copyWithDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(params.getDeclaredDeps())
                      .add(ocamlLibraryBuild)
                      .build()),
              Suppliers.ofInstance(params.getExtraDeps())),
          pathResolver,
          ocamlLibraryBuild.getPathToOutputFile());
    }
  }

  public static AbstractBuildRule createFineGrainedBuildRule(
      OCamlBuckConfig ocamlBuckConfig,
      final BuildRuleParams params,
      BuildRuleResolver resolver,
      ImmutableList<OCamlSource> srcs,
      boolean isLibrary,
      ImmutableList<String> argFlags,
      final ImmutableList<String> linkerFlags) {
    CxxPreprocessorInput cxxPreprocessorInputFromDeps;
    try {
      cxxPreprocessorInputFromDeps =
          CxxPreprocessables.getTransitiveCxxPreprocessorInput(
              ocamlBuckConfig.getCxxPlatform(),
              FluentIterable.from(params.getDeps())
                  .filter(Predicates.instanceOf(CxxPreprocessorDep.class)));
    } catch (CxxPreprocessorInput.ConflictingHeadersException e) {
      throw e.getHumanReadableExceptionForBuildTarget(params.getBuildTarget());
    }

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    ImmutableList<String> includes = FluentIterable.from(params.getDeps())
        .transformAndConcat(getLibInclude(false))
        .toList();

    ImmutableList<String> bytecodeIncludes = FluentIterable.from(params.getDeps())
        .transformAndConcat(getLibInclude(true))
        .toList();

    final FluentIterable<SourcePath> srcSourcePaths = FluentIterable.from(srcs).transform(
        OCamlSource.TO_SOURCE_PATH);
    final FluentIterable<Path> srcPaths = srcSourcePaths.transform(pathResolver.getPathFunction());

    NativeLinkableInput linkableInput = NativeLinkables.getTransitiveNativeLinkableInput(
        ocamlBuckConfig.getCxxPlatform(),
        params.getDeps(),
        Linker.LinkableDepType.STATIC,
        /* reverse */ false);

    ImmutableList<OCamlLibrary> ocamlInput = OCamlUtil.getTransitiveOCamlInput(params.getDeps());

    ImmutableList<SourcePath> allInputs =
        ImmutableList.<SourcePath>builder()
            .addAll(getInput(srcs))
            .addAll(linkableInput.getInputs())
            .build();

    BuildTarget buildTarget =
        isLibrary ? createStaticLibraryBuildTarget(params.getBuildTarget())
            : createOCamlLinkTarget(params.getBuildTarget());

    final BuildRuleParams compileParams = params.copyWithChanges(
        NativeLinkable.NATIVE_LINKABLE_TYPE,
        buildTarget,
        /* declaredDeps */ Suppliers.ofInstance(
            ImmutableSortedSet.copyOf(pathResolver.filterBuildRuleInputs(allInputs))),
        /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));

    ImmutableList.Builder<String> flagsBuilder = ImmutableList.builder();
    flagsBuilder.addAll(argFlags);

    final OCamlBuildContext ocamlContext = OCamlBuildContext.builder(ocamlBuckConfig, pathResolver)
        .setFlags(flagsBuilder.build())
        .setIncludes(includes)
        .setBytecodeIncludes(bytecodeIncludes)
        .setOcamlInput(ocamlInput)
        .setLinkableInput(linkableInput)
        .setUpDirectories(buildTarget, isLibrary)
        .setCxxPreprocessorInput(cxxPreprocessorInputFromDeps)
        .setInput(getInput(srcs))
        .build();

    File baseDir = params.getProjectFilesystem().getRootPath().toAbsolutePath().toFile();
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
        ocamlBuckConfig.getCCompiler(),
        ocamlBuckConfig.getCxxCompiler());

    ImmutableList<BuildRule> ocamlLibraryBuild = generator.generate();

    if (isLibrary) {
      return new OCamlStaticLibrary(
          params.copyWithDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(params.getDeclaredDeps())
                      .addAll(ocamlLibraryBuild)
                      .build()),
              Suppliers.ofInstance(params.getExtraDeps())),
          pathResolver,
          compileParams,
          linkerFlags,
          srcPaths,
          ocamlContext,
          ocamlLibraryBuild.get(0));
    } else {
      return new OCamlBinary(
          params.copyWithDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(params.getDeclaredDeps())
                      .addAll(ocamlLibraryBuild)
                      .build()),
              Suppliers.ofInstance(params.getExtraDeps())),
          pathResolver,
          Preconditions.checkNotNull(ocamlLibraryBuild.get(0).getPathToOutputFile()));
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
      File baseDir,
      OCamlBuildContext ocamlContext) {
    OCamlDepToolStep depToolStep = new OCamlDepToolStep(
        ocamlContext.getOcamlDepTool(),
        ocamlContext.getMLInput(),
        ocamlContext.getIncludeFlags(/* isBytecode */ false, /* excludeDeps */ true));
    ImmutableList<String> cmd = depToolStep.getShellCommand(null);
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
          ocamlContext.getMLInput(),
          graphGenerator.generateDependencyMap(depsString.get()));
    } else {
      throw new HumanReadableException("ocamldep execution failed");
    }
  }

  private static ImmutableMap<Path, ImmutableList<Path>> filterCurrentRuleInput(
      final ImmutableList<Path> mlInput,
      ImmutableMap<Path, ImmutableList<Path>> deps) {
    ImmutableMap.Builder<Path, ImmutableList<Path>> builder = ImmutableMap.builder();
    for (ImmutableMap.Entry<Path, ImmutableList<Path>> entry : deps.entrySet()) {
      if (mlInput.contains(entry.getKey())) {
        builder.put(entry.getKey(),
            FluentIterable.from(entry.getValue())
              .filter(new Predicate<Path>() {
                        @Override
                        public boolean apply(Path input) {
                          return mlInput.contains(input);
                        }
                      }).toList()
            );
      }
    }
    return builder.build();
  }

  private static Optional<String> executeProcessAndGetStdout(
      File baseDir,
      ImmutableList<String> cmd) throws IOException, InterruptedException {
    PrintStream stdout = new CapturingPrintStream();
    PrintStream stderr = new CapturingPrintStream();

    ImmutableSet.Builder<ProcessExecutor.Option> options = ImmutableSet.builder();
    options.add(ProcessExecutor.Option.EXPECTING_STD_ERR);
    options.add(ProcessExecutor.Option.EXPECTING_STD_OUT);
    Console console = new Console(Verbosity.SILENT, stdout, stderr, Ansi.withoutTty());
    ProcessExecutor exe = new ProcessExecutor(console);
    ProcessBuilder processBuilder = new ProcessBuilder(cmd);
    processBuilder.directory(baseDir);
    ProcessExecutor.Result result = exe.execute(
        processBuilder.start(),
        options.build(),
        /* stdin */ Optional.<String>absent(),
        /* timeOutMs */ Optional.<Long>absent());
    if (result.getExitCode() != 0) {
      return Optional.absent();
    } else {
      return result.getStdout();
    }
  }
}
