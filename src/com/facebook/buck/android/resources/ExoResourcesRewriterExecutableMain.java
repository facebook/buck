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
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/** Main entry point for executing {@link ExoResourcesRewriter} calls. */
public class ExoResourcesRewriterExecutableMain {
  @Option(name = "--original-r-dot-txt", required = true)
  private String originalRDotTxt;

  @Option(name = "--new-r-dot-txt", required = true)
  private String newRDotTxt;

  @Option(name = "--original-primary-apk-resources", required = true)
  private String originalPrimaryApkResources;

  @Option(name = "--new-primary-apk-resources", required = true)
  private String newPrimaryApkResources;

  @Option(name = "--exo-resources", required = true)
  private String exoResources;

  @Option(name = "--exo-resources-hash", required = true)
  private String exoResourcesHash;

  @Option(name = "--zipalign-tool", required = true)
  private String zipalignTool;

  public static void main(String[] args) throws IOException {
    ExoResourcesRewriterExecutableMain main = new ExoResourcesRewriterExecutableMain();
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
    RelPath unalignedExoResources = RelPath.get("unalignedExoResources.apk");

    ExoResourcesRewriter.rewrite(
        root,
        RelPath.get(originalPrimaryApkResources),
        RelPath.get(originalRDotTxt),
        RelPath.get(newPrimaryApkResources),
        unalignedExoResources,
        RelPath.get(newRDotTxt));
    Process zipalignProcess =
        new ProcessBuilder()
            .command(zipalignTool, "-f", "4", unalignedExoResources.toString(), exoResources)
            .start();

    try {
      zipalignProcess.waitFor();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    Files.writeString(
        Paths.get(exoResourcesHash),
        com.google.common.io.Files.hash(Paths.get(exoResources).toFile(), Hashing.sha1())
            .toString());
  }
}
