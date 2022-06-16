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

package com.facebook.buck.android.resources.filter;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Predicate;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/** Entry point for filtering resources. */
public class FilterResourcesExecutableMain {
  @Option(name = "--in-res-dir-to-out-res-dir-map", required = true)
  private String inResDirToOutResDirMapPath;

  @Option(name = "--target-densities")
  private String targetDensities;

  @Option(name = "--enable-string-as-assets-filtering")
  private boolean enableStringsAsAssetsFiltering;

  @Option(name = "--not-filtered-string-dirs")
  private String notFilteredStringDirsFile;

  @Option(name = "--packaged-locales")
  private String packagedLocalesString;

  public static void main(String[] args) throws IOException {
    FilterResourcesExecutableMain main = new FilterResourcesExecutableMain();
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
    AbsPath root = AbsPath.of(Paths.get(".").normalize().toAbsolutePath());
    Map<String, ImmutableBiMap<Path, Path>> rawMap =
        ObjectMappers.READER.readValue(
            ObjectMappers.createParser(Paths.get(inResDirToOutResDirMapPath)),
            new TypeReference<Map<String, ImmutableBiMap<Path, Path>>>() {});
    ImmutableBiMap<Path, Path> inResDirToOutResDirMap = rawMap.get("res_dir_map");
    ImmutableSet<ResourceFilters.Density> targetDensitiesSet =
        targetDensities != null
            ? Arrays.stream(targetDensities.split(","))
                .map(ResourceFilters.Density::from)
                .collect(ImmutableSet.toImmutableSet())
            : ImmutableSet.of();
    ImmutableSet<String> packagedLocales =
        packagedLocalesString != null
            ? ImmutableSet.copyOf(packagedLocalesString.split(","))
            : ImmutableSet.of();
    ImmutableSet<Path> notFilteredStringDirs =
        notFilteredStringDirsFile != null
            ? Files.readAllLines(Paths.get(notFilteredStringDirsFile)).stream()
                .map(Paths::get)
                .collect(ImmutableSet.toImmutableSet())
            : ImmutableSet.of();

    Predicate<Path> filteringPredicate =
        FilteringPredicate.getFilteringPredicate(
            root,
            inResDirToOutResDirMap,
            !targetDensitiesSet.isEmpty(),
            targetDensitiesSet,
            // TODO(T122759074) Support these filters too
            /* canDownscale */ false,
            /* locales */ ImmutableSet.of("NONE"),
            /* packagedLocales */ packagedLocales,
            enableStringsAsAssetsFiltering,
            notFilteredStringDirs);

    FilteredDirectoryCopier.copyDirs(root, inResDirToOutResDirMap, filteringPredicate);

    System.exit(0);
  }
}
