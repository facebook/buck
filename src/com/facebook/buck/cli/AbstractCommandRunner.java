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

import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.listener.FileSerializationEventBusListener;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

abstract class AbstractCommandRunner<T extends AbstractCommandOptions> implements CommandRunner {

  abstract T createOptions();

  private ParserAndOptions<T> createParser() {
    T options = createOptions();
    return new ParserAndOptions<>(options);
  }

  @Override
  public final int runCommand(CommandRunnerParams params, List<String> args)
      throws IOException, InterruptedException {
    ParserAndOptions<T> parserAndOptions = createParser();
    T options = parserAndOptions.options;
    CmdLineParser parser = parserAndOptions.parser;

    boolean hasValidOptions = false;
    try {
      parser.parseArgument(args);
      hasValidOptions = true;
    } catch (CmdLineException e) {
      params.getConsole().getStdErr().println(e.getMessage());
    }

    if (hasValidOptions && !options.showHelp()) {
      return runCommandWithOptions(params, options);
    } else {
      printUsage(params, parser);
      return 1;
    }
  }

  public final void printUsage(CommandRunnerParams params, CmdLineParser parser) {
    String intro = getUsageIntro();
    if (intro != null) {
      params.getConsole().getStdErr().println(intro);
    }
    parser.printUsage(params.getConsole().getStdErr());
  }

  /**
   * @return the exit code this process should exit with
   */
  public final synchronized int runCommandWithOptions(CommandRunnerParams params, T options)
      throws IOException, InterruptedException {
    // At this point, we have parsed options but haven't started running the command yet.  This is
    // a good opportunity to augment the event bus with our serialize-to-file event-listener.
    Optional<Path> eventsOutputPath = options.getEventsOutputPath();
    if (eventsOutputPath.isPresent()) {
      BuckEventListener listener = new FileSerializationEventBusListener(eventsOutputPath.get());
      params.getBuckEventBus().register(listener);
    }
    return runCommandWithOptionsInternal(params, options);
  }

  /**
   * Invoked by {@link #runCommandWithOptions(CommandRunnerParams, AbstractCommandOptions)} after
   * {@code #options} has been set.
   * @return the exit code this process should exit with
   */
  abstract int runCommandWithOptionsInternal(CommandRunnerParams params, T options)
      throws IOException, InterruptedException;

  /**
   * @return may be null
   */
  @Nullable
  abstract String getUsageIntro();

  /**
   * @return A set of {@link BuildTarget}s for the input buildTargetNames.
   */
  protected ImmutableSet<BuildTarget> getBuildTargets(
      CommandRunnerParams params,
      Iterable<String> buildTargetNames) {
    ImmutableSet.Builder<BuildTarget> buildTargets = ImmutableSet.builder();

    // Parse all of the build targets specified by the user.
    BuildTargetParser buildTargetParser = params.getParser().getBuildTargetParser();

    for (String buildTargetName : buildTargetNames) {
      buildTargets.add(buildTargetParser.parse(
              buildTargetName,
              BuildTargetPatternParser.fullyQualified(buildTargetParser)));
    }

    return buildTargets.build();
  }

  public ArtifactCache getArtifactCache(CommandRunnerParams params, T options)
      throws InterruptedException {
    return params.getArtifactCacheFactory().newInstance(params.getBuckConfig(), options);
  }

  private static class ParserAndOptions<T> {
    private final T options;
    private final CmdLineParser parser;

    private ParserAndOptions(T options) {
      this.options = options;
      this.parser = new AdditionalOptionsCmdLineParser(options);
    }
  }

  protected ExecutionContext createExecutionContext(CommandRunnerParams params) {
    return ExecutionContext.builder()
        .setProjectFilesystem(params.getRepository().getFilesystem())
        .setConsole(params.getConsole())
        .setAndroidPlatformTargetSupplier(params.getAndroidPlatformTargetSupplier())
        .setEventBus(params.getBuckEventBus())
        .setPlatform(params.getPlatform())
        .setEnvironment(params.getEnvironment())
        .setJavaPackageFinder(params.getJavaPackageFinder())
        .setObjectMapper(params.getObjectMapper())
        .build();
  }
}
