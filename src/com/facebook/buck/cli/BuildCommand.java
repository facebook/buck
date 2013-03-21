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

import com.facebook.buck.command.Build;
import com.facebook.buck.debug.Tracer;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildEvents;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.JavaUtilsLoggingBuildListener;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.Verbosity;
import com.facebook.buck.util.ExceptionWithHumanReadableMessage;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.EventBus;

import java.io.IOException;
import java.io.PrintStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BuildCommand extends AbstractCommandRunner<BuildCommandOptions> {

  /** The minimum length of time for a Tracer event to take to be printed to the console. */
  private static final long TRACER_THRESHOLD = 50L;

  private Build build;

  private ImmutableList<BuildTarget> buildTargets = ImmutableList.of();

  public BuildCommand(ArtifactCache artifactCache) {
    super(artifactCache);
  }

  public BuildCommand(PrintStream stdOut, PrintStream stdErr, Console console,
      ProjectFilesystem projectFilesystem, ArtifactCache artifactCache) {
    super(stdOut, stdErr, console, projectFilesystem, artifactCache);
  }

  @Override
  BuildCommandOptions createOptions(BuckConfig buckConfig) {
    return new BuildCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptions(BuildCommandOptions options) throws IOException {
    Tracer tracer = Tracer.startTracer("buck");
    try {
      return runCommandWithOptionsWithTracerRunning(options);
    } finally {
      tracer.stop(TRACER_THRESHOLD);
      if (options.getVerbosity().shouldPrintCommand()) {
        Tracer.clearAndPrintCurrentTrace(stdErr);
      }
    }
  }

  private int runCommandWithOptionsWithTracerRunning(BuildCommandOptions options)
      throws IOException {
    // Set the logger level based on the verbosity option.
    Verbosity verbosity = options.getVerbosity();
    Logging.setLoggingLevelForVerbosity(verbosity);


    Parser parser = createParser();

    try {
      buildTargets = getBuildTargets(parser, options.getArgumentsFormattedAsBuildTargets());
    } catch (NoSuchBuildTargetException e) {
      console.printFailureWithoutStacktrace(e);
      return 1;
    }

    if (buildTargets.isEmpty()) {
      console.printFailure("Must specify at least one build target.");
      return 1;
    }

    // Parse the build files to create a DependencyGraph.
    DependencyGraph dependencyGraph;
    try {
      dependencyGraph = parser.parseBuildFilesForTargets(buildTargets,
          options.getDefaultIncludes());
    } catch (NoSuchBuildTargetException e) {
      console.printFailureWithoutStacktrace(e);
      return 1;
    }

    // Create and execute the build.
    this.build = options.createBuild(dependencyGraph,
        getProjectFilesystem().getProjectRoot(),
        console);
    stdErr.printf("BUILDING %s\n", Joiner.on(' ').join(buildTargets));
    int exitCode = executeBuildAndPrintAnyFailuresToConsole(build, console);

    if (exitCode != 0) {
      return exitCode;
    }

    Tracer.addComment("Build targets built");
    ansi.printlnHighlightedSuccessText(stdErr, "BUILD SUCCESSFUL");
    return 0;
  }

  static int executeBuildAndPrintAnyFailuresToConsole(Build build, Console console) {
    ExecutorService busExecutor = Executors.newCachedThreadPool();
    EventBus events = new AsyncEventBus("buck-events", busExecutor);
    addEventListeners(events);

    Tracer buildTracer = Tracer.startTracer("buck build");
    int exitCode;
    try {
      // Get the Future representing the build and then block until everything is built.
      build.executeBuild(events).get();
      exitCode = 0;
    } catch (IOException e) {
      console.printFailureWithoutStacktrace(e);
      exitCode = 1;
    } catch (StepFailedException e) {
      console.printFailureWithoutStacktrace(e);
      exitCode = e.getExitCode();
    } catch (ExecutionException e) {
      // This is likely a checked exception that was caught while building a build rule.
      Throwable cause = e.getCause();
      if (cause instanceof HumanReadableException) {
        throw ((HumanReadableException)cause);
      } else if (cause instanceof ExceptionWithHumanReadableMessage) {
        throw new HumanReadableException((ExceptionWithHumanReadableMessage)cause);
      } else {
        if (cause instanceof RuntimeException) {
          console.printFailureWithStacktrace(e);
        } else {
          console.printFailureWithoutStacktrace(e);
        }
        exitCode = 1;
      }
    } catch (InterruptedException e) {
      // This suggests an error in Buck rather than a user error.
      console.printFailureWithoutStacktrace(e);
      exitCode = 1;
    } finally {
      buildTracer.stop(TRACER_THRESHOLD);
    }

    events.post(BuildEvents.finished(exitCode));
    return exitCode;
  }

  private static void addEventListeners(EventBus events) {
    events.register(new JavaUtilsLoggingBuildListener());
    JavaUtilsLoggingBuildListener.ensureLogFileIsWritten();
  }

  Build getBuild() {
    Preconditions.checkNotNull(build);
    return build;
  }

  ImmutableList<BuildTarget> getBuildTargets() {
    return ImmutableList.copyOf(buildTargets);
  }

  @Override
  String getUsageIntro() {
    return "Specify one build rule to build.";
  }
}
