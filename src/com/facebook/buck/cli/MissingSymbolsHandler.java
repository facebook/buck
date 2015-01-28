/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.MissingSymbolEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaSymbolFinder;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.SrcRootsFinder;
import com.facebook.buck.json.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.Description;
import com.facebook.buck.util.Console;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import com.google.common.eventbus.Subscribe;

import java.util.Collection;
import java.util.Set;

public class MissingSymbolsHandler {

  private final Console console;
  private final JavaSymbolFinder javaSymbolFinder;
  private final String buildFileName;

  private MissingSymbolsHandler(
      Console console,
      JavaSymbolFinder javaSymbolFinder,
      String buildFileName) {
    this.console = console;
    this.javaSymbolFinder = javaSymbolFinder;
    this.buildFileName = buildFileName;
  }

  public static MissingSymbolsHandler create(
      ProjectFilesystem projectFilesystem,
      ImmutableSet<Description<?>> descriptions,
      BuckConfig config,
      BuckEventBus buckEventBus,
      Console console,
      JavacOptions javacOptions,
      ImmutableMap<String, String> environment) {
    SrcRootsFinder srcRootsFinder = new SrcRootsFinder(projectFilesystem);
    ParserConfig parserConfig = new ParserConfig(config);
    ProjectBuildFileParserFactory projectBuildFileParserFactory =
        new DefaultProjectBuildFileParserFactory(
            projectFilesystem,
            parserConfig,
            descriptions);
    JavaSymbolFinder javaSymbolFinder = new JavaSymbolFinder(
        projectFilesystem,
        srcRootsFinder,
        javacOptions,
        projectBuildFileParserFactory,
        config,
        buckEventBus,
        console,
        environment);
    return new MissingSymbolsHandler(
        console,
        javaSymbolFinder,
        parserConfig.getBuildFileName());
  }

  /**
   * Instantiate a MissingSymbolsHandler and wrap it in a listener that calls it on the appropriate
   * events. This is done as part of the global listener setup in Main, and it's the entry point
   * into most of the dependency autodetection code.
   */
  public static BuckEventListener createListener(
      ProjectFilesystem projectFilesystem,
      ImmutableSet<Description<?>> descriptions,
      BuckConfig config,
      BuckEventBus buckEventBus,
      Console console,
      JavacOptions javacOptions,
      ImmutableMap<String, String> environment) {
    final MissingSymbolsHandler missingSymbolsHandler = create(
        projectFilesystem,
        descriptions,
        config,
        buckEventBus,
        console,
        javacOptions,
        environment);

    final Multimap<BuildId, MissingSymbolEvent> missingSymbolEvents = HashMultimap.create();

    BuckEventListener missingSymbolsListener = new BuckEventListener() {
      @Override
      public void outputTrace(BuildId buildId) {
        // If we put {@link #printNeededDependencies} here, it's output won't be visible in buckd.
        // Instead, we listen for BuildEvent.Finished, below.
      }

      @Subscribe
      public void onMissingSymbol(MissingSymbolEvent event) {
        missingSymbolEvents.put(event.getBuildId(), event);
      }

      @Subscribe
      public void onBuildFinished(BuildEvent.Finished event) throws InterruptedException {
        // Shortcircuit if there aren't any failures.
        if (missingSymbolEvents.get(event.getBuildId()).isEmpty()) {
          return;
        }
        missingSymbolsHandler.printNeededDependencies(missingSymbolEvents.get(event.getBuildId()));
        missingSymbolEvents.removeAll(event.getBuildId());
      }
    };

    return missingSymbolsListener;
  }

  /**
   * Using missing symbol events from the build and the JavaSymbolFinder class, build a list of
   * missing dependencies for each broken target.
   */
  public ImmutableSetMultimap<BuildTarget, BuildTarget> getNeededDependencies(
      Collection<MissingSymbolEvent> missingSymbolEvents) throws InterruptedException {
    ImmutableSetMultimap.Builder<BuildTarget, String> targetsMissingSymbolsBuilder =
        ImmutableSetMultimap.builder();
    for (MissingSymbolEvent event : missingSymbolEvents) {
      if (event.getType() != MissingSymbolEvent.SymbolType.Java) {
        throw new UnsupportedOperationException("Only implemented for Java.");
      }
      targetsMissingSymbolsBuilder.put(event.getTarget(), event.getSymbol());
    }
    ImmutableSetMultimap<BuildTarget, String> targetsMissingSymbols =
        targetsMissingSymbolsBuilder.build();
    ImmutableSetMultimap<String, BuildTarget> symbolProviders =
        javaSymbolFinder.findTargetsForSymbols(ImmutableSet.copyOf(targetsMissingSymbols.values()));

    ImmutableSetMultimap.Builder<BuildTarget, BuildTarget> neededDeps =
        ImmutableSetMultimap.builder();

    for (BuildTarget target: targetsMissingSymbols.keySet()) {
      for (String symbol : targetsMissingSymbols.get(target)) {
        // TODO(jacko): Properly handle symbols that are defined in more than one place.
        // TODO(jacko): Properly handle target visibility.
        neededDeps.putAll(target, ImmutableSortedSet.copyOf(symbolProviders.get(symbol)));
      }
    }

    return neededDeps.build();
  }

  /**
   * Get a list of missing dependencies from {@link #getNeededDependencies} and print it to the
   * console in a list-of-Python-strings way that's easy to copy and paste.
   */
  private void printNeededDependencies(Collection<MissingSymbolEvent> missingSymbolEvents)
      throws InterruptedException {
    ImmutableSetMultimap<BuildTarget, BuildTarget> neededDependencies =
        getNeededDependencies(missingSymbolEvents);
    ImmutableSortedSet.Builder<String> samePackageDeps = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<String> otherPackageDeps = ImmutableSortedSet.naturalOrder();
    for (BuildTarget target : neededDependencies.keySet()) {
      print(formatTarget(target) + " is missing deps:");
      Set<BuildTarget> sortedDeps = ImmutableSortedSet.copyOf(neededDependencies.get(target));
      for (BuildTarget neededDep : sortedDeps) {
        if (neededDep.getBaseName().equals(target.getBaseName())) {
          samePackageDeps.add(":" + neededDep.getShortName());
        } else {
          otherPackageDeps.add(neededDep.toString());
        }
      }
    }
    String format = "    '%s',";
    for (String dep : samePackageDeps.build()) {
      print(String.format(format, dep));
    }
    for (String dep : otherPackageDeps.build()) {
      print(String.format(format, dep));
    }
  }

  /**
   * Format a target string so that the path to the BUCK file its in is easily copyable.
   */
  private String formatTarget(BuildTarget buildTarget) {
    return String.format("%s (:%s)",
        buildTarget.getBasePath().resolve(buildFileName),
        buildTarget.getShortName());
  }

  private void print(String line) {
    console.getStdOut().println(console.getAnsi().asWarningText(line));
  }
}
