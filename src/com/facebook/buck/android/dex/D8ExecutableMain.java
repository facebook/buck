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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
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
  /** name suffix that identifies it as a Java class file. */
  private static final String CLASS_NAME_SUFFIX = ".class";

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

  @Option(name = "--min-sdk-version")
  private String minSdkVersionString;

  @Option(name = "--weight-estimate-path")
  private String weightEstimateOutput;

  @Option(name = "--class-names-path")
  private String classNamesOutput;

  /**
   * When using jar compression, the secondary dex directory consists of N secondary dex jars, each
   * of which has a corresponding .meta file (the secondaryDexMetadataFile) containing a single line
   * of the form:
   *
   * <p>jar:<size of secondary dex jar (in bytes)> dex:<size of uncompressed dex file (in bytes)>
   *
   * <p>It also contains a metadata.txt file, which consists on N lines, one for each secondary dex
   * jar. Those lines consist of:
   *
   * <p><secondary dex jar file name> <hash of secondary dex jar> <canary class>
   *
   * <p>We write the line that needs to be added to metadata.txt for this secondary dex jar to
   * secondaryDexMetadataLine, and we use the secondaryDexCanaryClassName for the <canary class>.
   */
  @Option(name = "--secondary-dex-compression")
  private String secondaryDexCompression;

  @Option(name = "--secondary-dex-metadata-file")
  private String secondaryDexMetadataFile;

  @Option(name = "--secondary-dex-metadata-line")
  private String secondaryDexMetadataLine;

  @Option(name = "--secondary-dex-canary-class-name")
  private String secondaryDexCanaryClassName;

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
    Optional<Path> classNamesPath = Optional.ofNullable(classNamesOutput).map(Paths::get);

    Path outputPath = Paths.get(outputDex);
    Path d8Output =
        primaryDexClassNamesPath.isPresent() ? Files.createTempDirectory("dexTmpDir") : outputPath;

    try {
      Collection<String> referencedResources =
          D8Utils.runD8Command(
              new D8Utils.D8DiagnosticsHandler(),
              d8Output,
              Optional.empty(),
              filesToDex,
              getD8Options(),
              primaryDexClassNamesPath,
              Paths.get(androidJar),
              classpathFiles,
              minSdkVersion);

      Preconditions.checkState(
          primaryDexClassNamesPath.isPresent() || secondaryDexCompression == null);
      if (primaryDexClassNamesPath.isPresent()) {
        Path classesDotDex = d8Output.resolve("classes.dex");
        Preconditions.checkState(
            classesDotDex.toFile().exists(), "D8 command must produce a classes.dex");

        if ("jar".equals(secondaryDexCompression)) {
          Preconditions.checkState(outputDex.endsWith(".dex.jar"));
          Preconditions.checkNotNull(secondaryDexMetadataFile);
          Path secondaryDexMetadataFilePath = Paths.get(secondaryDexMetadataFile);
          Preconditions.checkState(
              outputPath
                  .resolveSibling(outputPath.getFileName() + ".meta")
                  .toString()
                  .equals(secondaryDexMetadataFile));
          D8Utils.writeSecondaryDexJarAndMetadataFile(
              outputPath, secondaryDexMetadataFilePath, classesDotDex, "jar");

          Preconditions.checkNotNull(secondaryDexMetadataLine);
          Preconditions.checkNotNull(secondaryDexCanaryClassName);
          Files.write(
              Paths.get(secondaryDexMetadataLine),
              Collections.singletonList(
                  D8Utils.getSecondaryDexJarMetadataString(
                      outputPath, secondaryDexCanaryClassName)));
        } else {
          Preconditions.checkState(
              outputDex.endsWith(".dex"),
              String.format(
                  "Expect the outputDex to end with '.dex' if not '.dex.jar', but it is %s",
                  outputDex));
          Files.move(classesDotDex, outputPath);
        }
      }

      if (referencedResourcesPath.isPresent()) {
        Files.write(referencedResourcesPath.get(), referencedResources);
      }

      if (weightEstimatePath.isPresent() || classNamesPath.isPresent()) {
        int totalWeightEstimate = 0;
        ImmutableList.Builder<String> classNames = ImmutableList.builder();
        try (ZipFile zipFile = new ZipFile(Iterables.getOnlyElement(filesToDex).toFile())) {
          Enumeration<? extends ZipEntry> entries = zipFile.entries();
          while (entries.hasMoreElements()) {
            ZipEntry zipEntry = entries.nextElement();
            String zipEntryName = zipEntry.getName();
            // Ignore non-.class files.
            if (!zipEntryName.endsWith(CLASS_NAME_SUFFIX)) {
              continue;
            }

            classNames.add(
                zipEntryName.substring(0, zipEntryName.length() - CLASS_NAME_SUFFIX.length()));
            totalWeightEstimate += (int) zipEntry.getSize();
          }
        }
        if (weightEstimatePath.isPresent()) {
          Files.write(
              weightEstimatePath.get(),
              Collections.singletonList(Integer.toString(totalWeightEstimate)));
        }
        if (classNamesPath.isPresent()) {
          Files.write(classNamesPath.get(), classNames.build());
        }
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
    if (primaryDexClassNamesList != null) {
      // Only add the specified classes
      d8OptionsBuilder.add(D8Options.MINIMIZE_PRIMARY_DEX);
    }

    return d8OptionsBuilder.build();
  }
}
