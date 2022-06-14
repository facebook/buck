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
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.function.Predicate;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/** Entry point for filtering resources. */
public class FilterResourcesExecutableMain {
  @Option(name = "--in-res-dir-to-out-res-dir-map", required = true)
  private String inResDirToOutResDirMapPath;

  @Option(name = "--target-densities", required = true)
  private String targetDensities;

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
    ImmutableSet<ResourceFilters.Density> targetDensitiesSet =
        Arrays.stream(targetDensities.split(","))
            .map(ResourceFilters.Density::from)
            .collect(ImmutableSet.toImmutableSet());
    ImmutableBiMap<Path, Path> inResDirToOutResDirMap =
        Files.lines(Paths.get(inResDirToOutResDirMapPath))
            .map(s -> s.split(" "))
            .collect(
                ImmutableBiMap.toImmutableBiMap(
                    arr -> Paths.get(arr[0]), arr -> Paths.get(arr[1])));
    Predicate<Path> filteringPredicate =
        FilteringPredicate.getFilteringPredicate(
            root,
            inResDirToOutResDirMap,
            !targetDensities.isEmpty(),
            targetDensitiesSet,
            // TODO(T122759074) Support these filters too
            /* canDownscale */ false,
            /* locales */ ImmutableSet.of("NONE"),
            /* packagedLocales */ ImmutableSet.of(),
            /* enableStringWhitelisting */ false,
            /* whitelistedStringDirs */ ImmutableSet.of());

    FilteredDirectoryCopier.copyDirs(root, inResDirToOutResDirMap, filteringPredicate);

    System.exit(0);
  }
}
