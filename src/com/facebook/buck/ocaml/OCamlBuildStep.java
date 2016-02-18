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
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A step that preprocesses, compiles, and assembles OCaml sources.
 */
public class OCamlBuildStep implements Step {

  private final SourcePathResolver resolver;
  private final ProjectFilesystem filesystem;
  private final OCamlBuildContext ocamlContext;
  private final ImmutableMap<String, String> cCompilerEnvironment;
  private final ImmutableList<String> cCompiler;
  private final ImmutableMap<String, String> cxxCompilerEnvironment;
  private final ImmutableList<String> cxxCompiler;

  private final boolean hasGeneratedSources;
  private final OCamlDepToolStep depToolStep;

  public OCamlBuildStep(
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      OCamlBuildContext ocamlContext,
      ImmutableMap<String, String> cCompilerEnvironment,
      ImmutableList<String> cCompiler,
      ImmutableMap<String, String> cxxCompilerEnvironment,
      ImmutableList<String> cxxCompiler) {
    this.resolver = resolver;
    this.filesystem = filesystem;
    this.ocamlContext = ocamlContext;
    this.cCompilerEnvironment = cCompilerEnvironment;
    this.cCompiler = cCompiler;
    this.cxxCompilerEnvironment = cxxCompilerEnvironment;
    this.cxxCompiler = cxxCompiler;

    hasGeneratedSources = ocamlContext.getLexInput().size() > 0 ||
        ocamlContext.getYaccInput().size() > 0;

    this.depToolStep = new OCamlDepToolStep(
        filesystem.getRootPath(),
        this.ocamlContext.getSourcePathResolver(),
        this.ocamlContext.getOcamlDepTool().get(),
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
  public int execute(ExecutionContext context)
      throws IOException, InterruptedException {
    if (hasGeneratedSources) {
      int genExitCode = generateSources(context, filesystem.getRootPath());
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
    ImmutableList<Path> sortedInput = sortDependency(depToolStep.getStdout());
    ImmutableList.Builder<Path> nativeLinkerInputs = ImmutableList.builder();
    int mlCompileNativeExitCode = executeMLNativeCompilation(
        context,
        filesystem.getRootPath(),
        sortedInput,
        nativeLinkerInputs);
    if (mlCompileNativeExitCode != 0) {
      return mlCompileNativeExitCode;
    }

    ImmutableList.Builder<Path> bytecodeLinkerInputs = ImmutableList.builder();
    int mlCompileBytecodeExitCode = executeMLBytecodeCompilation(
        context,
        filesystem.getRootPath(),
        sortedInput,
        bytecodeLinkerInputs);
    if (mlCompileBytecodeExitCode != 0) {
      return mlCompileBytecodeExitCode;
    }

    ImmutableList.Builder<Path> cLinkerInputs = ImmutableList.builder();
    int cCompileExitCode = executeCCompilation(context, cLinkerInputs);
    if (cCompileExitCode != 0) {
      return cCompileExitCode;
    }

    ImmutableList<Path> cObjects = cLinkerInputs.build();

    nativeLinkerInputs.addAll(cObjects);
    int nativeLinkExitCode = executeLinking(context, nativeLinkerInputs.build());
    if (nativeLinkExitCode != 0) {
      return nativeLinkExitCode;
    }

    bytecodeLinkerInputs.addAll(cObjects);
    int bytecodeLinkExitCode =  executeBytecodeLinking(context, bytecodeLinkerInputs.build());
    if (bytecodeLinkExitCode != 0) {
      return bytecodeLinkExitCode;
    }

    if (!ocamlContext.isLibrary()) {
      Step debugLauncher = new OCamlDebugLauncherStep(
          filesystem,
          resolver,
          new OCamlDebugLauncherStep.Args(
              ocamlContext.getOcamlDebug().get(),
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
      ImmutableList.Builder<Path> linkerInputs) throws IOException, InterruptedException {

    ImmutableList.Builder<String> cCompileFlags = ImmutableList.builder();
    cCompileFlags.addAll(ocamlContext.getCCompileFlags());
    cCompileFlags.addAll(ocamlContext.getCommonCFlags());

    CxxPreprocessorInput cxxPreprocessorInput = ocamlContext.getCxxPreprocessorInput();

    for (SourcePath cSrc : ocamlContext.getCInput()) {
      Path outputPath = ocamlContext.getCOutput(resolver.getAbsolutePath(cSrc));
      linkerInputs.add(outputPath);
      Step compileStep = new OCamlCCompileStep(
          resolver,
          filesystem.getRootPath(),
          new OCamlCCompileStep.Args(
              cCompilerEnvironment,
              cCompiler,
              ocamlContext.getOcamlCompiler().get(),
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
      ImmutableList<Path> linkerInputs) throws IOException, InterruptedException {

    ImmutableList.Builder<String> flags = ImmutableList.builder();
    flags.addAll(ocamlContext.getFlags());
    flags.addAll(ocamlContext.getCommonCLinkerFlags());

    OCamlLinkStep linkStep = new OCamlLinkStep(
        filesystem.getRootPath(),
        cxxCompilerEnvironment,
        cxxCompiler,
        ocamlContext.getOcamlCompiler().get().getCommandPrefix(resolver),
        flags.build(),
        ocamlContext.getNativeOutput(),
        ocamlContext.getNativeLinkableInput().getArgs(),
        ocamlContext.getCLinkableInput().getArgs(),
        linkerInputs,
        ocamlContext.isLibrary(),
        /* isBytecode */ false);
    return linkStep.execute(context);
  }

  private int executeBytecodeLinking(
      ExecutionContext context,
      ImmutableList<Path> linkerInputs) throws IOException, InterruptedException {

    ImmutableList.Builder<String> flags = ImmutableList.builder();
    flags.addAll(ocamlContext.getFlags());
    flags.addAll(ocamlContext.getCommonCLinkerFlags());

    OCamlLinkStep linkStep = new OCamlLinkStep(
        filesystem.getRootPath(),
        cxxCompilerEnvironment,
        cxxCompiler,
        ocamlContext.getOcamlBytecodeCompiler().get().getCommandPrefix(resolver),
        flags.build(),
        ocamlContext.getBytecodeOutput(),
        ocamlContext.getNativeLinkableInput().getArgs(),
        ocamlContext.getCLinkableInput().getArgs(),
        linkerInputs,
        ocamlContext.isLibrary(),
        /* isBytecode */ true);
    return linkStep.execute(context);
  }

  private ImmutableList<String> getCompileFlags(boolean isBytecode, boolean excludeDeps) {
    String output = isBytecode ? ocamlContext.getCompileBytecodeOutputDir().toString() :
        ocamlContext.getCompileNativeOutputDir().toString();
    ImmutableList.Builder<String> flagBuilder = ImmutableList.builder();
    flagBuilder.addAll(ocamlContext.getIncludeFlags(isBytecode, /* excludeDeps */ excludeDeps));
    flagBuilder.addAll(ocamlContext.getFlags());
    flagBuilder.add(
        OCamlCompilables.OCAML_INCLUDE_FLAG,
        output);
    return flagBuilder.build();
  }

  private int executeMLNativeCompilation(
      ExecutionContext context,
      Path workingDirectory,
      ImmutableList<Path> sortedInput,
      ImmutableList.Builder<Path> linkerInputs
  ) throws IOException, InterruptedException {
    MakeCleanDirectoryStep mkDir = new MakeCleanDirectoryStep(
        filesystem,
        ocamlContext.getCompileNativeOutputDir());
    int mkDirExitCode = mkDir.execute(context);
    if (mkDirExitCode != 0) {
      return mkDirExitCode;
    }
    for (Path inputOutput : sortedInput) {
      String inputFileName = inputOutput.getFileName().toString();
      String outputFileName = inputFileName
          .replaceFirst(OCamlCompilables.OCAML_ML_REGEX, OCamlCompilables.OCAML_CMX)
          .replaceFirst(OCamlCompilables.OCAML_MLI_REGEX, OCamlCompilables.OCAML_CMI);
      Path outputPath = ocamlContext.getCompileNativeOutputDir().resolve(outputFileName);
      if (!outputFileName.endsWith(OCamlCompilables.OCAML_CMI)) {
        linkerInputs.add(outputPath);
      }
      final ImmutableList<String> compileFlags = getCompileFlags(
          /* isBytecode */ false,
          /* excludeDeps */ false);
      Step compileStep = new OCamlMLCompileStep(
          workingDirectory,
          resolver,
          new OCamlMLCompileStep.Args(
              filesystem.getAbsolutifier(),
              cCompilerEnvironment,
              cCompiler,
              ocamlContext.getOcamlCompiler().get(),
              outputPath,
              inputOutput,
              compileFlags));
      int compileExitCode = compileStep.execute(context);
      if (compileExitCode != 0) {
        return compileExitCode;
      }
    }
    return 0;
  }

  private int executeMLBytecodeCompilation(
      ExecutionContext context,
      Path workingDirectory,
      ImmutableList<Path> sortedInput,
      ImmutableList.Builder<Path> linkerInputs
  ) throws IOException, InterruptedException {
    MakeCleanDirectoryStep mkDir = new MakeCleanDirectoryStep(
        filesystem,
        ocamlContext.getCompileBytecodeOutputDir());
    int mkDirExitCode = mkDir.execute(context);
    if (mkDirExitCode != 0) {
      return mkDirExitCode;
    }
    for (Path inputOutput : sortedInput) {
      String inputFileName = inputOutput.getFileName().toString();
      String outputFileName = inputFileName
          .replaceFirst(OCamlCompilables.OCAML_ML_REGEX, OCamlCompilables.OCAML_CMO)
          .replaceFirst(OCamlCompilables.OCAML_MLI_REGEX, OCamlCompilables.OCAML_CMI);
      Path outputPath = ocamlContext.getCompileBytecodeOutputDir().resolve(outputFileName);
      if (!outputFileName.endsWith(OCamlCompilables.OCAML_CMI)) {
        linkerInputs.add(outputPath);
      }
      final ImmutableList<String> compileFlags = getCompileFlags(
          /* isBytecode */ true,
          /* excludeDeps */ false);
      Step compileBytecodeStep = new OCamlMLCompileStep(
          workingDirectory,
          resolver,
          new OCamlMLCompileStep.Args(
              filesystem.getAbsolutifier(),
              cCompilerEnvironment,
              cCompiler,
              ocamlContext.getOcamlBytecodeCompiler().get(),
              outputPath,
              inputOutput,
              compileFlags));
      int compileExitCode = compileBytecodeStep.execute(context);
      if (compileExitCode != 0) {
        return compileExitCode;
      }
    }
    return 0;
  }

  private int generateSources(ExecutionContext context, Path workingDirectory)
      throws IOException, InterruptedException {
    MakeCleanDirectoryStep mkDir = new MakeCleanDirectoryStep(
        filesystem,
        ocamlContext.getGeneratedSourceDir());
    int mkDirExitCode = mkDir.execute(context);
    if (mkDirExitCode != 0) {
      return mkDirExitCode;
    }
    for (SourcePath yaccSource : ocamlContext.getYaccInput()) {
      SourcePath output = ocamlContext.getYaccOutput(ImmutableSet.of(yaccSource)).get(0);
      OCamlYaccStep yaccStep = new OCamlYaccStep(
          workingDirectory,
          resolver,
          new OCamlYaccStep.Args(
            ocamlContext.getYaccCompiler().get(),
            resolver.getAbsolutePath(output),
            resolver.getAbsolutePath(yaccSource)));
      int yaccExitCode = yaccStep.execute(context);
      if (yaccExitCode != 0) {
        return yaccExitCode;
      }
    }
    for (SourcePath lexSource : ocamlContext.getLexInput()) {
      SourcePath output = ocamlContext.getLexOutput(ImmutableSet.of(lexSource)).get(0);
      OCamlLexStep lexStep = new OCamlLexStep(
          workingDirectory,
          resolver,
          new OCamlLexStep.Args(
            ocamlContext.getLexCompiler().get(),
            resolver.getAbsolutePath(output),
            resolver.getAbsolutePath(lexSource)));
      int lexExitCode = lexStep.execute(context);
      if (lexExitCode != 0) {
        return lexExitCode;
      }
    }
    return 0;
  }

  private ImmutableList<Path> sortDependency(String depOutput) {
    OCamlDependencyGraphGenerator graphGenerator = new OCamlDependencyGraphGenerator();
    return
        FluentIterable.from(graphGenerator.generate(depOutput))
        .transform(MorePaths.TO_PATH)
        .toList();
  }

}
