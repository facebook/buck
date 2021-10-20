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
import java.util.logging.Logger; // NOPMD
import java.util.stream.Collectors;

/**
 * Main entry point for executing {@link MergeAndroidResources} calls.
 *
 * <p>Expected usage: {@code this_binary <symbol_file_info> <output_dir> <output_files_path>}.
 */
public class MergeAndroidResourcesExecutableMain {

  private static final Logger LOG =
      Logger.getLogger(MergeAndroidResourcesExecutableMain.class.getName());

  public static void main(String[] args) throws IOException {
    if (args.length < 3) {
      LOG.severe(
          "Must specify the following mandatory arguments:\n"
              + "symbol_file_info output_dir output_files\n");
      System.exit(1);
    }

    ImmutableMap.Builder<Path, String> symbolsFileToRDotJavaPackage = ImmutableMap.builder();
    ImmutableMap.Builder<Path, String> symbolsFileToTargetName = ImmutableMap.builder();

    Path symbolsFileInfo = Paths.get(args[0]);
    for (String line : Files.readAllLines(symbolsFileInfo)) {
      String[] parts = line.split(" ");
      Preconditions.checkState(parts.length == 3);
      Path symbolsFilePath = Paths.get(parts[0]);
      Path rDotJavaPackageFilePath = Paths.get(parts[1]);
      String rDotJavaPackage = new String(Files.readAllBytes(rDotJavaPackageFilePath));
      symbolsFileToRDotJavaPackage.put(symbolsFilePath, rDotJavaPackage);
      symbolsFileToTargetName.put(symbolsFilePath, parts[2]);
    }

    Path outputDir = Paths.get(args[1]);

    String outputFiles = args[2];

    boolean forceFinalResourceIds = false;
    ImmutableList<Path> uberRDotTxt = ImmutableList.of();
    EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes =
        EnumSet.noneOf(RDotTxtEntry.RType.class);
    ImmutableList<Path> overrideSymbols = ImmutableList.of();
    Optional<Path> duplicateResourceWhitelistPath = Optional.empty();
    Optional<String> unionPackage = Optional.empty();

    for (int argsIndex = 3; argsIndex < args.length; argsIndex += 2) {
      String arg = args[argsIndex];

      switch (arg) {
        case "--force-final-resource-ids":
          forceFinalResourceIds = Boolean.parseBoolean(args[argsIndex + 1]);
          break;
        case "--uber-r-dot-txt":
          uberRDotTxt =
              Files.readAllLines(Paths.get(args[argsIndex + 1])).stream()
                  .map(Paths::get)
                  .collect(ImmutableList.toImmutableList());
          break;
        case "--banned-duplicate-resource-types":
          bannedDuplicateResourceTypes =
              EnumSet.copyOf(
                  Files.readAllLines(Paths.get(args[argsIndex + 1])).stream()
                      .map(RDotTxtEntry.RType::valueOf)
                      .collect(Collectors.toList()));
          break;
        case "--override-symbols":
          overrideSymbols =
              Files.readAllLines(Paths.get(args[argsIndex + 1])).stream()
                  .map(Paths::get)
                  .collect(ImmutableList.toImmutableList());
          break;
        case "--duplicate-resource-whitelist-path":
          duplicateResourceWhitelistPath = Optional.of(Paths.get(args[argsIndex + 1]));
          break;
        case "--union-package":
          unionPackage = Optional.of(args[argsIndex + 1]);
          break;
        default:
          throw new RuntimeException("Unknown arg: " + arg);
      }
    }

    try {
      MergeAndroidResources.mergeAndroidResources(
          uberRDotTxt,
          symbolsFileToRDotJavaPackage.build(),
          symbolsFileToTargetName.build(),
          forceFinalResourceIds,
          bannedDuplicateResourceTypes,
          duplicateResourceWhitelistPath,
          unionPackage,
          overrideSymbols,
          outputDir);
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
