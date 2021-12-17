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

package com.facebook.buck.android.resources;

import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.util.ThrowingPrintWriter;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Optional;
import java.util.stream.Collectors;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/** Main entry point for executing {@link MergeAndroidResources} calls. */
public class MergeAndroidResourcesExecutableMain {
  @Option(name = "--symbol-file-info", required = true)
  private String symbolFileInfo;

  @Option(name = "--output-dir", required = true)
  private String outputDirString;

  @Option(name = "--output-files", required = true)
  private String outputFiles;

  @Option(name = "--force-final-resource-ids")
  private boolean forceFinalResourceIds = false;

  @Option(name = "--uber-r-dot-txt")
  private String uberRDotTxtFilesList;

  @Option(name = "--banned-duplicate-resource-types")
  private String bannedDuplicateResourceTypesList;

  @Option(name = "--override-symbols")
  private String overrideSymbolsList;

  @Option(name = "--duplicate-resource-allowlist-path")
  private String duplicateResourceAllowlist;

  @Option(name = "--union-package")
  private String unionPackageString;

  @Option(name = "--referenced-resources-lists")
  private String referencedResourcesLists;

  public static void main(String[] args) throws IOException {
    MergeAndroidResourcesExecutableMain main = new MergeAndroidResourcesExecutableMain();
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
    ImmutableMap.Builder<Path, String> symbolsFileToRDotJavaPackage = ImmutableMap.builder();
    ImmutableMap.Builder<Path, String> symbolsFileToTargetName = ImmutableMap.builder();

    for (String line : Files.readAllLines(Paths.get(symbolFileInfo))) {
      String[] parts = line.split(" ");
      Preconditions.checkState(parts.length == 3);
      Path symbolsFilePath = Paths.get(parts[0]);
      Path rDotJavaPackageFilePath = Paths.get(parts[1]);
      String rDotJavaPackage = new String(Files.readAllBytes(rDotJavaPackageFilePath));
      symbolsFileToRDotJavaPackage.put(symbolsFilePath, rDotJavaPackage);
      symbolsFileToTargetName.put(symbolsFilePath, parts[2]);
    }

    ImmutableList<Path> uberRDotTxt =
        uberRDotTxtFilesList != null
            ? Files.readAllLines(Paths.get(uberRDotTxtFilesList)).stream()
                .map(Paths::get)
                .collect(ImmutableList.toImmutableList())
            : ImmutableList.of();
    EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes =
        bannedDuplicateResourceTypesList != null
            ? EnumSet.copyOf(
                Files.readAllLines(Paths.get(bannedDuplicateResourceTypesList)).stream()
                    .map(RDotTxtEntry.RType::valueOf)
                    .collect(Collectors.toList()))
            : EnumSet.noneOf(RDotTxtEntry.RType.class);
    ImmutableList<Path> overrideSymbols =
        overrideSymbolsList != null
            ? Files.readAllLines(Paths.get(overrideSymbolsList)).stream()
                .map(Paths::get)
                .collect(ImmutableList.toImmutableList())
            : ImmutableList.of();
    Optional<Path> duplicateResourceAllowlistPath =
        Optional.ofNullable(duplicateResourceAllowlist).map(Paths::get);
    Optional<String> unionPackage = Optional.ofNullable(unionPackageString);

    Path outputDir = Paths.get(outputDirString);

    ImmutableList.Builder<String> referencedResources = ImmutableList.builder();
    if (referencedResourcesLists != null) {
      for (String referencedResourcesList :
          Files.readAllLines(Paths.get(referencedResourcesLists))) {
        referencedResources.addAll(Files.readAllLines(Paths.get(referencedResourcesList)));
      }
    }

    try {
      MergeAndroidResources.mergeAndroidResources(
          uberRDotTxt,
          symbolsFileToRDotJavaPackage.build(),
          symbolsFileToTargetName.build(),
          forceFinalResourceIds,
          bannedDuplicateResourceTypes,
          duplicateResourceAllowlistPath,
          unionPackage,
          overrideSymbols,
          outputDir,
          referencedResources.build());
    } catch (MergeAndroidResources.DuplicateResourceException e) {
      throw new RuntimeException(e);
    }

    try (ThrowingPrintWriter writer = new ThrowingPrintWriter(new FileOutputStream(outputFiles))) {
      for (String pkg : symbolsFileToRDotJavaPackage.build().values()) {
        writer.printf("%s\n", MergeAndroidResources.getPathToRDotJava(outputDir, pkg));
      }

      if (unionPackage.isPresent()) {
        writer.printf(
            "%s\n", MergeAndroidResources.getPathToRDotJava(outputDir, unionPackage.get()));
      }
    }

    System.exit(0);
  }
}
