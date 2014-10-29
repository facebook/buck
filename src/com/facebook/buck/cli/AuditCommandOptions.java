/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import com.google.common.collect.Lists;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class AuditCommandOptions extends AbstractCommandOptions {

  /**
   * Expected usage:
   * <pre>
   * buck audit classpath --dot //java/com/facebook/pkg:pkg > /tmp/graph.dot
   * dot -Tpng /tmp/graph.dot -o /tmp/graph.png
   * </pre>
   */
  @Option(name = "--dot",
          usage = "Print dependencies as Dot graph")
  private boolean generateDotOutput;

  @Option(name = "--json",
          usage = "Output in JSON format")
  private boolean generateJsonOutput;

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public AuditCommandOptions(BuckConfig buckConfig) {
    super(buckConfig);
  }

  public List<String> getArguments() {
    return arguments;
  }

  public List<String> getArgumentsFormattedAsBuildTargets() {
    return getCommandLineBuildTargetNormalizer().normalizeAll(getArguments());
  }

  /**
   * @return relative paths under the project root
   */
  public Iterable<Path> getArgumentsAsPaths(Path projectRoot) throws IOException {
    return PathArguments.getCanonicalFilesUnderProjectRoot(projectRoot, getArguments())
        .relativePathsUnderProjectRoot;
  }

  public boolean shouldGenerateDotOutput() {
    return generateDotOutput;
  }

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }
}
