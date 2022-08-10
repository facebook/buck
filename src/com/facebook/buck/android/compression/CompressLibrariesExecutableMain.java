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

package com.facebook.buck.android.compression;

import com.google.common.collect.ImmutableList;
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
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZ;
import org.tukaani.xz.XZOutputStream;

/** Main entry point for compressing libraries into a single file. */
public class CompressLibrariesExecutableMain {
  @Option(name = "--libraries", required = true)
  private String librariesList;

  @Option(name = "--output-dir", required = true)
  private String outputDirString;

  @Option(name = "--xz-compression-level")
  private int xzCompressionLevel = -1;

  public static void main(String[] args) throws IOException {
    CompressLibrariesExecutableMain main = new CompressLibrariesExecutableMain();
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
    ImmutableList<Path> libraries =
        Files.readAllLines(Paths.get(librariesList)).stream()
            .map(Paths::get)
            .collect(ImmutableList.toImmutableList());
    Path outputDir = Paths.get(outputDirString);
    Files.createDirectories(outputDir);
    doXzsCompression(outputDir, libraries);
  }

  private void doXzsCompression(Path outputDir, ImmutableList<Path> libraries) throws IOException {
    try (OutputStream xzOutput =
            new BufferedOutputStream(new FileOutputStream(outputDir.resolve("libs.xzs").toFile()));
        XZOutputStream xzOutputStream =
            new XZOutputStream(xzOutput, new LZMA2Options(xzCompressionLevel), XZ.CHECK_CRC32)) {
      for (Path library : libraries) {
        try (InputStream libraryInputStream =
            new BufferedInputStream(new FileInputStream(library.toFile()))) {
          ByteStreams.copy(libraryInputStream, xzOutputStream);
        }
      }
    }
  }
}
