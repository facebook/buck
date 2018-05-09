/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.build.engine.impl.MetadataChecker;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.args4j.Argument;

/** Command for proposing a fine-grained partitioning of a Java build rule. */
public class SuggestCommand extends AbstractCommand {
  /** This command has one required argument, which must be a build target or alias. */
  @Argument private List<String> arguments = new ArrayList<>();

  /**
   * Normalizes the sole build target argument and partitions it using a {@link
   * FineGrainedJavaDependencySuggester}.
   */
  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    if (arguments.size() != 1) {
      throw new CommandLineException("must specify exactly one argument to 'buck suggest'");
    }

    Console console = params.getConsole();

    String targetToBreakDown = Iterables.getOnlyElement(arguments);
    String fullyQualifiedTarget =
        Iterables.getOnlyElement(
            getCommandLineBuildTargetNormalizer(params.getBuckConfig())
                .normalize(targetToBreakDown));

    MetadataChecker.checkAndCleanIfNeeded(params.getCell());
    JavaBuildGraphProcessor.Processor processor =
        (graph, javaDepsFinder, executorService) -> {
          BuildTarget buildTarget =
              params.getBuckConfig().getBuildTargetForFullyQualifiedTarget(fullyQualifiedTarget);
          FineGrainedJavaDependencySuggester suggester =
              new FineGrainedJavaDependencySuggester(buildTarget, graph, javaDepsFinder, console);
          suggester.suggestRefactoring();
        };
    try {
      JavaBuildGraphProcessor.run(params, this, processor);
    } catch (JavaBuildGraphProcessor.ExitCodeException e) {
      return ExitCode.map(e.exitCode);
    }

    return ExitCode.SUCCESS;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "suggests a refactoring for the specified build target";
  }
}
