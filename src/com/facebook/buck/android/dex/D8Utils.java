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
import com.facebook.buck.util.zip.ZipScrubber;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Runs d8. */
public class D8Utils {

  public static Collection<String> runD8Command(
      D8DiagnosticsHandler diagnosticsHandler,
      Path outputDexFile,
      Iterable<Path> filesToDex,
      Set<D8Options> options,
      Optional<Path> primaryDexClassNamesPath,
      Path androidJarPath,
      Collection<Path> classpathFiles,
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
      if (outputs != null && (outputs.length > 0)) {
        Files.move(outputs[0].toPath(), outputDexFile, StandardCopyOption.REPLACE_EXISTING);
      }
    }

    return d8Command.getDexItemFactory().computeReferencedResources();
  }

  static void writeSecondaryDexJarAndMetadataFile(
      Path secondaryDexOutputJarPath,
      Path secondaryDexOutputJarMetadataPath,
      Path rawSecondaryDexPath,
      String compression)
      throws IOException {
    try (OutputStream secondaryDexJar =
            new BufferedOutputStream(new FileOutputStream(secondaryDexOutputJarPath.toFile()));
        JarOutputStream jarOutputStream = new JarOutputStream(secondaryDexJar);
        InputStream secondaryDexInputStream =
            new BufferedInputStream(new FileInputStream(rawSecondaryDexPath.toFile()))) {
      if (compression.equals("xz") || compression.equals("xzs")) {
        jarOutputStream.setLevel(Deflater.NO_COMPRESSION);
      }
      jarOutputStream.putNextEntry(new ZipEntry("classes.dex"));
      ByteStreams.copy(secondaryDexInputStream, jarOutputStream);
      jarOutputStream.closeEntry();
    }

    ZipScrubber.scrubZip(secondaryDexOutputJarPath);

    writeSecondaryDexMetadata(secondaryDexOutputJarPath, secondaryDexOutputJarMetadataPath);
  }

  /**
   * Write a secondary dex jar metadata file. This is a .meta file with a single line containing:
   *
   * <p>jar:<size of secondary dex jar (in bytes)> dex:<size of uncompressed dex file (in bytes)>
   */
  static void writeSecondaryDexMetadata(
      Path secondaryDexOutputJarPath, Path secondaryDexOutputJarMetadataPath) throws IOException {
    try (ZipFile zf = new ZipFile(secondaryDexOutputJarPath.toFile())) {
      ZipEntry classesDexEntry = zf.getEntry("classes.dex");
      if (classesDexEntry == null) {
        throw new RuntimeException("could not find classes.dex in jar");
      }

      long uncompressedSize = classesDexEntry.getSize();
      if (uncompressedSize == -1) {
        throw new RuntimeException("classes.dex size should be known");
      }

      Files.write(
          secondaryDexOutputJarMetadataPath,
          Collections.singletonList(
              String.format(
                  "jar:%s dex:%s", Files.size(secondaryDexOutputJarPath), uncompressedSize)));
    }
  }

  /**
   * The secondary dex directory contains a single metadata.txt file which has one line per
   * secondary dex, consisting of:
   *
   * <p><secondary dex jar file name> <hash of secondary dex jar> <canary class>
   */
  static String getSecondaryDexJarMetadataString(
      Path secondaryDexOutputJarPath, String canaryClassName) throws IOException {
    return String.format(
        "%s %s %s",
        secondaryDexOutputJarPath.getFileName(),
        com.google.common.io.Files.hash(secondaryDexOutputJarPath.toFile(), Hashing.sha1())
            .toString(),
        canaryClassName);
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
