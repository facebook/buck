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
import com.facebook.buck.android.proguard.ProguardTranslatorFactory;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZ;
import org.tukaani.xz.XZOutputStream;

/** Executable for creating multiple DEX files from library jars. */
public class MultiDexExecutableMain {
  /** name suffix that identifies it as a Java class file. */
  private static final String CLASS_NAME_SUFFIX = ".class";

  private static final String SECONDARY_DEX_SUBDIR = "assets/secondary-program-dex-jars";

  @Option(name = "--primary-dex")
  private String primaryDexString;

  @Option(name = "--secondary-dex-output-dir", required = true)
  private String secondaryDexOutputDirString;

  @Option(name = "--files-to-dex-list")
  private String filesToDexList;

  @Option(name = "--android-jar")
  private String androidJar;

  @Option(name = "--primary-dex-patterns-path")
  private String primaryDexPatternsPathString;

  @Option(name = "--raw-secondary-dexes-dir")
  private String rawSecondaryDexesDir;

  @Option(name = "--compression", required = true)
  private String compression;

  @Option(name = "--proguard-configuration-file")
  private String proguardConfigurationFileString;

  @Option(name = "--proguard-mapping-file")
  private String proguardMappingFileString;

  @Option(name = "--min-sdk-version")
  private String minSdkVersionString;

  @Option(name = "--no-optimize")
  private boolean noOptimize = false;

  @Option(name = "--minimize-primary-dex")
  private boolean minimizePrimaryDex = false;

  @Option(name = "--xz-compression-level")
  private int xzCompressionLevel = -1;

  public static void main(String[] args) throws IOException {
    MultiDexExecutableMain main = new MultiDexExecutableMain();
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
    Path rawSecondaryDexesDirPath;
    if (rawSecondaryDexesDir == null) {
      Path d8OutputDir = Files.createTempDirectory("d8_output_dir");
      ImmutableSet<Path> filesToDex =
          Files.readAllLines(Paths.get(filesToDexList)).stream()
              .map(Paths::get)
              .collect(ImmutableSet.toImmutableSet());

      List<String> primaryDexPatterns = Files.readAllLines(Paths.get(primaryDexPatternsPathString));
      Path primaryDexClassNamesPath = Files.createTempFile("primary_dex_class_names", "txt");
      ProguardTranslatorFactory proguardTranslatorFactory =
          ProguardTranslatorFactory.create(
              Optional.ofNullable(proguardConfigurationFileString).map(Paths::get),
              Optional.ofNullable(proguardMappingFileString).map(Paths::get),
              false);
      Files.write(
          primaryDexClassNamesPath,
          getPrimaryDexClassNames(filesToDex, primaryDexPatterns, proguardTranslatorFactory));

      Optional<Integer> minSdkVersion =
          Optional.ofNullable(minSdkVersionString).map(Integer::parseInt);

      try {
        D8Utils.runD8Command(
            new D8Utils.D8DiagnosticsHandler(),
            d8OutputDir,
            Optional.empty(),
            filesToDex,
            getD8Options(),
            Optional.of(primaryDexClassNamesPath),
            Paths.get(androidJar),
            ImmutableList.of(),
            minSdkVersion);
      } catch (CompilationFailedException e) {
        throw new IOException(e);
      }

      Path createdPrimaryDex = d8OutputDir.resolve("classes.dex");
      Preconditions.checkState(Files.exists(createdPrimaryDex));
      Path primaryDexPath = Paths.get(primaryDexString);
      Files.move(createdPrimaryDex, primaryDexPath);

      rawSecondaryDexesDirPath = d8OutputDir;
    } else {
      rawSecondaryDexesDirPath = Paths.get(rawSecondaryDexesDir);
    }

    postprocessSecondaryDexFiles(rawSecondaryDexesDirPath);

    System.exit(0);
  }

  private void postprocessSecondaryDexFiles(Path rawSecondaryDexesDirPath) throws IOException {
    Preconditions.checkState(
        ImmutableList.of("raw", "jar", "xz", "xzs").contains(compression),
        "Only raw, jar, xz and xzs compression is supported!");
    Preconditions.checkState(
        compression.equals("raw") || compression.equals("jar") || xzCompressionLevel != -1,
        "Must specify a valid compression level when xz or xzs compression is used!");

    Path secondaryDexOutputDir = Paths.get(secondaryDexOutputDirString);
    Files.createDirectories(secondaryDexOutputDir);

    if (compression.equals("raw")) {
      Files.list(rawSecondaryDexesDirPath)
          .forEach(
              path -> {
                try {
                  Files.move(path, secondaryDexOutputDir.resolve(path.getFileName()));
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    } else {
      Path secondaryDexSubdir = secondaryDexOutputDir.resolve(SECONDARY_DEX_SUBDIR);
      Files.createDirectories(secondaryDexSubdir);

      long secondaryDexCount = Files.list(rawSecondaryDexesDirPath).count();
      ImmutableList.Builder<String> metadataLines = ImmutableList.builder();
      ImmutableList.Builder<Path> secondaryDexJarPaths = ImmutableList.builder();
      for (int i = 0; i < secondaryDexCount; i++) {
        String secondaryDexName = String.format("classes%s.dex", i + 2);
        Path rawSecondaryDexPath = rawSecondaryDexesDirPath.resolve(secondaryDexName);
        Preconditions.checkState(Files.exists(rawSecondaryDexPath));
        Path secondaryDexOutputJarPath =
            compression.equals("xzs")
                ? secondaryDexSubdir.resolve(String.format("secondary-%s.dex.jar.xzs.tmp~", i + 1))
                : secondaryDexSubdir.resolve(String.format("secondary-%s.dex.jar", i + 1));
        secondaryDexJarPaths.add(secondaryDexOutputJarPath);

        Path metadataPath =
            secondaryDexOutputJarPath.resolveSibling(
                secondaryDexOutputJarPath.getFileName() + ".meta");
        D8Utils.writeSecondaryDexJarAndMetadataFile(
            secondaryDexOutputJarPath, metadataPath, rawSecondaryDexPath, compression);

        metadataLines.add(
            D8Utils.getSecondaryDexJarMetadataString(
                secondaryDexOutputJarPath, String.format("secondary.dex%d.Canary", i + 1)));
        if (compression.equals("xz")) {
          doXzCompression(secondaryDexOutputJarPath);
        }
      }

      if (compression.equals("xzs")) {
        doXzsCompression(secondaryDexSubdir, secondaryDexJarPaths.build());
      }

      Files.write(secondaryDexSubdir.resolve("metadata.txt"), metadataLines.build());
    }
  }

  private void doXzCompression(Path secondaryDexOutputJarPath) throws IOException {
    Path xzCompressedOutputJarPath =
        secondaryDexOutputJarPath.resolveSibling(secondaryDexOutputJarPath.getFileName() + ".xz");

    try (InputStream in =
            new BufferedInputStream(new FileInputStream(secondaryDexOutputJarPath.toFile()));
        OutputStream out =
            new BufferedOutputStream(new FileOutputStream(xzCompressedOutputJarPath.toFile()));
        XZOutputStream xzOut =
            new XZOutputStream(out, new LZMA2Options(xzCompressionLevel), XZ.CHECK_CRC32)) {
      ByteStreams.copy(in, xzOut);
    }

    Files.delete(secondaryDexOutputJarPath);
  }

  private void doXzsCompression(Path secondaryDexSubdir, ImmutableList<Path> secondaryDexJarPaths)
      throws IOException {
    try (OutputStream secondaryDexOutput =
            new BufferedOutputStream(
                new FileOutputStream(
                    secondaryDexSubdir.resolve("secondary.dex.jar.xzs").toFile()));
        XZOutputStream xzOutputStream =
            new XZOutputStream(
                secondaryDexOutput, new LZMA2Options(xzCompressionLevel), XZ.CHECK_CRC32)) {
      for (Path secondaryDexJarPath : secondaryDexJarPaths) {
        try (InputStream secondaryDexInputStream =
            new BufferedInputStream(new FileInputStream(secondaryDexJarPath.toFile()))) {
          ByteStreams.copy(secondaryDexInputStream, xzOutputStream);
        }
      }
    }
  }

  private ImmutableList<String> getPrimaryDexClassNames(
      ImmutableSet<Path> filesToDex,
      List<String> primaryDexPatterns,
      ProguardTranslatorFactory proguardTranslatorFactory)
      throws IOException {
    ClassNameFilter primaryDexClassNameFilter =
        ClassNameFilter.fromConfiguration(primaryDexPatterns);
    ImmutableList.Builder<String> primaryDexClassNames = ImmutableList.builder();
    Function<String, String> deobfuscateFunction =
        proguardTranslatorFactory.createDeobfuscationFunction();

    for (Path fileToDex : filesToDex) {
      try (ZipFile zipFile = new ZipFile(fileToDex.toFile())) {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
          ZipEntry zipEntry = entries.nextElement();
          String zipEntryName = zipEntry.getName();
          // Ignore non-.class files.
          if (!zipEntryName.endsWith(CLASS_NAME_SUFFIX)) {
            continue;
          }

          String className =
              Objects.requireNonNull(
                  deobfuscateFunction.apply(
                      zipEntryName.substring(
                          0, zipEntryName.length() - CLASS_NAME_SUFFIX.length())));
          if (primaryDexClassNameFilter.matches(className)) {
            primaryDexClassNames.add(zipEntryName);
          }
        }
      }
    }

    return primaryDexClassNames.build();
  }

  private Set<D8Options> getD8Options() {
    ImmutableSet.Builder<D8Options> d8OptionsBuilder = ImmutableSet.builder();
    if (noOptimize) {
      d8OptionsBuilder.add(D8Options.NO_OPTIMIZE);
    }
    if (minimizePrimaryDex) {
      d8OptionsBuilder.add(D8Options.MINIMIZE_PRIMARY_DEX);
    } else {
      d8OptionsBuilder.add(D8Options.MAXIMIZE_PRIMARY_DEX);
    }

    return d8OptionsBuilder.build();
  }
}
