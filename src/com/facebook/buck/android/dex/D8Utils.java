/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.android.dex;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.utils.InternalOptions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Runs d8. */
public class D8Utils {

  public static Collection<String> runD8Command(
      D8DiagnosticsHandler diagnosticsHandler,
      Path outputDexFile,
      Optional<Path> secondaryOutputDexFile,
      Iterable<Path> filesToDex,
      Set<D8Options> options,
      Optional<Path> primaryDexClassNamesPath,
      Path androidJarPath,
      Collection<Path> classpathFiles,
      Optional<String> bucketId,
      Optional<Integer> minSdkVersion)
      throws CompilationFailedException, IOException {
    Set<Path> inputs = new HashSet<>();
    for (Path toDex : filesToDex) {
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
    Preconditions.checkState(
        secondaryOutputDexFile.isEmpty() || primaryDexClassNamesPath.isPresent(),
        "Can only provide a secondaryOutputDexFile if a primaryDexClassNamesPath is provided!");
    Preconditions.checkState(
        secondaryOutputDexFile.isEmpty() || outputToDex,
        "If a secondaryDexOutputFile is provided, the outputDexFile must be a file and not a directory!");
    Path output = outputToDex ? Files.createTempDirectory("buck-d8") : outputDexFile;

    D8Command.Builder builder =
        D8Command.builder(diagnosticsHandler)
            .addProgramFiles(inputs)
            .setIntermediate(options.contains(D8Options.INTERMEDIATE))
            .addLibraryFiles(androidJarPath)
            .setMode(
                options.contains(D8Options.NO_OPTIMIZE)
                    ? CompilationMode.DEBUG
                    : CompilationMode.RELEASE)
            .setOutput(output, OutputMode.DexIndexed)
            .setDisableDesugaring(options.contains(D8Options.NO_DESUGAR))
            .setInternalOptionsModifier(
                (InternalOptions opt) -> {
                  opt.testing.forceJumboStringProcessing = options.contains(D8Options.FORCE_JUMBO);
                  if (options.contains(D8Options.MINIMIZE_PRIMARY_DEX)) {
                    opt.minimalMainDex = true;
                  } else if (options.contains(D8Options.MAXIMIZE_PRIMARY_DEX)) {
                    opt.minimalMainDex = false;
                  }
                });

    bucketId.ifPresent(builder::setBucketId);
    minSdkVersion.ifPresent(builder::setMinApiLevel);
    primaryDexClassNamesPath.ifPresent(builder::addMainDexListFiles);

    if (classpathFiles != null) {
      // classpathFiles is needed only for D8 Java 8 desugar
      builder.addClasspathFiles(classpathFiles);
    }

    D8Command d8Command = builder.build();
    com.android.tools.r8.D8.run(d8Command);

    if (outputToDex) {
      File[] outputs = output.toFile().listFiles();
      if (secondaryOutputDexFile.isPresent()) {
        movePrimaryAndSecondaryDex(
            outputs,
            outputDexFile,
            secondaryOutputDexFile.get(),
            diagnosticsHandler,
            options,
            androidJarPath,
            classpathFiles,
            bucketId,
            minSdkVersion);
      } else if (outputs != null && (outputs.length > 0)) {
        Files.move(outputs[0].toPath(), outputDexFile, StandardCopyOption.REPLACE_EXISTING);
      }
    }

    return d8Command.getDexItemFactory().computeReferencedResources();
  }

  private static void movePrimaryAndSecondaryDex(
      File[] outputs,
      Path outputDexFile,
      Path secondaryOutputDexFile,
      D8DiagnosticsHandler diagnosticsHandler,
      Set<D8Options> options,
      Path androidJarPath,
      Collection<Path> classpathFiles,
      Optional<String> bucketId,
      Optional<Integer> minSdkVersion)
      throws IOException, CompilationFailedException {
    Preconditions.checkState(outputs != null);
    Preconditions.checkState(
        outputs.length == 2,
        "Expected one primary and one secondary dex class, but actually got "
            + outputs.length
            + " dex classes.");
    File primaryOutput, secondaryOutput;
    if (outputs[0].toPath().toString().endsWith("classes.dex")) {
      primaryOutput = outputs[0];
      secondaryOutput = outputs[1];
    } else {
      Preconditions.checkState(
          outputs[1].toPath().toString().endsWith("classes.dex"),
          "Expected one of the outputs to end with `classes.dex`, but they were actually: "
              + outputs[0].toPath().toString()
              + " "
              + outputs[1].toPath().toString());
      primaryOutput = outputs[1];
      secondaryOutput = outputs[0];
    }
    Files.move(primaryOutput.toPath(), outputDexFile, StandardCopyOption.REPLACE_EXISTING);
    if (secondaryOutputDexFile.toString().endsWith(".dex")) {
      // Raw dex file, just copy the result over.
      Files.move(
          secondaryOutput.toPath(), secondaryOutputDexFile, StandardCopyOption.REPLACE_EXISTING);
    } else {
      runD8Command(
          diagnosticsHandler,
          secondaryOutputDexFile,
          Optional.empty(),
          ImmutableSet.of(secondaryOutput.toPath()),
          options,
          Optional.empty(),
          androidJarPath,
          classpathFiles,
          bucketId,
          minSdkVersion);
    }
  }

  public static class D8DiagnosticsHandler implements DiagnosticsHandler {

    public final List<Diagnostic> diagnostics = new ArrayList<>();

    @Override
    public void warning(Diagnostic warning) {
      diagnostics.add(warning);
    }

    @Override
    public void info(Diagnostic info) {}
  }
}
