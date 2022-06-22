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

package com.facebook.buck.android.resources.strings;

import com.facebook.buck.core.filesystems.AbsPath;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/** Entry point for copying string resources. */
public class CopyStringResourcesExecutableMain {
  @Option(name = "--res-dirs", required = true)
  private String resDirsFileString;

  @Option(name = "--output", required = true)
  private String outputPathString;

  public static void main(String[] args) throws IOException {
    CopyStringResourcesExecutableMain main = new CopyStringResourcesExecutableMain();
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
    ImmutableList<Path> resDirs =
        Files.readAllLines(Paths.get(resDirsFileString)).stream()
            .map(Paths::get)
            .collect(ImmutableList.toImmutableList());
    Path outputPath = Paths.get(outputPathString);

    StringResourcesUtils.copyResources(root, resDirs, outputPath);

    System.exit(0);
  }
}
