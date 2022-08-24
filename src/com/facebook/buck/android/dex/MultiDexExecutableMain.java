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
import com.facebook.buck.android.apkmodule.APKModule;
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

  @Option(name = "--primary-dex")
  private String primaryDexString;

  @Option(name = "--secondary-dex-output-dir", required = true)
  private String secondaryDexOutputDirString;

  @Option(name = "--files-to-dex-list")
  private String filesToDexList;

  @Option(name = "--module", required = true)
  private String module;

  @Option(name = "--module-deps")
  private String moduleDepsPathString;

  @Option(name = "--canary-class-name", required = true)
  private String canaryClassName;

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

      Path primaryDexClassNamesPath = null;
      Preconditions.checkState(
          (primaryDexPatternsPathString != null) == APKModule.isRootModule(module));
      if (primaryDexPatternsPathString != null) {
        List<String> primaryDexPatterns =
            Files.readAllLines(Paths.get(primaryDexPatternsPathString));
        primaryDexClassNamesPath = Files.createTempFile("primary_dex_class_names", "txt");
        ProguardTranslatorFactory proguardTranslatorFactory =
            ProguardTranslatorFactory.create(
                Optional.ofNullable(proguardConfigurationFileString).map(Paths::get),
                Optional.ofNullable(proguardMappingFileString).map(Paths::get),
                false);
        Files.write(
            primaryDexClassNamesPath,
            getPrimaryDexClassNames(filesToDex, primaryDexPatterns, proguardTranslatorFactory));
      }

      Optional<Integer> minSdkVersion =
          Optional.ofNullable(minSdkVersionString).map(Integer::parseInt);

      try {
        D8Utils.runD8Command(
            new D8Utils.D8DiagnosticsHandler(),
            d8OutputDir,
            filesToDex,
            getD8Options(),
            Optional.ofNullable(primaryDexClassNamesPath),
            Paths.get(androidJar),
            ImmutableList.of(),
            minSdkVersion);
      } catch (CompilationFailedException e) {
        throw new IOException(e);
      }

      Preconditions.checkState((primaryDexString != null) == APKModule.isRootModule(module));
      if (primaryDexString != null) {
        Path createdPrimaryDex = d8OutputDir.resolve("classes.dex");
        Preconditions.checkState(Files.exists(createdPrimaryDex));
        Path primaryDexPath = Paths.get(primaryDexString);
        Files.move(createdPrimaryDex, primaryDexPath);
      }

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
    Path secondaryDexSubdir = secondaryDexOutputDir.resolve(getSecondaryDexSubDir(module));
    Files.createDirectories(secondaryDexSubdir);

    long secondaryDexCount = Files.list(rawSecondaryDexesDirPath).count();
    ImmutableList.Builder<String> metadataLines = ImmutableList.builder();
    metadataLines.add(String.format(".id %s", module));
    Preconditions.checkState((moduleDepsPathString == null) == APKModule.isRootModule(module));
    if (moduleDepsPathString != null) {
      metadataLines.addAll(
          Files.readAllLines(Paths.get(moduleDepsPathString)).stream()
              .map(moduleDep -> String.format(".requires %s", moduleDep))
              .collect(ImmutableList.toImmutableList()));
    }

    if (compression.equals("raw")) {
      if (APKModule.isRootModule(module)) {
        metadataLines.add(".root_relative");
      }
      for (int i = 0; i < secondaryDexCount; i++) {
        String secondaryDexName = getRawSecondaryDexName(module, i);
        Path secondaryDexSubDir = secondaryDexOutputDir.resolve(getRawSecondaryDexSubDir(module));
        Path movedDex = secondaryDexSubDir.resolve(secondaryDexName);
        Files.move(rawSecondaryDexesDirPath.resolve(secondaryDexName), movedDex);
        metadataLines.add(
            D8Utils.getSecondaryDexMetadataString(
                movedDex, String.format("%s.dex%d.Canary", canaryClassName, i + 1)));
      }
    } else {
      ImmutableList.Builder<Path> secondaryDexJarPaths = ImmutableList.builder();
      for (int i = 0; i < secondaryDexCount; i++) {
        String secondaryDexName = getRawSecondaryDexName(module, i);
        Path rawSecondaryDexPath = rawSecondaryDexesDirPath.resolve(secondaryDexName);
        Preconditions.checkState(
            Files.exists(rawSecondaryDexPath), "Expected file to exist at: " + rawSecondaryDexPath);
        Path secondaryDexOutputJarPath =
            compression.equals("xzs")
                ? secondaryDexSubdir.resolve(
                    String.format("%s.xzs.tmp~", getSecondaryDexJarName(module, i)))
                : secondaryDexSubdir.resolve(getSecondaryDexJarName(module, i));
        secondaryDexJarPaths.add(secondaryDexOutputJarPath);

        Path metadataPath =
            secondaryDexOutputJarPath.resolveSibling(
                secondaryDexOutputJarPath.getFileName() + ".meta");
        D8Utils.writeSecondaryDexJarAndMetadataFile(
            secondaryDexOutputJarPath, metadataPath, rawSecondaryDexPath, compression);

        Path secondaryDexOutput;
        if (compression.equals("xz")) {
          secondaryDexOutput = doXzCompression(secondaryDexOutputJarPath);
        } else {
          secondaryDexOutput = secondaryDexOutputJarPath;
        }

        metadataLines.add(
            D8Utils.getSecondaryDexMetadataString(
                secondaryDexOutput, String.format("%s.dex%d.Canary", canaryClassName, i + 1)));
      }

      if (compression.equals("xzs")) {
        doXzsCompression(secondaryDexSubdir, secondaryDexJarPaths.build());
      }
    }

    Files.write(secondaryDexSubdir.resolve("metadata.txt"), metadataLines.build());
  }

  private String getRawSecondaryDexSubDir(String module) {
    if (APKModule.isRootModule(module)) {
      return "";
    } else {
      return String.format("assets/%s", module);
    }
  }

  private String getRawSecondaryDexName(String module, int index) {
    if (APKModule.isRootModule(module)) {
      return String.format("classes%d.dex", index + 2);
    } else if (index == 0) {
      return "classes.dex";
    } else {
      return String.format("classes%d.dex", index + 1);
    }
  }

  private String getSecondaryDexSubDir(String module) {
    if (APKModule.isRootModule(module)) {
      return "assets/secondary-program-dex-jars";
    } else {
      return String.format("assets/%s", module);
    }
  }

  private String getSecondaryDexJarName(String module, int index) {
    return String.format(
        "%s-%d.dex.jar", APKModule.isRootModule(module) ? "secondary" : module, index + 1);
  }

  private Path doXzCompression(Path secondaryDexOutputJarPath) throws IOException {
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

    return xzCompressedOutputJarPath;
  }

  private void doXzsCompression(Path secondaryDexSubdir, ImmutableList<Path> secondaryDexJarPaths)
      throws IOException {
    try (OutputStream secondaryDexOutput =
            new BufferedOutputStream(
                new FileOutputStream(
                    secondaryDexSubdir
                        .resolve(
                            String.format(
                                "%s.dex.jar.xzs",
                                APKModule.isRootModule(module) ? "secondary" : module))
                        .toFile()));
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
