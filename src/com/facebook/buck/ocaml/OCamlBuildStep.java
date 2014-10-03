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

import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.shell.Shell;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MakeExecutableStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A step that preprocesses, compiles, and assembles OCaml sources.
 */
public class OCamlBuildStep implements Step {

  private static final Path OCAML_DEBUG = Paths.get("/usr/local/bin/ocamldebug");
  private final OCamlBuildContext ocamlContext;
  private final boolean hasGeneratedSources;
  private OCamlDepToolStep depToolStep;

  public OCamlBuildStep(OCamlBuildContext ocamlContext) {
    this.ocamlContext = Preconditions.checkNotNull(ocamlContext);

    hasGeneratedSources = ocamlContext.getLexInput().size() > 0 ||
        ocamlContext.getYaccInput().size() > 0;

    this.depToolStep = new OCamlDepToolStep(
        this.ocamlContext.getOcamlDepTool(),
        ocamlContext.getMLInput(),
        this.ocamlContext.getIncludeFlags(/* excludeDeps */ true)
    );
  }

  @Override
  public String getShortName() {
    return "OCaml compile";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return depToolStep.getDescription(context);
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    if (hasGeneratedSources) {
      int genExitCode = generateSources(context);
      if (genExitCode != 0) {
        return genExitCode;
      }
    }

    int depToolExitCode = depToolStep.execute(context);
    if (depToolExitCode != 0) {
      return depToolExitCode;
    }

    // OCaml requires module A to be present in command line to ocamlopt or ocamlc before
    // module B if B depends on A. In OCaml circular dependencies are prohibited, so all
    // dependency relations among modules form DAG. Topologically sorting this graph satisfies the
    // requirement.
    //
    // To get the DAG we launch ocamldep tool which provides the direct dependency information, like
    // module A depends on modules B, C, D.
    ImmutableList<String> sortedInput = sortDependency(depToolStep.getStdout());
    ImmutableList.Builder<String> linkerInputs = ImmutableList.builder();
    int mlCompileExitCode = executeMLCompilation(context, sortedInput, linkerInputs);
    if (mlCompileExitCode != 0) {
      return mlCompileExitCode;
    }

    ImmutableList.Builder<String> bytecodeLinkerInputs = ImmutableList.builder();
    int mlCompileBytecodeExitCode = executeMLCompileBytecode(
        context,
        sortedInput,
        bytecodeLinkerInputs);
    if (mlCompileBytecodeExitCode != 0) {
      return mlCompileBytecodeExitCode;
    }

    ImmutableList.Builder<String> cLinkerInputs = ImmutableList.builder();
    int cCompileExitCode = executeCCompilation(context, cLinkerInputs);
    if (cCompileExitCode != 0) {
      return cCompileExitCode;
    }

    ImmutableList<String> cObjects = cLinkerInputs.build();

    linkerInputs.addAll(cObjects);
    int nativeLinkExitCode = executeLinking(context, linkerInputs.build());
    if (nativeLinkExitCode != 0) {
      return nativeLinkExitCode;
    }

    bytecodeLinkerInputs.addAll(cObjects);
    int bytecodeExitCode =  executeBytecodeLinking(context, bytecodeLinkerInputs.build());
    if (bytecodeExitCode != 0) {
      return bytecodeExitCode;
    }

    if (!ocamlContext.isLibrary()) {
      return buildDebugLauncher(context);
    } else {
      return 0;
    }
  }

  private int executeCCompilation(
      ExecutionContext context,
      ImmutableList.Builder<String> linkerInputs) throws InterruptedException {

    ImmutableList.Builder<String> compileFlags = ImmutableList.builder();

    CxxPreprocessorInput cxxPreprocessorInput = ocamlContext.getCxxPreprocessorInput();

    for (Path includes : cxxPreprocessorInput.getIncludeRoots()) {
      compileFlags.add("-ccopt", "-I" + includes.toString());
    }

    // TODO(user): Refactor to use BuildRules
    for (Path cSrc : ocamlContext.getCInput()) {
      Path outputPath = ocamlContext.getCOutput(cSrc);
      linkerInputs.add(outputPath.toString());
      Step compileStep = new OCamlCCompileStep(
          ocamlContext.getCCompiler(),
          ocamlContext.getOcamlCompiler(),
          outputPath,
          cSrc,
          compileFlags.build());
      int compileExitCode = compileStep.execute(context);
      if (compileExitCode != 0) {
        return compileExitCode;
      }
    }
    return 0;
  }

  private int executeLinking(
      ExecutionContext context,
      ImmutableList<String> linkerInputs) throws InterruptedException {
    OCamlLinkStep linkStep = new OCamlLinkStep(
        ocamlContext.getCxxCompiler(),
        ocamlContext.getOcamlCompiler(),
        ocamlContext.getOutput(),
        ocamlContext.getLinkableInput().getArgs(),
        linkerInputs,
        ocamlContext.getFlags(),
        ocamlContext.isLibrary(),
        /* isBytecode */ false);
    return linkStep.execute(context);
  }

  private int executeBytecodeLinking(
      ExecutionContext context,
      ImmutableList<String> linkerInputs) throws InterruptedException {
    OCamlLinkStep linkStep = new OCamlLinkStep(
        ocamlContext.getCxxCompiler(),
        ocamlContext.getOcamlBytecodeCompiler(),
        ocamlContext.getBytecodeOutput(),
        ocamlContext.getLinkableInput().getArgs(),
        linkerInputs,
        ocamlContext.getFlags(),
        ocamlContext.isLibrary(),
        /* isBytecode */ true);
    return linkStep.execute(context);
  }

  // Debug launcher script is a script for launching OCaml debugger with target binary loaded.
  // Works with bytecode and provides limited debugging functionality like stepping, breakpoints,
  // etc.
  private int buildDebugLauncher(ExecutionContext context) throws InterruptedException {
    String debugCmdStr = getDebugCmd();
    String debugLuancherScript = getDebugLauncherScript(debugCmdStr);
    final String debugFilePathStr = ocamlContext.getBytecodeOutput().toString() + ".debug";

    WriteFileStep writeFile = new WriteFileStep(debugLuancherScript, debugFilePathStr);
    int writeExitCode = writeFile.execute(context);
    if (writeExitCode != 0) {
      return writeExitCode;
    }

    ShellStep chmod = new MakeExecutableStep(debugFilePathStr);
    return chmod.execute(context);
  }

  private String getDebugLauncherScript(String debugCmdStr) {
    ImmutableList.Builder<String> debugFile = ImmutableList.builder();
    debugFile.add("#!/bin/sh");
    debugFile.add(debugCmdStr);

    return Joiner.on("\n").join(debugFile.build());
  }

  private String getDebugCmd() {
    ImmutableList.Builder<String> debugCmd = ImmutableList.builder();
    debugCmd.add("rlwrap");
    debugCmd.add(OCAML_DEBUG.toString());

    Iterable<String> includesBytecodeFlags = FluentIterable.from(ocamlContext.getOCamlInput())
        .transformAndConcat(new Function<OCamlLibrary, Iterable<String>>() {
                              @Override
                              public Iterable<String> apply(OCamlLibrary input) {
                                return input.getBytecodeIncludeDirs();
                              }
                            });

    debugCmd.addAll(includesBytecodeFlags);
    debugCmd.addAll(ocamlContext.getBytecodeIncludeFlags());

    debugCmd.add(ocamlContext.getBytecodeOutput().toString());
    return Shell.shellQuoteJoin(debugCmd.build(), " ") + " $@";
  }

  private ImmutableList<String> getCompileFlags(boolean excludeDeps) {
    ImmutableList.Builder<String> flagBuilder = ImmutableList.builder();
    flagBuilder.addAll(ocamlContext.getIncludeFlags(/* excludeDeps */ excludeDeps));
    flagBuilder.addAll(ocamlContext.getFlags());
    flagBuilder.add(
        OCamlCompilables.OCAML_INCLUDE_FLAG,
        ocamlContext.getCompileOutputDir().toString());
    return flagBuilder.build();
  }

  private int executeMLCompilation(
      ExecutionContext context,
      ImmutableList<String> sortedInput,
      ImmutableList.Builder<String> linkerInputs
  ) throws InterruptedException {
    MakeCleanDirectoryStep mkDir = new MakeCleanDirectoryStep(ocamlContext.getCompileOutputDir());
    int mkDirExitCode = mkDir.execute(context);
    if (mkDirExitCode != 0) {
      return mkDirExitCode;
    }
    for (String inputOutput : sortedInput) {
      String inputFileName = Paths.get(inputOutput).getFileName().toString();
      String outputFileName = inputFileName
          .replaceFirst(OCamlCompilables.OCAML_ML_REGEX, OCamlCompilables.OCAML_CMX)
          .replaceFirst(OCamlCompilables.OCAML_MLI_REGEX, OCamlCompilables.OCAML_CMI);
      Path outputPath = ocamlContext.getCompileOutputDir().resolve(outputFileName);
      if (!outputFileName.endsWith(OCamlCompilables.OCAML_CMI)) {
        linkerInputs.add(outputPath.toString());
      }
      final ImmutableList<String> compileFlags = getCompileFlags(false);
      Step compileStep = new OCamlMLCompileStep(
          ocamlContext.getCCompiler(),
          ocamlContext.getOcamlCompiler(),
          outputPath,
          Paths.get(inputOutput),
          compileFlags);
      int compileExitCode = compileStep.execute(context);
      if (compileExitCode != 0) {
        return compileExitCode;
      }
    }
    return 0;
  }

  private int executeMLCompileBytecode(
      ExecutionContext context,
      ImmutableList<String> sortedInput,
      ImmutableList.Builder<String> linkerInputs) throws InterruptedException {
    MakeCleanDirectoryStep mkDir = new MakeCleanDirectoryStep(
        ocamlContext.getCompileBytecodeOutputDir());
    int mkDirExitCode = mkDir.execute(context);
    if (mkDirExitCode != 0) {
      return mkDirExitCode;
    }
    for (String inputOutput : sortedInput) {
      String inputFileName = Paths.get(inputOutput).getFileName().toString();
      String outputFileName = inputFileName
          .replaceFirst(OCamlCompilables.OCAML_ML_REGEX, OCamlCompilables.OCAML_CMO)
          .replaceFirst(OCamlCompilables.OCAML_MLI_REGEX, OCamlCompilables.OCAML_CMI);
      Path outputPath = ocamlContext.getCompileBytecodeOutputDir().resolve(outputFileName);
      if (!outputFileName.endsWith(OCamlCompilables.OCAML_CMI)) {
        linkerInputs.add(outputPath.toString());
      }
      final ImmutableList<String> compileFlags = getCompileFlags(/* excludeDeps */ false);
      Step compileBytecodeStep = new OCamlMLCompileStep(
          ocamlContext.getCCompiler(),
          ocamlContext.getOcamlBytecodeCompiler(),
          outputPath,
          Paths.get(inputOutput),
          compileFlags);
      int compileExitCode = compileBytecodeStep.execute(context);
      if (compileExitCode != 0) {
        return compileExitCode;
      }
    }
    return 0;
  }

  private int generateSources(ExecutionContext context) throws InterruptedException {
    MakeCleanDirectoryStep mkDir = new MakeCleanDirectoryStep(ocamlContext.getGeneratedSourceDir());
    int mkDirExitCode = mkDir.execute(context);
    if (mkDirExitCode != 0) {
      return mkDirExitCode;
    }
    for (Path yaccSource : ocamlContext.getYaccInput()) {
      Path output = ocamlContext.getYaccOutput(ImmutableSet.of(yaccSource)).get(0);
      OCamlYaccStep yaccStep = new OCamlYaccStep(
          ocamlContext.getYaccCompiler(),
          output,
          yaccSource);
      int yaccExitCode = yaccStep.execute(context);
      if (yaccExitCode != 0) {
        return yaccExitCode;
      }
    }
    for (Path lexSource : ocamlContext.getLexInput()) {
      Path output = ocamlContext.getLexOutput(ImmutableSet.of(lexSource)).get(0);
      OCamlLexStep lexStep = new OCamlLexStep(ocamlContext.getLexCompiler(), output, lexSource);
      int lexExitCode = lexStep.execute(context);
      if (lexExitCode != 0) {
        return lexExitCode;
      }
    }
    return 0;
  }

  private ImmutableList<String> sortDependency(String depOutput) {
    OCamlDependencyGraphGenerator graphGenerator = new OCamlDependencyGraphGenerator();
    return graphGenerator.generate(depOutput);
  }

}
