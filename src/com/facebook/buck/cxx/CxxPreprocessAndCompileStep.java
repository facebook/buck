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

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.Compiler;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.DependencyTrackingMode;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A step that preprocesses and/or compiles C/C++ sources in a single step. */
class CxxPreprocessAndCompileStep implements Step {

  private static final Logger LOG = Logger.get(CxxPreprocessAndCompileStep.class);

  private final ProjectFilesystem filesystem;
  private final Operation operation;
  private final Path output;
  private final Optional<Path> depFile;
  private final Path input;
  private final CxxSource.Type inputType;
  private final ToolCommand command;
  private final HeaderPathNormalizer headerPathNormalizer;
  private final DebugPathSanitizer sanitizer;
  private final Compiler compiler;
  private final Optional<CxxLogInfo> cxxLogInfo;

  /** Directory to use to store intermediate/temp files used for compilation. */
  private final Path scratchDir;

  private final boolean useArgfile;

  private static final FileLastModifiedDateContentsScrubber FILE_LAST_MODIFIED_DATE_SCRUBBER =
      new FileLastModifiedDateContentsScrubber();

  private static final String DEPENDENCY_OUTPUT_PREFIX = "Note: including file:";
  private static final String INCLUDE_GUARD_SUGGESTION =
      "Multiple include guards may be useful for:";
  private static final Pattern showHeadersLinePattern = Pattern.compile("\\.+ .+");

  public CxxPreprocessAndCompileStep(
      ProjectFilesystem filesystem,
      Operation operation,
      Path output,
      Optional<Path> depFile,
      Path input,
      CxxSource.Type inputType,
      ToolCommand command,
      HeaderPathNormalizer headerPathNormalizer,
      DebugPathSanitizer sanitizer,
      Path scratchDir,
      boolean useArgfile,
      Compiler compiler,
      Optional<CxxLogInfo> cxxLogInfo) {
    this.filesystem = filesystem;
    this.operation = operation;
    this.output = output;
    this.depFile = depFile;
    this.input = input;
    this.inputType = inputType;
    this.command = command;
    this.headerPathNormalizer = headerPathNormalizer;
    this.sanitizer = sanitizer;
    this.scratchDir = scratchDir;
    this.useArgfile = useArgfile;
    this.compiler = compiler;
    this.cxxLogInfo = cxxLogInfo;
  }

  @Override
  public String getShortName() {
    return inputType.getLanguage() + " " + operation.toString().toLowerCase();
  }

  /**
   * Apply common settings for our subprocesses.
   *
   * @return Half-configured ProcessExecutorParams.Builder
   */
  private ProcessExecutorParams.Builder makeSubprocessBuilder(ExecutionContext context) {
    Map<String, String> env = new HashMap<>(context.getEnvironment());

    env.putAll(
        sanitizer.getCompilationEnvironment(
            filesystem.getRootPath().toAbsolutePath(), shouldSanitizeOutputBinary()));

    // Set `TMPDIR` to `scratchDir` so the compiler/preprocessor uses this dir for it's temp and
    // intermediate files.
    env.put("TMPDIR", filesystem.resolve(scratchDir).toString());

    if (cxxLogInfo.isPresent()) {
      // Add some diagnostic strings into the subprocess's env as well.
      // Note: the current process's env already contains `BUCK_BUILD_ID`, which will be inherited.
      CxxLogInfo info = cxxLogInfo.get();

      info.getTarget().ifPresent(target -> env.put("BUCK_BUILD_TARGET", target.toString()));
      info.getSourcePath().ifPresent(path -> env.put("BUCK_BUILD_RULE_SOURCE", path.toString()));
      info.getOutputPath().ifPresent(path -> env.put("BUCK_BUILD_RULE_OUTPUT", path.toString()));
    }

    return ProcessExecutorParams.builder()
        .setDirectory(filesystem.getRootPath().toAbsolutePath())
        .setRedirectError(ProcessBuilder.Redirect.PIPE)
        .setEnvironment(ImmutableMap.copyOf(env));
  }

  private Path getArgfile() {
    return filesystem.resolve(scratchDir).resolve("ppandcompile.argsfile");
  }

  @VisibleForTesting
  ImmutableList<String> getArguments(boolean allowColorsInDiagnostics) {
    boolean useUnixPathSeparator = compiler.getUseUnixPathSeparator();
    String inputLanguage =
        operation == Operation.GENERATE_PCH
            ? inputType.getPrecompiledHeaderLanguage().get()
            : inputType.getLanguage();
    return ImmutableList.<String>builder()
        .addAll(
            (allowColorsInDiagnostics
                    ? compiler.getFlagsForColorDiagnostics()
                    : Optional.<ImmutableList<String>>empty())
                .orElseGet(ImmutableList::of))
        .addAll(compiler.languageArgs(inputLanguage))
        .addAll(command.getArguments())
        .addAll(
            sanitizer.getCompilationFlags(
                compiler, filesystem.getRootPath(), headerPathNormalizer.getPrefixMap()))
        .addAll(
            compiler.outputArgs(
                useUnixPathSeparator
                    ? MorePaths.pathWithUnixSeparators(output.toString())
                    : output.toString()))
        .add("-c")
        .addAll(
            depFile
                .map(depFile -> compiler.outputDependenciesArgs(depFile.toString()))
                .orElseGet(ImmutableList::of))
        .add(
            useUnixPathSeparator
                ? MorePaths.pathWithUnixSeparators(input.toString())
                : input.toString())
        .build();
  }

  private ProcessExecutor.Result executeCompilation(ExecutionContext context)
      throws IOException, InterruptedException {
    ProcessExecutorParams.Builder builder = makeSubprocessBuilder(context);

    if (useArgfile) {
      filesystem.writeLinesToPath(
          Iterables.transform(
              getArguments(context.getAnsi().isAnsiTerminal()), Escaper.ARGFILE_ESCAPER::apply),
          getArgfile());
      builder.setCommand(
          ImmutableList.<String>builder()
              .addAll(command.getCommandPrefix())
              .add("@" + getArgfile())
              .build());
    } else {
      builder.setCommand(
          ImmutableList.<String>builder()
              .addAll(command.getCommandPrefix())
              .addAll(getArguments(context.getAnsi().isAnsiTerminal()))
              .build());
    }

    ProcessExecutorParams params = builder.build();

    if (LOG.isVerboseEnabled()) {
      LOG.verbose("Running command (pwd=%s): %s", params.getDirectory(), getDescription(context));
    }

    ProcessExecutor.Result result =
        new DefaultProcessExecutor(Console.createNullConsole()).launchAndExecute(params);

    String err = getSanitizedStderr(result, context);
    result =
        new ProcessExecutor.Result(
            result.getExitCode(), result.isTimedOut(), result.getStdout(), Optional.of(err));
    processResult(result, context);
    return result;
  }

  private void processResult(ProcessExecutor.Result result, ExecutionContext context) {
    // If we generated any error output, print that to the console.
    String err = result.getStderr().orElse("");
    if (!err.isEmpty()) {
      context
          .getBuckEventBus()
          .post(
              createConsoleEvent(
                  context,
                  compiler.getFlagsForColorDiagnostics().isPresent(),
                  result.getExitCode() == 0 ? Level.WARNING : Level.SEVERE,
                  err));
    }
  }

  /**
   * @return The sanitized version of stderr captured during step execution. Sanitized output does
   *     not include symlink references and other internal buck details.
   */
  private String getSanitizedStderr(ProcessExecutor.Result result, ExecutionContext context)
      throws IOException {
    String stdErr = compiler.getStderr(result).orElse("");
    Stream<String> lines = MoreStrings.lines(stdErr).stream();

    if (compiler.needsToRemoveCompiledFilenamesFromOutput()) {
      // In order to get cleaner logs, the following filter removes lines
      // with only the filename of the file being compiled,
      // which is an unavoidable behaviour of the Windows compiler.
      lines = lines.filter(line -> !line.equals(input.getFileName().toString()));
    }

    String err;
    if (depFile.isPresent()
        && compiler.getDependencyTrackingMode() == DependencyTrackingMode.SHOW_INCLUDES) {
      // Include lines and errors lines should be processed differently.
      Map<Boolean, List<String>> includesAndErrors =
          lines.collect(Collectors.partitioningBy(CxxPreprocessAndCompileStep::isShowIncludeLine));
      List<String> includeLines = includesAndErrors.getOrDefault(true, Collections.emptyList());
      List<String> errorLines = includesAndErrors.getOrDefault(false, Collections.emptyList());

      includeLines =
          includeLines.stream()
              .map(CxxPreprocessAndCompileStep::parseShowIncludeLine)
              .collect(Collectors.toList());
      writeSrcAndIncludes(includeLines, depFile.get());
      err = formatErrors(errorLines.stream(), context);
    } else if (depFile.isPresent()
        && compiler.getDependencyTrackingMode() == DependencyTrackingMode.SHOW_HEADERS) {
      // Headers lines and errors lines should be processed differently.
      Map<Boolean, List<String>> includesAndErrors =
          lines.collect(Collectors.partitioningBy(CxxPreprocessAndCompileStep::isShowHeadersLine));
      List<String> includeLines = includesAndErrors.getOrDefault(true, Collections.emptyList());
      List<String> errorLines = includesAndErrors.getOrDefault(false, Collections.emptyList());

      includeLines =
          includeLines.stream()
              .map(CxxPreprocessAndCompileStep::parseShowHeadersLine)
              .collect(Collectors.toList());
      writeSrcAndIncludes(includeLines, depFile.get());
      // We are not interested in showing suggestions about include guards.
      errorLines = stripIncludeGuardSuggestions(errorLines);
      err = formatErrors(errorLines.stream(), context);
    } else {
      err = formatErrors(lines, context);
    }
    return err;
  }

  private static List<String> stripIncludeGuardSuggestions(List<String> errorLines) {
    int includeGuardSuggestionIdx = errorLines.indexOf(INCLUDE_GUARD_SUGGESTION);
    if (includeGuardSuggestionIdx != -1) {
      return errorLines.subList(0, includeGuardSuggestionIdx);
    } else {
      return errorLines;
    }
  }

  private void writeSrcAndIncludes(List<String> includeLines, Path depFile) throws IOException {
    Iterable<String> srcAndIncludes =
        Iterables.concat(ImmutableList.of(filesystem.resolve(input).toString()), includeLines);
    filesystem.writeLinesToPath(srcAndIncludes, depFile);
  }

  private String formatErrors(Stream<String> errorLines, ExecutionContext context) {
    CxxErrorTransformer cxxErrorTransformer =
        new CxxErrorTransformer(
            filesystem, context.shouldReportAbsolutePaths(), headerPathNormalizer);
    return errorLines
        .map(cxxErrorTransformer::transformLine)
        .collect(Collectors.joining(System.lineSeparator()));
  }

  private static boolean isShowIncludeLine(String line) {
    return line.startsWith(DEPENDENCY_OUTPUT_PREFIX);
  }

  private static String parseShowIncludeLine(String line) {
    // We keep the spaces at the beginning since we may use them to reconstruct the include tree
    return line.substring(DEPENDENCY_OUTPUT_PREFIX.length());
  }

  private static boolean isShowHeadersLine(String line) {
    return showHeadersLinePattern.matcher(line).matches();
  }

  private static String parseShowHeadersLine(String line) {
    // Replace . with spaces at the beginning to match with showIncludes format.
    int nestedDepth = line.indexOf(' ');
    return Strings.repeat(" ", nestedDepth) + line.substring(nestedDepth + 1);
  }

  private ConsoleEvent createConsoleEvent(
      ExecutionContext context, boolean commandOutputsColor, Level level, String message) {
    if (context.getAnsi().isAnsiTerminal() && commandOutputsColor) {
      return ConsoleEvent.createForMessageWithAnsiEscapeCodes(level, message);
    } else {
      return ConsoleEvent.create(level, message);
    }
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("%s %s -> %s", operation.toString().toLowerCase(), input, output);
    }

    ProcessExecutor.Result result = executeCompilation(context);
    int exitCode = result.getExitCode();

    if (exitCode == 0) {
      Path path = filesystem.getRootPath().toAbsolutePath().resolve(output);

      // Guarantee that the output file exists
      if (!Files.exists(path)) {
        LOG.warn("Execution has exitCode 0 but output file does not exist: %s", path);
      }

      // If the compilation completed successfully and we didn't effect debug-info normalization
      // through #line directive modification, perform the in-place update of the compilation per
      // above.  This locates the relevant debug section and swaps out the expanded actual
      // compilation directory with the one we really want.
      if (shouldSanitizeOutputBinary()) {
        sanitizer.restoreCompilationDirectory(path, filesystem.getRootPath().toAbsolutePath());
        FILE_LAST_MODIFIED_DATE_SCRUBBER.scrubFileWithPath(path);
      }
    }

    if (exitCode != 0) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("error %d %s %s", exitCode, operation.toString().toLowerCase(), input);
      }
    }

    return StepExecutionResult.of(result);
  }

  ImmutableList<String> getCommand() {
    // We set allowColorsInDiagnostics to false here because this function is only used by the
    // compilation database (its contents should not depend on how Buck was invoked) and in the
    // step's description. It is not used to determine what command this step runs, which needs
    // to decide whether to use colors or not based on whether the terminal supports them.
    return ImmutableList.<String>builder()
        .addAll(command.getCommandPrefix())
        .addAll(getArguments(false))
        .build();
  }

  @Override
  public String getDescription(ExecutionContext context) {
    if (context.getVerbosity().shouldPrintCommand()) {
      return Stream.concat(command.getCommandPrefix().stream(), getArguments(false).stream())
          .map(Escaper.SHELL_ESCAPER)
          .collect(Collectors.joining(" "));
    }
    return "(verbosity level disables command output)";
  }

  private boolean shouldSanitizeOutputBinary() {
    return inputType.isAssembly()
        || (operation == Operation.PREPROCESS_AND_COMPILE && compiler.shouldSanitizeOutputBinary());
  }

  public enum Operation {
    /** Run only the compiler on source files. */
    COMPILE,
    /** Run the preprocessor and compiler on source files. */
    PREPROCESS_AND_COMPILE,
    GENERATE_PCH,
    ;
  }

  public static class ToolCommand {
    private final ImmutableList<String> commandPrefix;
    private final ImmutableList<String> arguments;
    private final ImmutableMap<String, String> environment;

    public ToolCommand(
        ImmutableList<String> commandPrefix,
        ImmutableList<String> arguments,
        ImmutableMap<String, String> environment) {
      this.commandPrefix = commandPrefix;
      this.arguments = arguments;
      this.environment = environment;
    }

    public ImmutableList<String> getCommandPrefix() {
      return commandPrefix;
    }

    public ImmutableList<String> getArguments() {
      return arguments;
    }

    public ImmutableMap<String, String> getEnvironment() {
      return environment;
    }
  }
}
