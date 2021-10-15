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

package com.facebook.buck.android.dex;

import com.android.tools.r8.CompilationFailedException;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Optional;
import java.util.logging.Logger; // NOPMD
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Main entry point for executing {@link com.android.tools.r8.D8Command} calls.
 *
 * <p>Expected usage: {@code this_binary <output_dex_file> <files_to_dex_path> <android_jar_path>
 * options}.
 */
public class D8ExecutableMain {

  private static final Logger LOG = Logger.getLogger(D8ExecutableMain.class.getName());

  public static void main(String[] args) throws IOException {
    if (args.length < 3) {
      LOG.severe(
          "Must specify an output dex file, a file containing the files to dex, and an android jar path, plus any other optional parameters");
      System.exit(1);
    }

    Path outputDexFile = Paths.get(args[0]);
    Path filesToDexPath = Paths.get(args[1]);
    ImmutableSet<Path> filesToDex =
        Files.readAllLines(filesToDexPath).stream()
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());
    Path androidJarPath = Paths.get(args[2]);

    Optional<Path> primaryDexClassNamesPath = Optional.empty();
    Optional<Path> referencedResourcesPath = Optional.empty();
    ImmutableSet.Builder<D8Options> d8OptionsBuilder = ImmutableSet.builder();
    Collection<Path> classpathFiles = null;
    Optional<String> bucketId = Optional.empty();
    Optional<Integer> minSdkVersion = Optional.empty();
    Optional<Path> weightEstimatePath = Optional.empty();

    for (int argsConsumed = 3; argsConsumed < args.length; argsConsumed++) {
      String arg = args[argsConsumed];

      switch (arg) {
        case "--intermediate":
          d8OptionsBuilder.add(D8Options.INTERMEDIATE);
          break;
        case "--no-optimize":
          d8OptionsBuilder.add(D8Options.NO_OPTIMIZE);
          break;
        case "--force-jumbo":
          d8OptionsBuilder.add(D8Options.FORCE_JUMBO);
          break;
        case "--no-desugar":
          d8OptionsBuilder.add(D8Options.NO_DESUGAR);
          break;
        case "--primary-dex-class-names-path":
          primaryDexClassNamesPath = Optional.of(Paths.get(args[++argsConsumed]));
          break;
        case "--referenced-resources-path":
          referencedResourcesPath = Optional.of(Paths.get(args[++argsConsumed]));
          break;
        case "--classpathFiles":
          Path classpathFilesPath = Paths.get(args[++argsConsumed]);
          classpathFiles =
              Files.readAllLines(classpathFilesPath).stream()
                  .map(Paths::get)
                  .collect(ImmutableSet.toImmutableSet());
          break;
        case "--bucket-id":
          bucketId = Optional.of(args[++argsConsumed]);
          break;
        case "--min-sdk-version":
          minSdkVersion = Optional.of(Integer.parseInt(args[++argsConsumed]));
          break;
        case "--weight-estimate-path":
          weightEstimatePath = Optional.of(Paths.get(args[++argsConsumed]));
          break;
        default:
          throw new RuntimeException("Unknown arg: " + arg);
      }
    }

    try {
      Collection<String> referencedResources =
          D8Utils.runD8Command(
              new D8Utils.D8DiagnosticsHandler(),
              outputDexFile,
              filesToDex,
              d8OptionsBuilder.build(),
              primaryDexClassNamesPath,
              androidJarPath,
              classpathFiles,
              bucketId,
              minSdkVersion);

      if (referencedResourcesPath.isPresent()) {
        Files.write(referencedResourcesPath.get(), referencedResources);
      }

      if (weightEstimatePath.isPresent()) {
        int totalWeightEstimate = 0;
        try (ZipFile zipFile = new ZipFile(Iterables.getOnlyElement(filesToDex).toFile())) {
          Enumeration<? extends ZipEntry> entries = zipFile.entries();
          while (entries.hasMoreElements()) {
            ZipEntry zipEntry = entries.nextElement();
            // Ignore non-.class files.
            if (!zipEntry.getName().endsWith(".class")) {
              continue;
            }

            totalWeightEstimate += (int) zipEntry.getSize();
          }
        }
        Files.write(
            weightEstimatePath.get(),
            Collections.singletonList(Integer.toString(totalWeightEstimate)));
      }
    } catch (CompilationFailedException e) {
      throw new IOException(e);
    }

    System.exit(0);
  }
}
