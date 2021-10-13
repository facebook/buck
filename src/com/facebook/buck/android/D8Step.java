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
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.utils.AbortException;
import com.facebook.buck.android.dex.D8Options;
import com.facebook.buck.android.dex.D8Utils;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Runs d8. */
public class D8Step extends IsolatedStep {

  public static final int SUCCESS_EXIT_CODE = 0;
  public static final int FAILURE_EXIT_CODE = 1;
  public static final int DEX_METHOD_REFERENCE_OVERFLOW_EXIT_CODE = 2;
  public static final int DEX_FIELD_REFERENCE_OVERFLOW_EXIT_CODE = 3;

  // TODO: Refactor project file system out of DxStep to make it a fully conforming isolated step
  private final ProjectFilesystem filesystem;
  // TODO: Refactor android platform target out of DxStep to make it a fully conforming isolated
  // step
  private final AndroidPlatformTarget androidPlatformTarget;
  @VisibleForTesting final @Nullable Collection<Path> classpathFiles;
  private final Path outputDexFile;
  private final Set<Path> filesToDex;
  private final Set<D8Options> options;
  private final Optional<Path> primaryDexClassNamesPath;
  // used to differentiate different dexing buckets (if any)
  private final Optional<String> bucketId;
  private final Optional<Integer> minSdkVersion;

  @Nullable private Collection<String> resourcesReferencedInCode;

  /**
   * @param outputDexFile path to the file where the generated classes.dex should go.
   * @param filesToDex each element in this set is a path to a .class file, a zip file of .class
   *     files, or a directory of .class files.
   * @param options to pass to {@code dx}.
   */
  public D8Step(
      ProjectFilesystem filesystem,
      AndroidPlatformTarget androidPlatformTarget,
      Path outputDexFile,
      Iterable<Path> filesToDex,
      EnumSet<D8Options> options) {
    this(
        filesystem,
        androidPlatformTarget,
        outputDexFile,
        filesToDex,
        options,
        Optional.empty(),
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
   * @param classpathFiles specifies classpath for interface static and default methods desugaring.
   * @param minSdkVersion
   */
  public D8Step(
      ProjectFilesystem filesystem,
      AndroidPlatformTarget androidPlatformTarget,
      Path outputDexFile,
      Iterable<Path> filesToDex,
      EnumSet<D8Options> options,
      Optional<Path> primaryDexClassNamesPath,
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
    this.bucketId = bucketId;
    this.minSdkVersion = minSdkVersion;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    return StepExecutionResult.of(executeInProcess(context));
  }

  private int executeInProcess(IsolatedExecutionContext context) throws IOException {
    D8Utils.D8DiagnosticsHandler diagnosticsHandler = new D8Utils.D8DiagnosticsHandler();
    try {
      resourcesReferencedInCode =
          D8Utils.runD8Command(
              diagnosticsHandler,
              outputDexFile,
              filesToDex.stream().map(filesystem::resolve).collect(Collectors.toList()),
              options,
              primaryDexClassNamesPath,
              androidPlatformTarget.getAndroidJar(),
              classpathFiles == null
                  ? null
                  : classpathFiles.stream()
                      .map(filesystem::getPathForRelativeExistingPath)
                      .collect(Collectors.toList()),
              bucketId,
              minSdkVersion);

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
      IsolatedExecutionContext context, D8Utils.D8DiagnosticsHandler diagnosticsHandler) {
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

    commandArgs.add(options.contains(D8Options.NO_OPTIMIZE) ? "--debug" : "--release");

    commandArgs.add("--lib").add(androidPlatformTarget.getAndroidJar().toString());

    if (options.contains(D8Options.NO_DESUGAR)) {
      commandArgs.add("--no-desugaring");
    }

    if (options.contains(D8Options.FORCE_JUMBO)) {
      commandArgs.add("--force-jumbo");
    }

    if (options.contains(D8Options.INTERMEDIATE)) {
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

  public Path getOutputDexFile() {
    return outputDexFile;
  }

  public Set<Path> getFilesToDex() {
    return filesToDex;
  }
}
