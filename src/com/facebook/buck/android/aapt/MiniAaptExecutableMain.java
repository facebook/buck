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

package com.facebook.buck.android.aapt;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.util.ThrowingPrintWriter;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import javax.xml.xpath.XPathExpressionException;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/** Main entry point for executing {@link MiniAapt} calls. */
public class MiniAaptExecutableMain {

  @Option(name = "--resource-paths", required = true)
  private String resourcePathsDir;

  @Option(name = "--dep-symbol-paths", required = true)
  private String depSymbolsPathsList;

  @Option(name = "--output-path", required = true)
  private String outputPath;

  public static void main(String[] args) throws IOException {
    MiniAaptExecutableMain main = new MiniAaptExecutableMain();
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
    RelPath resourceDirectory = RelPath.get(resourcePathsDir);
    ImmutableMap<Path, Path> resourcePaths = MiniAapt.getAllResourceFiles(root, resourceDirectory);

    ImmutableSet<Path> depSymbolsFilePaths =
        Files.readAllLines(Paths.get(depSymbolsPathsList)).stream()
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());
    ImmutableSet<RDotTxtEntry> references;

    MiniAapt miniAapt = new MiniAapt(depSymbolsFilePaths);
    try {
      references = miniAapt.processAllFiles(resourcePaths);
    } catch (MiniAapt.ResourceParseException | XPathExpressionException e) {
      throw new RuntimeException(e);
    }

    Set<RDotTxtEntry> missing = miniAapt.verifyReferences(references);
    if (!missing.isEmpty()) {
      throw new RuntimeException(
          String.format(
              "The following resources were not found: \n%s\n", Joiner.on('\n').join(missing)));
    }

    try (ThrowingPrintWriter writer = new ThrowingPrintWriter(new FileOutputStream(outputPath))) {
      Set<RDotTxtEntry> sortedResources =
          ImmutableSortedSet.copyOf(
              Ordering.natural(), miniAapt.getResourceCollector().getResources());
      for (RDotTxtEntry entry : sortedResources) {
        writer.printf("%s %s %s %s\n", entry.idType, entry.type, entry.name, entry.idValue);
      }
    }

    System.exit(0);
  }
}
