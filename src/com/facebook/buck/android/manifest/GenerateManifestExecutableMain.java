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

package com.facebook.buck.android.manifest;

import com.android.common.utils.StdLogger;
import com.facebook.buck.util.ThrowingPrintWriter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/**
 * Main entry point for executing {@link GenerateManifest} calls.
 *
 * <p>Expected usage: {@code this_binary <skeleton_manifest_path> <module_name>
 * <library_manifest_paths_file> <placeholder_entries_file> <output_path> <merge_report_path} .
 */
public class GenerateManifestExecutableMain {
  @Option(name = "--skeleton-manifest", required = true)
  private String skeletonManifest;

  @Option(name = "--module-name", required = true)
  private String moduleName;

  @Option(name = "--library-manifests-list", required = true)
  private String libraryManifestsList;

  @Option(name = "--placeholder-entries-list", required = true)
  private String placeholderEntriesList;

  @Option(name = "--output", required = true)
  private String output;

  @Option(name = "--merge-report", required = true)
  private String mergeReport;

  public static void main(String[] args) throws IOException {
    GenerateManifestExecutableMain main = new GenerateManifestExecutableMain();
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
    ImmutableSet<Path> libraryManifestsFilePaths =
        Files.readAllLines(Paths.get(libraryManifestsList)).stream()
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());

    ImmutableMap<String, String> placeholderEntries =
        Files.readAllLines(Paths.get(placeholderEntriesList)).stream()
            .map(s -> s.split(" "))
            .collect(ImmutableMap.toImmutableMap(arr -> arr[0], arr -> arr[1]));

    Path outputPath = Paths.get(output);

    String xmlText =
        GenerateManifest.generateXml(
            Paths.get(skeletonManifest),
            moduleName,
            libraryManifestsFilePaths,
            placeholderEntries,
            outputPath,
            Paths.get(mergeReport),
            new StdLogger(StdLogger.Level.ERROR));

    try (ThrowingPrintWriter writer =
        new ThrowingPrintWriter(new FileOutputStream(outputPath.toFile()))) {
      writer.printf(xmlText);
    }

    System.exit(0);
  }
}
