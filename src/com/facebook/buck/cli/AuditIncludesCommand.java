/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.ProjectBuildFileParserFactory;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/**
 * Evaluates a build file and prints out a list of build file extensions included at parse time.
 * This commands is kind of like a buck query deps command, but for build files.
 */
public class AuditIncludesCommand extends AbstractCommand {

  @Option(name = "--json", usage = "Print JSON representation of each rule")
  private boolean json;

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  @Override
  public String getShortDescription() {
    return "List build file extensions imported at parse time.";
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    ProjectFilesystem projectFilesystem = params.getCell().getFilesystem();
    try (ProjectBuildFileParser parser =
        ProjectBuildFileParserFactory.createBuildFileParser(
            params.getCell(),
            new DefaultTypeCoercerFactory(),
            params.getConsole(),
            params.getBuckEventBus(),
            params.getExecutableFinder(),
            params.getKnownBuildRuleTypesProvider().get(params.getCell()).getDescriptions())) {
      PrintStream out = params.getConsole().getStdOut();
      for (String pathToBuildFile : getArguments()) {
        if (!json) {
          // Print a comment with the path to the build file.
          out.printf("# %s\n\n", pathToBuildFile);
        }

        // Resolve the path specified by the user.
        Path path = Paths.get(pathToBuildFile);
        if (!path.isAbsolute()) {
          Path root = projectFilesystem.getRootPath();
          path = root.resolve(path);
        }

        List<Map<String, Object>> rawRules = parser.getAllRulesAndMetaRules(path, new AtomicLong());

        int includesMetadataEntryIndex = 3;
        Preconditions.checkState(
            includesMetadataEntryIndex <= rawRules.size(), "__includes metadata entry is missing.");
        // __includes meta rule is the 3rd one from the end
        Map<String, Object> includesMetaRule =
            rawRules.get(rawRules.size() - includesMetadataEntryIndex);
        @SuppressWarnings("unchecked")
        @Nullable
        Iterable<String> includes = (Iterable<String>) includesMetaRule.get("__includes");
        printIncludesToStdout(
            params, Preconditions.checkNotNull(includes, "__includes metadata entry is missing"));
      }
    }

    return ExitCode.SUCCESS;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  private void printIncludesToStdout(CommandRunnerParams params, Iterable<String> includes)
      throws IOException {

    PrintStream stdOut = params.getConsole().getStdOut();

    if (json) {
      // We create a new JsonGenerator that does not close the stream.
      try (JsonGenerator generator =
          ObjectMappers.createGenerator(stdOut).useDefaultPrettyPrinter()) {
        ObjectMappers.WRITER.writeValue(generator, includes);
      }
    } else {
      for (String include : includes) {
        stdOut.println(include);
      }
    }
  }
}
