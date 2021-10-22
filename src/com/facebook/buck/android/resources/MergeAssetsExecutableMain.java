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

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/** Main entry point for merging assets. */
public class MergeAssetsExecutableMain {

  @Option(name = "--output-apk", required = true, usage = "path to output APK")
  private String outputApk;

  @Option(name = "--base-apk", usage = "path to existing APK containing resources")
  private String baseApk = null;

  @Option(name = "--assets", required = true, usage = "path to list of assets")
  private String pathToAssetsList;

  public static void main(String[] args) throws IOException {
    MergeAssetsExecutableMain main = new MergeAssetsExecutableMain();
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
    ImmutableMap<Path, Path> assets =
        Files.readAllLines(Paths.get(pathToAssetsList)).stream()
            .map(p -> p.split(" "))
            .collect(
                ImmutableMap.toImmutableMap(
                    pair -> Paths.get(pair[0]), pair -> Paths.get(pair[1])));

    MergeAssetsUtils.mergeAssets(
        Paths.get(outputApk), Optional.ofNullable(baseApk).map(Paths::get), assets);
  }
}
