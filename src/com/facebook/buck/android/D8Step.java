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

package com.facebook.buck.android;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.utils.AbortException;
import com.android.tools.r8.utils.InternalOptions;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.File;
import java.io.IOException;
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
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Runs d8. */
public class D8Step extends IsolatedStep {

  /** Options to pass to {@code d8}. */
  public enum Option {
    /** Specify the {@code --debug} flag. Otherwise --release is specified */
    NO_OPTIMIZE,

    /** Force the dexer to emit jumbo string references */
    FORCE_JUMBO,

    /** Disable java 8 desugaring when running D8 dexing tool. */
    NO_DESUGAR,
    ;
  }

  public static final int SUCCESS_EXIT_CODE = 0;
  public static final int FAILURE_EXIT_CODE = 1;
  public static final int DEX_METHOD_REFERENCE_OVERFLOW_EXIT_CODE = 2;
  public static final int DEX_FIELD_REFERENCE_OVERFLOW_EXIT_CODE = 3;

  /** Available tools to create dex files * */
  public static final String D8 = "d8";

  // TODO: Refactor project file system out of DxStep to make it a fully conforming isolated step
  private final ProjectFilesystem filesystem;
  // TODO: Refactor android platform target out of DxStep to make it a fully conforming isolated
  // step
  private final AndroidPlatformTarget androidPlatformTarget;
  @VisibleForTesting final @Nullable Collection<Path> classpathFiles;
  private final Path outputDexFile;
  private final Set<Path> filesToDex;
  private final Set<Option> options;
  private final Optional<Path> primaryDexClassNamesPath;
  private final String dexTool;
  private final boolean intermediate;
  // used to differentiate different dexing buckets (if any)
  private final Optional<String> bucketId;
  private final Optional<Integer> minSdkVersion;

  @Nullable private Collection<String> resourcesReferencedInCode;

  /**
   * @param outputDexFile path to the file where the generated classes.dex should go.
   * @param filesToDex each element in this set is a path to a .class file, a zip file of .class
   */
  public D8Step(
      ProjectFilesystem filesystem,
      AndroidPlatformTarget androidPlatformTarget,
      Path outputDexFile,
      Iterable<Path> filesToDex) {
    this(
        filesystem,
        androidPlatformTarget,
        outputDexFile,
        filesToDex,
        EnumSet.noneOf(D8Step.Option.class),
        D8);
  }

  /**
   * @param outputDexFile path to the file where the generated classes.dex should go.
   * @param filesToDex each element in this set is a path to a .class file, a zip file of .class
   *     files, or a directory of .class files.
   * @param options to pass to {@code dx}.
   * @param dexTool the tool used to perform dexing.
   */
  public D8Step(
      ProjectFilesystem filesystem,
      AndroidPlatformTarget androidPlatformTarget,
      Path outputDexFile,
      Iterable<Path> filesToDex,
      EnumSet<Option> options,
      String dexTool) {
    this(filesystem, androidPlatformTarget, outputDexFile, filesToDex, options, dexTool, false);
  }

  /**
   * @param outputDexFile path to the file where the generated classes.dex should go.
   * @param filesToDex each element in this set is a path to a .class file, a zip file of .class
   *     files, or a directory of .class files.
   * @param options to pass to {@code dx}.
   * @param dexTool the tool used to perform dexing.
   */
  public D8Step(
      ProjectFilesystem filesystem,
      AndroidPlatformTarget androidPlatformTarget,
      Path outputDexFile,
      Iterable<Path> filesToDex,
      EnumSet<Option> options,
      String dexTool,
      boolean intermediate) {
    this(
        filesystem,
        androidPlatformTarget,
        outputDexFile,
        filesToDex,
        options,
        Optional.empty(),
        dexTool,
        intermediate,
        null,
        Optional.empty(),
        Optional.empty() /* minSdkVersion */);
  }

  /**
   * @param outputDexFile path to the file where the generated classes.dex should go.
   * @param filesToDex each element in this set is a path to a .class file, a zip file of .class
   *     files, or a directory of .class files.
   * @param options to pass to {@code dx}.
   * @param primaryDexClassNamesPath
   * @param dexTool the tool used to perform dexing.
   * @param classpathFiles specifies classpath for interface static and default methods desugaring.
   * @param minSdkVersion
   */
  public D8Step(
      ProjectFilesystem filesystem,
      AndroidPlatformTarget androidPlatformTarget,
      Path outputDexFile,
      Iterable<Path> filesToDex,
      EnumSet<Option> options,
      Optional<Path> primaryDexClassNamesPath,
      String dexTool,
      boolean intermediate,
      @Nullable Collection<Path> classpathFiles,
      Optional<String> bucketId,
      Optional<Integer> minSdkVersion) {
    super();
    this.filesystem = filesystem;
    this.androidPlatformTarget = androidPlatformTarget;
    this.classpathFiles = classpathFiles;
    this.outputDexFile = filesystem.resolve(outputDexFile);
    this.filesToDex = ImmutableSet.copyOf(filesToDex);
    this.options = Sets.immutableEnumSet(options);
    this.primaryDexClassNamesPath = primaryDexClassNamesPath.map(filesystem::resolve);
    this.dexTool = dexTool;
    this.intermediate = intermediate;
    this.bucketId = bucketId;
    this.minSdkVersion = minSdkVersion;

    Preconditions.checkArgument(dexTool.equals(D8));
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    return StepExecutionResult.of(executeInProcess(context));
  }

  private int executeInProcess(IsolatedExecutionContext context) throws IOException {
    Preconditions.checkState(D8.equals(dexTool));

    D8DiagnosticsHandler diagnosticsHandler = new D8DiagnosticsHandler();
    try {
      Set<Path> inputs = new HashSet<>();
      for (Path rawFile : filesToDex) {
        Path toDex = filesystem.resolve(rawFile);
        if (Files.isRegularFile(toDex)) {
          inputs.add(toDex);
        } else {
          try (Stream<Path> paths = Files.walk(toDex)) {
            paths.filter(path -> path.toFile().isFile()).forEach(inputs::add);
          }
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
              .setOutput(output, OutputMode.DexIndexed)
              .setDisableDesugaring(options.contains(Option.NO_DESUGAR))
              .setInternalOptionsModifier(
                  (InternalOptions opt) -> {
                    opt.testing.forceJumboStringProcessing = options.contains(Option.FORCE_JUMBO);
                  });

      bucketId.ifPresent(builder::setBucketId);
      minSdkVersion.ifPresent(builder::setMinApiLevel);
      primaryDexClassNamesPath.ifPresent(builder::addMainDexListFiles);

      if (classpathFiles != null && !classpathFiles.isEmpty()) {
        // classpathFiles is needed only for D8 java 8 desugar
        ImmutableSet.Builder<Path> absolutePaths = ImmutableSet.builder();
        for (Path classpathFile : classpathFiles) {
          absolutePaths.add(filesystem.getPathForRelativeExistingPath(classpathFile));
        }
        builder.addClasspathFiles(absolutePaths.build());
      }
      D8Command d8Command = builder.build();
      com.android.tools.r8.D8.run(d8Command);

      if (outputToDex) {
        File[] outputs = output.toFile().listFiles();
        if (outputs != null && (outputs.length > 0)) {
          Files.move(outputs[0].toPath(), outputDexFile, StandardCopyOption.REPLACE_EXISTING);
        }
      }

      resourcesReferencedInCode = d8Command.getDexItemFactory().computeReferencedResources();
      return SUCCESS_EXIT_CODE;
    } catch (CompilationFailedException e) {
      if (isOverloadedDexException(e)) {
        context.getConsole().printErrorText(e.getMessage());
        if (e.getCause().getMessage().contains("# methods")) {
          return DEX_METHOD_REFERENCE_OVERFLOW_EXIT_CODE;
        } else if (e.getCause().getMessage().contains("# fields")) {
          return DEX_FIELD_REFERENCE_OVERFLOW_EXIT_CODE;
        }
        throw new IOException(e);
      } else {
        postCompilationFailureToConsole(context, diagnosticsHandler);
        throw new IOException(e);
      }
    } catch (IOException e) {
      postCompilationFailureToConsole(context, diagnosticsHandler);
      throw e;
    }
  }

  private boolean isOverloadedDexException(CompilationFailedException e) {
    return e.getCause() instanceof AbortException
        && e.getCause().getMessage().contains("Cannot fit requested classes in a single dex file");
  }

  private void postCompilationFailureToConsole(
      IsolatedExecutionContext context, D8DiagnosticsHandler diagnosticsHandler) {
    context.postEvent(
        ConsoleEvent.severe(
            String.join(
                System.lineSeparator(),
                diagnosticsHandler.diagnostics.stream()
                    .map(Diagnostic::getDiagnosticMessage)
                    .collect(ImmutableList.toImmutableList()))));
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    ImmutableList.Builder<String> commandArgs =
        ImmutableList.builderWithExpectedSize(10 + filesToDex.size());

    commandArgs.add("d8");

    minSdkVersion.ifPresent(
        minApi -> {
          commandArgs.add("--min-api");
          commandArgs.add(Integer.toString(minApi));
        });

    primaryDexClassNamesPath.ifPresent(
        path -> {
          commandArgs.add("--main-dex-list");
          commandArgs.add(path.toString());
        });

    commandArgs.add("--output");
    commandArgs.add(filesystem.resolve(outputDexFile).toString());

    commandArgs.add(options.contains(Option.NO_OPTIMIZE) ? "--debug" : "--release");

    commandArgs.add("--lib").add(androidPlatformTarget.getAndroidJar().toString());

    if (options.contains(Option.NO_DESUGAR)) {
      commandArgs.add("--no-desugaring");
    }

    if (options.contains(Option.FORCE_JUMBO)) {
      commandArgs.add("--force-jumbo");
    }

    if (intermediate) {
      commandArgs.add("--intermediate");
    }

    for (Path fileToDex : filesToDex) {
      commandArgs.add(filesystem.resolve(fileToDex).toString());
    }

    return Joiner.on(" ").join(commandArgs.build());
  }

  @Override
  public String getShortName() {
    return "d8";
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

  private static class D8DiagnosticsHandler implements DiagnosticsHandler {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    @Override
    public void warning(Diagnostic warning) {
      diagnostics.add(warning);
    }

    @Override
    public void info(Diagnostic info) {}
  }

  public Path getOutputDexFile() {
    return outputDexFile;
  }

  public Set<Path> getFilesToDex() {
    return filesToDex;
  }
}
