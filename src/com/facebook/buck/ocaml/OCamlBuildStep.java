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
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.nio.file.Paths;

import java.io.IOException;

/**
 * A step that preprocesses, compiles, and assembles OCaml sources.
 */
public class OCamlBuildStep implements Step {

  private final OCamlBuildContext ocamlContext;
  private final ImmutableList<String> cCompiler;
  private final ImmutableList<String> cxxCompiler;

  private final boolean hasGeneratedSources;
  private final OCamlDepToolStep depToolStep;

  public OCamlBuildStep(
      OCamlBuildContext ocamlContext,
      ImmutableList<String> cCompiler,
      ImmutableList<String> cxxCompiler) {
    this.ocamlContext = ocamlContext;
    this.cCompiler = cCompiler;
    this.cxxCompiler = cxxCompiler;

    hasGeneratedSources = ocamlContext.getLexInput().size() > 0 ||
        ocamlContext.getYaccInput().size() > 0;

    this.depToolStep = new OCamlDepToolStep(
        this.ocamlContext.getOcamlDepTool(),
        ocamlContext.getMLInput(),
        this.ocamlContext.getIncludeFlags(/* isBytecode */ false, /* excludeDeps */ true)
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
  public int execute(ExecutionContext context) throws IOException, InterruptedException {
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
      Step debugLauncher = new OCamlDebugLauncherStep(
          new OCamlDebugLauncherStep.Args(
              ocamlContext.getOcamlDebug(),
              ocamlContext.getBytecodeOutput(),
              ocamlContext.getOCamlInput(),
              ocamlContext.getBytecodeIncludeFlags()
          )
      );
      return debugLauncher.execute(context);
    } else {
      return 0;
    }
  }

  private int executeCCompilation(
      ExecutionContext context,
      ImmutableList.Builder<String> linkerInputs) throws IOException, InterruptedException {

    ImmutableList.Builder<String> cCompileFlags = ImmutableList.builder();
    cCompileFlags.addAll(ocamlContext.getCCompileFlags());
    cCompileFlags.addAll(ocamlContext.getCommonCFlags());

    CxxPreprocessorInput cxxPreprocessorInput = ocamlContext.getCxxPreprocessorInput();

    for (Path cSrc : ocamlContext.getCInput()) {
      Path outputPath = ocamlContext.getCOutput(cSrc);
      linkerInputs.add(outputPath.toString());
      Step compileStep = new OCamlCCompileStep(
          new OCamlCCompileStep.Args(
            cCompiler,
            ocamlContext.getOcamlCompiler(),
            outputPath,
            cSrc,
            cCompileFlags.build(),
              ImmutableMap.copyOf(cxxPreprocessorInput.getIncludes().getNameToPathMap())));
      int compileExitCode = compileStep.execute(context);
      if (compileExitCode != 0) {
        return compileExitCode;
      }
    }
    return 0;
  }

  private int executeLinking(
      ExecutionContext context,
      ImmutableList<String> linkerInputs) throws IOException, InterruptedException {

    ImmutableList.Builder<String> flags = ImmutableList.builder();
    flags.addAll(ocamlContext.getFlags());
    flags.addAll(ocamlContext.getCommonCLinkerFlags());

    OCamlLinkStep linkStep = new OCamlLinkStep(
        new OCamlLinkStep.Args(
          cxxCompiler,
          ocamlContext.getOcamlCompiler(),
          ocamlContext.getOutput(),
          ImmutableList.copyOf(ocamlContext.getLinkableInput().getArgs()),
          linkerInputs,
          flags.build(),
          ocamlContext.isLibrary(),
          /* isBytecode */ false));
    return linkStep.execute(context);
  }

  private int executeBytecodeLinking(
      ExecutionContext context,
      ImmutableList<String> linkerInputs) throws IOException, InterruptedException {

    ImmutableList.Builder<String> flags = ImmutableList.builder();
    flags.addAll(ocamlContext.getFlags());
    flags.addAll(ocamlContext.getCommonCLinkerFlags());

    OCamlLinkStep linkStep = new OCamlLinkStep(
        new OCamlLinkStep.Args(
          cxxCompiler,
          ocamlContext.getOcamlBytecodeCompiler(),
          ocamlContext.getBytecodeOutput(),
          ImmutableList.copyOf(ocamlContext.getLinkableInput().getArgs()),
          linkerInputs,
          flags.build(),
          ocamlContext.isLibrary(),
          /* isBytecode */ true));
    return linkStep.execute(context);
  }

  private ImmutableList<String> getCompileFlags(boolean isBytecode, boolean excludeDeps) {
    String output = isBytecode ? ocamlContext.getCompileBytecodeOutputDir().toString() :
        ocamlContext.getCompileOutputDir().toString();
    ImmutableList.Builder<String> flagBuilder = ImmutableList.builder();
    flagBuilder.addAll(ocamlContext.getIncludeFlags(isBytecode, /* excludeDeps */ excludeDeps));
    flagBuilder.addAll(ocamlContext.getFlags());
    flagBuilder.add(
        OCamlCompilables.OCAML_INCLUDE_FLAG,
        output);
    return flagBuilder.build();
  }

  private int executeMLCompilation(
      ExecutionContext context,
      ImmutableList<String> sortedInput,
      ImmutableList.Builder<String> linkerInputs
  ) throws IOException, InterruptedException {
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
      final ImmutableList<String> compileFlags = getCompileFlags(
          /* isBytecode */ false,
          /* excludeDeps */ false);
      Step compileStep = new OCamlMLCompileStep(
          new OCamlMLCompileStep.Args(
            cCompiler,
            ocamlContext.getOcamlCompiler(),
            outputPath,
            Paths.get(inputOutput),
            compileFlags));
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
      ImmutableList.Builder<String> linkerInputs) throws IOException, InterruptedException {
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
      final ImmutableList<String> compileFlags = getCompileFlags(
          /* isBytecode */ true,
          /* excludeDeps */ false);
      Step compileBytecodeStep = new OCamlMLCompileStep(
          new OCamlMLCompileStep.Args(
            cCompiler,
            ocamlContext.getOcamlBytecodeCompiler(),
            outputPath,
            Paths.get(inputOutput),
            compileFlags));
      int compileExitCode = compileBytecodeStep.execute(context);
      if (compileExitCode != 0) {
        return compileExitCode;
      }
    }
    return 0;
  }

private int generateSources(ExecutionContext context) throws IOException, InterruptedException {
    MakeCleanDirectoryStep mkDir = new MakeCleanDirectoryStep(ocamlContext.getGeneratedSourceDir());
    int mkDirExitCode = mkDir.execute(context);
    if (mkDirExitCode != 0) {
      return mkDirExitCode;
    }
    for (Path yaccSource : ocamlContext.getYaccInput()) {
      Path output = ocamlContext.getYaccOutput(ImmutableSet.of(yaccSource)).get(0);
      OCamlYaccStep yaccStep = new OCamlYaccStep(
          new OCamlYaccStep.Args(
            ocamlContext.getYaccCompiler(),
            output,
            yaccSource));
      int yaccExitCode = yaccStep.execute(context);
      if (yaccExitCode != 0) {
        return yaccExitCode;
      }
    }
    for (Path lexSource : ocamlContext.getLexInput()) {
      Path output = ocamlContext.getLexOutput(ImmutableSet.of(lexSource)).get(0);
      OCamlLexStep lexStep = new OCamlLexStep(
          new OCamlLexStep.Args(
            ocamlContext.getLexCompiler(),
            output,
            lexSource));
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
