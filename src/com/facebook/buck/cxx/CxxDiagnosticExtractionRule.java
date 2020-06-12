/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.types.Pair;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** Generates the diagnostics for a single Cxx source file for a specific diagnostic tool. */
public class CxxDiagnosticExtractionRule extends ModernBuildRule<CxxDiagnosticExtractionRule.Impl> {
  /** Creates a diagnostic extraction rule for a specific compile rule. */
  public CxxDiagnosticExtractionRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      Pair<String, Tool> diagnosticTools,
      SourcePathRuleFinder sourcePathRuleFinder,
      Optional<PreprocessorDelegate> preprocessorDelegate,
      CompilerDelegate compilerDelegate,
      SourcePath input,
      Optional<CxxPrecompiledHeader> precompiledHeaderRule,
      CxxSource.Type inputType,
      DebugPathSanitizer sanitizer,
      boolean withDownwardApi) {
    super(
        buildTarget,
        projectFilesystem,
        sourcePathRuleFinder,
        new Impl(
            buildTarget,
            diagnosticTools,
            preprocessorDelegate,
            compilerDelegate,
            input,
            precompiledHeaderRule,
            inputType,
            sanitizer,
            withDownwardApi));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  /**
   * Writes a compile command into an argfile and runs a diagnostic tool which is provided a path to
   * said argfile.
   */
  static class Impl extends CxxPreprocessAndCompileBaseBuildable {
    private static final String OUTPUT_NAME = "diagnostic-map.json";

    @AddToRuleKey private final String diagnosticName;
    @AddToRuleKey private final Tool diagnosticTool;

    public Impl(
        BuildTarget targetName,
        Pair<String, Tool> diagnosticTools,
        Optional<PreprocessorDelegate> preprocessDelegate,
        CompilerDelegate compilerDelegate,
        SourcePath input,
        Optional<CxxPrecompiledHeader> precompiledHeaderRule,
        CxxSource.Type inputType,
        DebugPathSanitizer sanitizer,
        boolean withDownwardApi) {
      super(
          targetName,
          preprocessDelegate,
          compilerDelegate,
          OUTPUT_NAME,
          input,
          precompiledHeaderRule,
          inputType,
          sanitizer,
          withDownwardApi);
      this.diagnosticName = diagnosticTools.getFirst();
      this.diagnosticTool = diagnosticTools.getSecond();
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {

      // At a high level, it performs the following steps:
      // - Write compile command into an argfile
      // - Run diagnostic tool by passing the compile command argfile path
      ImmutableList.Builder<Step> steps = ImmutableList.builder();
      AbsPath resolvedOutput = filesystem.resolve(outputPathResolver.resolvePath(output));

      steps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  buildContext.getBuildCellRootPath(),
                  filesystem,
                  resolvedOutput.getPath().getParent())));

      // Write compile command to argfile
      AbsPath scratchDir = filesystem.resolve(outputPathResolver.getTempPath());
      AbsPath sourceInputPath = buildContext.getSourcePathResolver().getAbsolutePath(input);
      AbsPath argFilePath =
          addWriteCommandToArgFileStep(
              buildContext, filesystem, sourceInputPath.getPath(), scratchDir, steps);

      // Run diagnostic tool and create JSON output
      steps.add(
          new CxxExtractDiagnosticFromCompileCommand(
              filesystem,
              buildContext.getSourcePathResolver(),
              argFilePath.getPath(),
              diagnosticTool,
              diagnosticName,
              sourceInputPath.getPath(),
              resolvedOutput.getPath(),
              withDownwardApi));

      return steps.build();
    }

    private AbsPath addWriteCommandToArgFileStep(
        BuildContext context,
        ProjectFilesystem filesystem,
        Path inputPath,
        AbsPath scratchDir,
        ImmutableList.Builder<Step> steps) {
      AbsPath argFilePath = scratchDir.resolve("compile_command.argsfile");
      AbsPath objectFilePath = scratchDir.resolve("object.o");

      SourcePathResolverAdapter resolver = context.getSourcePathResolver();
      CxxToolFlags preprocessorDelegateFlags = getCxxToolFlags(resolver);
      ImmutableList<Arg> compilerArguments = getArguments(filesystem, preprocessorDelegateFlags);

      ImmutableList<String> arguments =
          CxxPreprocessAndCompileEnhancer.getCompilationCommand(
              false,
              compilerDelegate.getCompiler(),
              getOperation(),
              inputType,
              makeCommand(context.getSourcePathResolver(), compilerArguments),
              sanitizer,
              filesystem,
              getHeaderPathNormalizer(context),
              objectFilePath.getPath(),
              getDepFile(objectFilePath.getPath()),
              inputPath);
      steps.add(new CxxWriteCompileCommandToArgFile(argFilePath.getPath(), arguments));
      return argFilePath;
    }
  }

  /** Runs a diagnostic tool which is expected to output JSON. */
  private static class CxxExtractDiagnosticFromCompileCommand extends AbstractExecutionStep {
    private final ProjectFilesystem filesystem;
    private final SourcePathResolverAdapter resolver;
    private final Path compileCommandArgsFilePath;
    private final Tool diagnosticTool;
    private final String diagnosticName;
    private final Path inputPath;
    private final Path outputPath;
    private final boolean withDownwardApi;

    CxxExtractDiagnosticFromCompileCommand(
        ProjectFilesystem filesystem,
        SourcePathResolverAdapter resolver,
        Path compileCommandArgsFilePath,
        Tool diagnosticTool,
        String diagnosticName,
        Path inputPath,
        Path outputPath,
        boolean withDownwardApi) {
      super("diagnostic-extraction-call-tool");
      this.filesystem = filesystem;
      this.resolver = resolver;
      this.compileCommandArgsFilePath = compileCommandArgsFilePath;
      this.diagnosticTool = diagnosticTool;
      this.diagnosticName = diagnosticName;
      this.inputPath = inputPath;
      this.outputPath = outputPath;
      this.withDownwardApi = withDownwardApi;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext context)
        throws IOException, InterruptedException {

      String jsonDiagnosticString = runDiagnosticTool(context);
      writeDiagnosticResult(jsonDiagnosticString);

      return StepExecutionResults.SUCCESS;
    }

    private String runDiagnosticTool(StepExecutionContext context)
        throws InterruptedException, IOException {
      ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
      commandBuilder.addAll(diagnosticTool.getCommandPrefix(resolver));
      commandBuilder.add("--input-path", inputPath.toString());
      commandBuilder.add("--command-file", compileCommandArgsFilePath.toString());

      ProcessExecutorParams.Builder paramsBuilder = ProcessExecutorParams.builder();
      ProcessExecutorParams processExecutorParams =
          paramsBuilder
              .setCommand(commandBuilder.build())
              .setDirectory(filesystem.getRootPath().getPath())
              .build();
      Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
      ProcessExecutor processExecutor = context.getProcessExecutor();
      if (withDownwardApi) {
        processExecutor =
            processExecutor.withDownwardAPI(
                DownwardApiProcessExecutor.FACTORY, context.getBuckEventBus());
      }

      ProcessExecutor.Result result =
          processExecutor.launchAndExecute(
              processExecutorParams,
              options,
              /* stdin */ Optional.empty(),
              /* timeOutMs */ Optional.empty(),
              /* timeOutHandler */ Optional.empty());

      Optional<String> stdOut = result.getStdout();
      if (!stdOut.isPresent() || stdOut.get().length() == 0) {
        throw new RuntimeException(
            String.format("Diagnostic '%s' did not produce any standard output", diagnosticName));
      }
      return stdOut.get();
    }

    private void writeDiagnosticResult(String jsonDiagnosticData) throws IOException {
      JsonNode diagnosticsNode;
      try {
        diagnosticsNode = ObjectMappers.READER.readTree(jsonDiagnosticData);
      } catch (IOException e) {
        throw new RuntimeException(
            String.format("Diagnostic '%s' did not produce JSON output", diagnosticName), e);
      }

      ObjectNode resultNode = (ObjectNode) ObjectMappers.READER.createObjectNode();
      // To ensure the output is cachable, we must not store absolute paths.
      resultNode.put("input_path", filesystem.relativize(inputPath).toString());
      resultNode.put("diagnostic_name", diagnosticName);
      resultNode.set("diagnostic_data", diagnosticsNode);

      String json = ObjectMappers.WRITER.writeValueAsString(resultNode);
      MostFiles.writeLinesToFile(ImmutableList.of(json), outputPath);
    }
  }

  /** Writes a command (list of arguments) to an argfile. */
  private static class CxxWriteCompileCommandToArgFile extends AbstractExecutionStep {
    private final Path argFilePath;
    private final ImmutableList<String> argFileContents;

    CxxWriteCompileCommandToArgFile(Path argFilePath, ImmutableList<String> argFileContents) {
      super("diagnostic-extraction-write-arg-file");
      Preconditions.checkArgument(argFilePath.isAbsolute());
      this.argFilePath = argFilePath;
      this.argFileContents = argFileContents;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext context)
        throws IOException, InterruptedException {
      Path argFileDir = argFilePath.getParent();
      if (Files.notExists(argFileDir)) {
        Files.createDirectories(argFileDir);
      }
      MostFiles.writeLinesToFile(argFileContents, argFilePath);
      return StepExecutionResults.SUCCESS;
    }
  }
}
