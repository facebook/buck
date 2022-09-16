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

package com.facebook.buck.android.resources;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/** Main entry point for executing {@link MergeThirdPartyJarResourcesUtils} calls. */
public class MergeThirdPartyJarResourcesExecutableMain {
  @Option(name = "--output", required = true)
  private String outputString;

  @Option(name = "--output-hash", required = true)
  private String outputHashString;

  @Option(name = "--third-party-jars", required = true)
  private String thirdPartyJarsList;

  public static void main(String[] args) throws IOException {
    MergeThirdPartyJarResourcesExecutableMain main =
        new MergeThirdPartyJarResourcesExecutableMain();
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
    ImmutableSortedSet<RelPath> thirdPartyJars =
        Files.readAllLines(Paths.get(thirdPartyJarsList)).stream()
            .map(RelPath::get)
            .collect(ImmutableSortedSet.toImmutableSortedSet(RelPath.comparator()));
    Path output = Paths.get(outputString);

    MergeThirdPartyJarResourcesUtils.mergeThirdPartyJarResources(root, thirdPartyJars, output);
    Files.writeString(
        Paths.get(outputHashString),
        com.google.common.io.Files.hash(output.toFile(), Hashing.sha1()).toString());
  }
}
