/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.parser;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.WatchmanFactory;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.json.HybridProjectBuildFileParser;
import com.facebook.buck.json.PythonDslProjectBuildFileParser;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.api.Syntax;
import com.facebook.buck.parser.decorators.EventReportingProjectBuildFileParser;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.skylark.parser.SkylarkProjectBuildFileParser;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

public class ProjectBuildFileParserFactory {
  /**
   * Callers are responsible for managing the life-cycle of the created {@link
   * ProjectBuildFileParser}.
   */
  public static ProjectBuildFileParser createBuildFileParser(
      Cell cell,
      TypeCoercerFactory typeCoercerFactory,
      Console console,
      BuckEventBus eventBus,
      Iterable<Description<?>> descriptions) {
    return createBuildFileParser(
        cell, typeCoercerFactory, console, eventBus, descriptions, /* enableProfiling */ false);
  }

  /**
   * Same as @{{@link #createBuildFileParser(Cell, TypeCoercerFactory, Console, BuckEventBus,
   * Iterable)}} but provides a way to configure whether parse profiling should be enabled
   */
  public static ProjectBuildFileParser createBuildFileParser(
      Cell cell,
      TypeCoercerFactory typeCoercerFactory,
      Console console,
      BuckEventBus eventBus,
      Iterable<Description<?>> descriptions,
      boolean enableProfiling) {

    ParserConfig parserConfig = cell.getBuckConfig().getView(ParserConfig.class);

    boolean useWatchmanGlob =
        parserConfig.getGlobHandler() == ParserConfig.GlobHandler.WATCHMAN
            && cell.getWatchman().hasWildmatchGlob();
    boolean watchmanGlobStatResults =
        parserConfig.getWatchmanGlobSanityCheck() == ParserConfig.WatchmanGlobSanityCheck.STAT;
    boolean watchmanUseGlobGenerator =
        cell.getWatchman().getCapabilities().contains(WatchmanFactory.Capability.GLOB_GENERATOR);
    boolean useMercurialGlob = parserConfig.getGlobHandler() == ParserConfig.GlobHandler.MERCURIAL;
    String pythonInterpreter = parserConfig.getPythonInterpreter(new ExecutableFinder());
    Optional<String> pythonModuleSearchPath = parserConfig.getPythonModuleSearchPath();

    ProjectBuildFileParserOptions buildFileParserOptions =
        ProjectBuildFileParserOptions.builder()
            .setEnableProfiling(enableProfiling)
            .setProjectRoot(cell.getFilesystem().getRootPath())
            .setCellRoots(cell.getCellPathResolver().getCellPaths())
            .setCellName(cell.getCanonicalName().orElse(""))
            .setFreezeGlobals(parserConfig.getFreezeGlobals())
            .setPythonInterpreter(pythonInterpreter)
            .setPythonModuleSearchPath(pythonModuleSearchPath)
            .setAllowEmptyGlobs(parserConfig.getAllowEmptyGlobs())
            .setIgnorePaths(cell.getFilesystem().getIgnorePaths())
            .setBuildFileName(cell.getBuildFileName())
            .setDefaultIncludes(parserConfig.getDefaultIncludes())
            .setDescriptions(descriptions)
            .setUseWatchmanGlob(useWatchmanGlob)
            .setWatchmanGlobStatResults(watchmanGlobStatResults)
            .setWatchmanUseGlobGenerator(watchmanUseGlobGenerator)
            .setWatchman(cell.getWatchman())
            .setWatchmanQueryTimeoutMs(parserConfig.getWatchmanQueryTimeoutMs())
            .setUseMercurialGlob(useMercurialGlob)
            .setRawConfig(cell.getBuckConfig().getRawConfigForParser())
            .setBuildFileImportWhitelist(parserConfig.getBuildFileImportWhitelist())
            .build();
    return EventReportingProjectBuildFileParser.of(
        createProjectBuildFileParser(
            cell, typeCoercerFactory, console, eventBus, parserConfig, buildFileParserOptions),
        eventBus);
  }

  /** Creates a project build file parser based on Buck configuration settings. */
  private static ProjectBuildFileParser createProjectBuildFileParser(
      Cell cell,
      TypeCoercerFactory typeCoercerFactory,
      Console console,
      BuckEventBus eventBus,
      ParserConfig parserConfig,
      ProjectBuildFileParserOptions buildFileParserOptions) {
    PythonDslProjectBuildFileParser pythonDslProjectBuildFileParser =
        new PythonDslProjectBuildFileParser(
            buildFileParserOptions,
            typeCoercerFactory,
            cell.getBuckConfig().getEnvironment(),
            eventBus,
            new DefaultProcessExecutor(console));
    if (parserConfig.isPolyglotParsingEnabled()) {
      return HybridProjectBuildFileParser.using(
          ImmutableMap.of(
              Syntax.PYTHON_DSL,
              pythonDslProjectBuildFileParser,
              Syntax.SKYLARK,
              SkylarkProjectBuildFileParser.using(
                  buildFileParserOptions,
                  eventBus,
                  SkylarkFilesystem.using(cell.getFilesystem()),
                  typeCoercerFactory)),
          parserConfig.getDefaultBuildFileSyntax());
    }
    return pythonDslProjectBuildFileParser;
  }
}
