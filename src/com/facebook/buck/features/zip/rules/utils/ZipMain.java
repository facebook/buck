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

package com.facebook.buck.features.zip.rules.utils;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.sourcepath.resolver.impl.utils.RelativePathMapUtils;
import com.facebook.buck.util.zip.collect.OnDuplicateEntry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Main entry point for generating Zip files
 *
 * <p>Expected usage: {@code this_binary --output_path <output_path> --on_duplicate_entry
 * <on_duplicate_entry_value> --entries_file <file with (artifact + isSource flag) pairs>
 * --zip_sources <zip sources paths> --entries_to_exclude <entries_to_exclude patterns> } .
 */
public class ZipMain {

  private static final AbsPath CURRENT_DIRECTORY =
      AbsPath.of(Paths.get(".").normalize().toAbsolutePath());

  /** Main entry point into ZipFile binary */
  public static void main(String[] args) throws IOException {
    RelPath outputPath = null;
    OnDuplicateEntry onDuplicateEntry = null;
    ImmutableMap<RelPath, RelPath> entryMap = ImmutableMap.of();
    ImmutableList.Builder<RelPath> zipSources = ImmutableList.builder();
    ImmutableSet.Builder<Pattern> entriesToExclude = ImmutableSet.builder();

    for (int i = 0; i < args.length; ) {
      String argName = args[i];
      switch (argName) {
        case "--output_path":
          outputPath = RelPath.get(args[i + 1]);
          i = i + 2;
          break;

        case "--on_duplicate_entry":
          onDuplicateEntry = OnDuplicateEntry.valueOf(args[i + 1].toUpperCase());
          i = i + 2;
          break;

        case "--zip_sources":
          int zipSourcesIndex = i + 1;
          while (zipSourcesIndex < args.length && !args[zipSourcesIndex].startsWith("--")) {
            zipSources.add(RelPath.get(args[zipSourcesIndex++]));
          }
          i = zipSourcesIndex;
          break;

        case "--entries_to_exclude":
          int entriesToExcludeIndex = i + 1;
          while (entriesToExcludeIndex < args.length
              && !args[entriesToExcludeIndex].startsWith("--")) {
            entriesToExclude.add(Pattern.compile(args[entriesToExcludeIndex++]));
          }
          i = entriesToExcludeIndex;
          break;

        case "--entries_file":
          AbsPath entriesFilePath = CURRENT_DIRECTORY.resolve(args[i + 1]);
          List<String> entriesLines = Files.readAllLines(entriesFilePath.getPath());

          Map<Path, AbsPath> map = new LinkedHashMap<>();
          for (int lineIndex = 0; lineIndex < entriesLines.size(); lineIndex += 3) {
            String entry = entriesLines.get(lineIndex);
            AbsPath zipEntryAbsPath = CURRENT_DIRECTORY.resolve(entry);
            RelPath shortPath = RelPath.get(entriesLines.get(lineIndex + 1));
            boolean isSourceFlag = Boolean.parseBoolean(entriesLines.get(lineIndex + 2));

            if (isSourceFlag) {
              RelativePathMapUtils.addPathToRelativePathMap(
                  CURRENT_DIRECTORY,
                  map,
                  CURRENT_DIRECTORY.getPath(),
                  zipEntryAbsPath,
                  shortPath.getPath());
            } else {
              RelativePathMapUtils.addPathToRelativePathMap(
                  CURRENT_DIRECTORY,
                  map,
                  zipEntryAbsPath.getPath().getParent(),
                  zipEntryAbsPath,
                  zipEntryAbsPath.getFileName());
            }
          }
          entryMap = ZipUtils.toRelPathEntryMap(ImmutableMap.copyOf(map), CURRENT_DIRECTORY);

          i = i + 2;
          break;

        default:
          throw new IllegalStateException("Unrecognized argument: " + argName);
      }
    }

    ZipUtils.createZipFile(
        CURRENT_DIRECTORY,
        entryMap,
        zipSources.build(),
        entriesToExclude.build(),
        onDuplicateEntry,
        outputPath);

    System.exit(0);
  }
}
