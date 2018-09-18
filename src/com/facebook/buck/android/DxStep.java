/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

public class DxStep extends ShellStep {

  /** Options to pass to {@code dx}. */
  public enum Option {
    /** Specify the {@code --no-optimize} flag when running {@code dx}. */
    NO_OPTIMIZE,

    /** Specify the {@code --force-jumbo} flag when running {@code dx}. */
    FORCE_JUMBO,

    /**
     * See if the {@code buck.dx} property was specified, and if so, use the executable that that
     * points to instead of the {@code dx} in the user's Android SDK.
     */
    USE_CUSTOM_DX_IF_AVAILABLE,

    /** Execute DX in-process instead of fork/execing. This only works with custom dx. */
    RUN_IN_PROCESS,

    /** Run DX with the --no-locals flag. */
    NO_LOCALS,
    ;
  }

  /** Available tools to create dex files * */
  public static final String DX = "dx";

  public static final String D8 = "d8";

  /**
   * this is nothing more than a heuristic based on looking around for ARG_MAX values. a proper way
   * might be to determine this value from the OS somehow (like sysconf(_SC_ARG_MAX)), but I haven't
   * found an easy way to do it from java. This particular value is based on Windows'
   * CreateProcess() limit of 32768 *characters*, which seems to be the smallest.
   */
  private static final int ARG_MAX = 32768;

  private final ProjectFilesystem filesystem;
  private final AndroidPlatformTarget androidPlatformTarget;
  private final Path outputDexFile;
  private final Set<Path> filesToDex;
  private final Set<Option> options;
  private final Optional<String> maxHeapSize;
  private final String dexTool;
  private final boolean intermediate;

  @Nullable private Collection<String> resourcesReferencedInCode;

  /**
   * @param outputDexFile path to the file where the generated classes.dex should go.
   * @param filesToDex each element in this set is a path to a .class file, a zip file of .class
   *     files, or a directory of .class files.
   */
  public DxStep(
      ProjectFilesystem filesystem,
      AndroidPlatformTarget androidPlatformTarget,
      Path outputDexFile,
      Iterable<Path> filesToDex) {
    this(
        filesystem,
        androidPlatformTarget,
        outputDexFile,
        filesToDex,
        EnumSet.noneOf(DxStep.Option.class),
        DX);
  }

  /**
   * @param outputDexFile path to the file where the generated classes.dex should go.
   * @param filesToDex each element in this set is a path to a .class file, a zip file of .class
   *     files, or a directory of .class files.
   * @param options to pass to {@code dx}.
   * @param dexTool the tool used to perform dexing.
   */
  public DxStep(
      ProjectFilesystem filesystem,
      AndroidPlatformTarget androidPlatformTarget,
      Path outputDexFile,
      Iterable<Path> filesToDex,
      EnumSet<Option> options,
      String dexTool) {
    this(
        filesystem,
        androidPlatformTarget,
        outputDexFile,
        filesToDex,
        options,
        Optional.empty(),
        dexTool,
        false);
  }

  /**
   * @param outputDexFile path to the file where the generated classes.dex should go.
   * @param filesToDex each element in this set is a path to a .class file, a zip file of .class
   *     files, or a directory of .class files.
   * @param options to pass to {@code dx}.
   * @param maxHeapSize The max heap size used for out of process dex.
   * @param dexTool the tool used to perform dexing.
   */
  public DxStep(
      ProjectFilesystem filesystem,
      AndroidPlatformTarget androidPlatformTarget,
      Path outputDexFile,
      Iterable<Path> filesToDex,
      EnumSet<Option> options,
      Optional<String> maxHeapSize,
      String dexTool,
      boolean intermediate) {
    super(filesystem.getRootPath());
    this.filesystem = filesystem;
    this.androidPlatformTarget = androidPlatformTarget;
    this.outputDexFile = filesystem.resolve(outputDexFile);
    this.filesToDex = ImmutableSet.copyOf(filesToDex);
    this.options = Sets.immutableEnumSet(options);
    this.maxHeapSize = maxHeapSize;
    this.dexTool = dexTool;
    this.intermediate = intermediate;

    Preconditions.checkArgument(
        !options.contains(Option.RUN_IN_PROCESS)
            || options.contains(Option.USE_CUSTOM_DX_IF_AVAILABLE),
        "In-process dexing is only supported with custom DX");
    Preconditions.checkArgument(
        !intermediate || dexTool.equals(D8), "Intermediate dexing is only supported with D8");
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    CharsCountingStringList commandArgs = new CharsCountingStringList(10 + filesToDex.size());

    // TODO: Support D8 for out of process dexing by respecting dexTool here
    String dx = androidPlatformTarget.getDxExecutable().toString();

    if (dexTool.equals(D8)) {
      context.postEvent(
          ConsoleEvent.fine("Using %s instead of D8. D8 can only be used in-process.", dx));
    }

    if (options.contains(Option.USE_CUSTOM_DX_IF_AVAILABLE)) {
      String customDx = Strings.emptyToNull(System.getProperty("buck.dx"));
      dx = customDx != null ? customDx : dx;
    }

    commandArgs.add(dx);

    // Add the Xmx override, but not for in-process dexing, since the dexer won't understand it.
    // Also, if DX works in-process, it probably wouldn't need an enlarged Xmx.
    if (maxHeapSize.isPresent() && !options.contains(Option.RUN_IN_PROCESS)) {
      commandArgs.add(String.format("-JXmx%s", maxHeapSize.get()));
    }

    commandArgs.add("--dex");

    // --statistics flag, if appropriate.
    if (context.getVerbosity().shouldPrintSelectCommandOutput()) {
      commandArgs.add("--statistics");
    }

    if (options.contains(Option.NO_OPTIMIZE)) {
      commandArgs.add("--no-optimize");
    }

    if (options.contains(Option.FORCE_JUMBO)) {
      commandArgs.add("--force-jumbo");
    }

    // --no-locals flag, if appropriate.
    if (options.contains(Option.NO_LOCALS)) {
      commandArgs.add("--no-locals");
    }

    // verbose flag, if appropriate.
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      commandArgs.add("--verbose");
    }

    commandArgs.add("--output");
    commandArgs.add(filesystem.resolve(outputDexFile).toString());

    final int splitPoint = commandArgs.size();

    for (Path fileToDex : filesToDex) {
      // TODO: Does this need to also resolve directory entries as below when using D8?
      commandArgs.add(filesystem.resolve(fileToDex).toString());
    }

    ImmutableList.Builder<String> builder = new ImmutableList.Builder<>();
    if (!isRunningInProc()
        && splitPoint < commandArgs.size()
        && isOverArgMax(commandArgs.getCharCount(), commandArgs.size())) {
      builder.addAll(commandArgs.subList(0, splitPoint));
      String inputListFile;
      try {
        inputListFile =
            writeFileToDexArgsToFile(commandArgs.subList(splitPoint, commandArgs.size()));
      } catch (IOException e) {
        throw new RuntimeException("failed to create argument list file", e);
      }
      builder.add("--input-list");
      builder.add(inputListFile);
    } else {
      builder.addAll(commandArgs);
    }

    return builder.build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    if (isRunningInProc()) {
      return StepExecutionResult.of(executeInProcess(context));
    } else {
      return super.execute(context);
    }
  }

  private int executeInProcess(ExecutionContext context) {
    if (D8.equals(dexTool)) {

      D8DiagnosticsHandler diagnosticsHandler = new D8DiagnosticsHandler();

      try {
        Set<Path> inputs = new HashSet<>();
        for (Path rawFile : filesToDex) {
          Path toDex = filesystem.resolve(rawFile);
          if (Files.isRegularFile(toDex)) {
            inputs.add(toDex);
          } else {
            Files.walk(toDex).filter(path -> path.toFile().isFile()).forEach(inputs::add);
          }
        }

        // D8 only outputs to dex if the output path is a directory. So we output to a temporary dir
        // and move it over to the final location
        boolean outputToDex = outputDexFile.getFileName().toString().endsWith(".dex");
        Path output = outputToDex ? Files.createTempDirectory("buck-d8") : outputDexFile;

        D8Command.Builder builder =
            D8Command.builder(diagnosticsHandler)
                .addProgramFiles(inputs)
                .setIntermediate(intermediate)
                .addLibraryFiles(androidPlatformTarget.getAndroidJar())
                .setMode(
                    options.contains(Option.NO_OPTIMIZE)
                        ? CompilationMode.DEBUG
                        : CompilationMode.RELEASE)
                .setOutput(output, OutputMode.DexIndexed);
        D8Command d8Command = builder.build();
        com.android.tools.r8.D8.run(d8Command);

        if (outputToDex) {
          File[] outputs = output.toFile().listFiles();
          if (outputs != null && (outputs.length > 0)) {
            Files.move(outputs[0].toPath(), outputDexFile, StandardCopyOption.REPLACE_EXISTING);
          }
        }

        resourcesReferencedInCode = d8Command.getDexItemFactory().computeReferencedResources();
        return 0;
      } catch (CompilationFailedException | IOException e) {
        context.postEvent(
            ConsoleEvent.severe(
                String.join(
                    System.lineSeparator(),
                    diagnosticsHandler
                        .diagnostics
                        .stream()
                        .map(Diagnostic::getDiagnosticMessage)
                        .collect(ImmutableList.toImmutableList()))));
        e.printStackTrace(context.getStdErr());
        return 1;
      }
    } else if (DX.equals(dexTool)) {
      ImmutableList<String> argv = getShellCommandInternal(context);

      // The first arguments should be ".../dx --dex" ("...\dx.bat --dex on Windows).  Strip them
      // off
      // because we bypass the dispatcher and go straight to the dexer.
      Preconditions.checkState(
          argv.get(0).endsWith(File.separator + "dx") || argv.get(0).endsWith("\\dx.bat"));
      Preconditions.checkState(argv.get(1).equals("--dex"));
      ImmutableList<String> args = argv.subList(2, argv.size());

      ByteArrayOutputStream stderr = new ByteArrayOutputStream();
      PrintStream stderrStream = new PrintStream(stderr);
      try {
        com.android.dx.command.dexer.DxContext dxContext =
            new com.android.dx.command.dexer.DxContext(context.getStdOut(), stderrStream);
        com.android.dx.command.dexer.Main.Arguments arguments =
            new com.android.dx.command.dexer.Main.Arguments();
        com.android.dx.command.dexer.Main dexer = new com.android.dx.command.dexer.Main(dxContext);
        arguments.parseCommandLine(args.toArray(new String[0]), dxContext);
        int returncode = dexer.run(arguments);
        String stdErrOutput = stderr.toString();
        if (!stdErrOutput.isEmpty()) {
          context.postEvent(ConsoleEvent.warning("%s", stdErrOutput));
        }
        if (returncode == 0) {
          resourcesReferencedInCode = dexer.getReferencedResourceNames();
        }
        return returncode;
      } catch (IOException e) {
        e.printStackTrace(context.getStdErr());
        return 1;
      }
    } else {
      return 1;
    }
  }

  @Override
  protected boolean shouldPrintStderr(Verbosity verbosity) {
    return verbosity.shouldPrintSelectCommandOutput();
  }

  @Override
  protected boolean shouldPrintStdout(Verbosity verbosity) {
    return verbosity.shouldPrintSelectCommandOutput();
  }

  @Override
  public String getShortName() {
    return dexTool;
  }

  /**
   * Return the names of resources referenced in the code that was dexed. This is only valid after
   * the step executes successfully and only when in-process dexing is used. It only returns
   * resources referenced in java classes being dexed, not merged dex files.
   */
  @Nullable
  Collection<String> getResourcesReferencedInCode() {
    return resourcesReferencedInCode;
  }

  private boolean isOverArgMax(int charsCount, int argsCount) {
    return charsCount * 2 + argsCount - 1 >= ARG_MAX;
  }

  private boolean isRunningInProc() {
    // D8 runs in process only
    return options.contains(Option.RUN_IN_PROCESS) || D8.equals(dexTool);
  }

  private String writeFileToDexArgsToFile(List<String> items) throws IOException {
    Path path = filesystem.createTempFile("dx_input_list", "");
    try (BufferedWriter writer =
        new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path.toFile())))) {
      for (String item : items) {
        writer.write(item);
        writer.write("\n");
      }
    }
    return path.toAbsolutePath().toString();
  }

  private static class D8DiagnosticsHandler implements DiagnosticsHandler {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    @Override
    public void warning(Diagnostic warning) {
      diagnostics.add(warning);
    }

    @Override
    public void info(Diagnostic info) {}
  }

  private static class CharsCountingStringList extends ArrayList<String> {
    private int mCharCounter = 0;

    public CharsCountingStringList(int initialSize) {
      super(initialSize);
    }

    @Override
    public boolean add(@Nullable String e) {
      if (e == null) {
        throw new NullPointerException("argument may not be null");
      }
      mCharCounter += e.length();
      return super.add(e);
    }

    public int getCharCount() {
      return mCharCounter;
    }
  }
}
