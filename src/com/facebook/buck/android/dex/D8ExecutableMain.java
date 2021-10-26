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
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/** Main entry point for executing {@link com.android.tools.r8.D8Command} calls. */
public class D8ExecutableMain {
  @Option(name = "--output-dex-file", required = true)
  private String outputDex;

  @Option(name = "--files-to-dex-list", required = true)
  private String filesToDexList;

  @Option(name = "--android-jar", required = true)
  private String androidJar;

  @Option(name = "--intermediate")
  private boolean intermediate = false;

  @Option(name = "--no-desugar")
  private boolean noDesugar = false;

  @Option(name = "--no-optimize")
  private boolean noOptimize = false;

  @Option(name = "--force-jumbo")
  private boolean forceJumbo = false;

  @Option(name = "--primary-dex-class-names-path")
  private String primaryDexClassNamesList;

  @Option(name = "--referenced-resources-path")
  private String referencedResourcesList;

  @Option(name = "--classpath-files")
  private String classpathFilesList;

  @Option(name = "--bucket-id")
  private String bucketIdString;

  @Option(name = "--min-sdk-version")
  private String minSdkVersionString;

  @Option(name = "--weight-estimate-path")
  private String weightEstimateOutput;

  public static void main(String[] args) throws IOException {
    D8ExecutableMain main = new D8ExecutableMain();
    CmdLineParser parser = new CmdLineParser(main);
    try {
      parser.parseArgument(args);
      main.run();
      System.exit(0);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.exit(1);
    }
  }

  private void run() throws IOException {
    ImmutableSet<Path> filesToDex =
        Files.readAllLines(Paths.get(filesToDexList)).stream()
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());

    Optional<Path> primaryDexClassNamesPath =
        Optional.ofNullable(primaryDexClassNamesList).map(Paths::get);
    Optional<Path> referencedResourcesPath =
        Optional.ofNullable(referencedResourcesList).map(Paths::get);

    ImmutableSet<Path> classpathFiles =
        classpathFilesList == null
            ? ImmutableSet.of()
            : Files.readAllLines(Paths.get(classpathFilesList)).stream()
                .map(Paths::get)
                .collect(ImmutableSet.toImmutableSet());

    Optional<Integer> minSdkVersion =
        Optional.ofNullable(minSdkVersionString).map(Integer::parseInt);
    Optional<Path> weightEstimatePath = Optional.ofNullable(weightEstimateOutput).map(Paths::get);
    Optional<String> bucketId = Optional.ofNullable(bucketIdString);

    try {
      Collection<String> referencedResources =
          D8Utils.runD8Command(
              new D8Utils.D8DiagnosticsHandler(),
              Paths.get(outputDex),
              filesToDex,
              getD8Options(),
              primaryDexClassNamesPath,
              Paths.get(androidJar),
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

  private Set<D8Options> getD8Options() {
    ImmutableSet.Builder<D8Options> d8OptionsBuilder = ImmutableSet.builder();
    if (intermediate) {
      d8OptionsBuilder.add(D8Options.INTERMEDIATE);
    }
    if (noOptimize) {
      d8OptionsBuilder.add(D8Options.NO_OPTIMIZE);
    }
    if (forceJumbo) {
      d8OptionsBuilder.add(D8Options.FORCE_JUMBO);
    }
    if (noDesugar) {
      d8OptionsBuilder.add(D8Options.NO_DESUGAR);
    }

    return d8OptionsBuilder.build();
  }
}
