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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypesProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.WatchmanFactory;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.json.HybridProjectBuildFileParser;
import com.facebook.buck.json.PythonDslProjectBuildFileParser;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.api.Syntax;
import com.facebook.buck.parser.decorators.EventReportingProjectBuildFileParser;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.skylark.parser.BuckGlobals;
import com.facebook.buck.skylark.parser.ConsoleEventHandler;
import com.facebook.buck.skylark.parser.RuleFunctionFactory;
import com.facebook.buck.skylark.parser.SkylarkProjectBuildFileParser;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.EventKind;
import java.util.Arrays;
import java.util.Optional;

public class ProjectBuildFileParserFactory {
  private final TypeCoercerFactory typeCoercerFactory;
  private final Console console;
  private final ParserPythonInterpreterProvider pythonInterpreterProvider;
  private final KnownBuildRuleTypesProvider knownBuildRuleTypesProvider;
  private final boolean enableProfiling;

  public ProjectBuildFileParserFactory(
      TypeCoercerFactory typeCoercerFactory,
      Console console,
      ParserPythonInterpreterProvider pythonInterpreterProvider,
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider,
      boolean enableProfiling) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.console = console;
    this.pythonInterpreterProvider = pythonInterpreterProvider;
    this.knownBuildRuleTypesProvider = knownBuildRuleTypesProvider;
    this.enableProfiling = enableProfiling;
  }

  public ProjectBuildFileParserFactory(
      TypeCoercerFactory typeCoercerFactory,
      ParserPythonInterpreterProvider pythonInterpreterProvider,
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider,
      boolean enableProfiling) {
    this(
        typeCoercerFactory,
        Console.createNullConsole(),
        pythonInterpreterProvider,
        knownBuildRuleTypesProvider,
        enableProfiling);
  }

  public ProjectBuildFileParserFactory(
      TypeCoercerFactory typeCoercerFactory,
      Console console,
      ParserPythonInterpreterProvider pythonInterpreterProvider,
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider) {
    this(
        typeCoercerFactory, console, pythonInterpreterProvider, knownBuildRuleTypesProvider, false);
  }

  /**
   * Callers are responsible for managing the life-cycle of the created {@link
   * ProjectBuildFileParser}.
   */
  public ProjectBuildFileParser createBuildFileParser(BuckEventBus eventBus, Cell cell) {

    ParserConfig parserConfig = cell.getBuckConfig().getView(ParserConfig.class);

    boolean useWatchmanGlob =
        parserConfig.getGlobHandler() == ParserConfig.GlobHandler.WATCHMAN
            && cell.getWatchman().hasWildmatchGlob();
    boolean watchmanGlobStatResults =
        parserConfig.getWatchmanGlobSanityCheck() == ParserConfig.WatchmanGlobSanityCheck.STAT;
    boolean watchmanUseGlobGenerator =
        cell.getWatchman().getCapabilities().contains(WatchmanFactory.Capability.GLOB_GENERATOR);
    Optional<String> pythonModuleSearchPath = parserConfig.getPythonModuleSearchPath();

    ProjectBuildFileParserOptions buildFileParserOptions =
        ProjectBuildFileParserOptions.builder()
            .setEnableProfiling(enableProfiling)
            .setProjectRoot(cell.getFilesystem().getRootPath())
            .setCellRoots(cell.getCellPathResolver().getCellPaths())
            .setCellName(cell.getCanonicalName().orElse(""))
            .setPythonInterpreter(pythonInterpreterProvider.getOrFail())
            .setPythonModuleSearchPath(pythonModuleSearchPath)
            .setAllowEmptyGlobs(parserConfig.getAllowEmptyGlobs())
            .setIgnorePaths(cell.getFilesystem().getIgnorePaths())
            .setBuildFileName(cell.getBuildFileName())
            .setDefaultIncludes(parserConfig.getDefaultIncludes())
            .setDescriptions(knownBuildRuleTypesProvider.get(cell).getDescriptions())
            .setUseWatchmanGlob(useWatchmanGlob)
            .setWatchmanGlobStatResults(watchmanGlobStatResults)
            .setWatchmanUseGlobGenerator(watchmanUseGlobGenerator)
            .setWatchman(cell.getWatchman())
            .setWatchmanQueryTimeoutMs(parserConfig.getWatchmanQueryTimeoutMs())
            .setRawConfig(cell.getBuckConfig().getRawConfigForParser())
            .setBuildFileImportWhitelist(parserConfig.getBuildFileImportWhitelist())
            .setDisableImplicitNativeRules(parserConfig.getDisableImplicitNativeRules())
            .setWarnAboutDeprecatedSyntax(parserConfig.isWarnAboutDeprecatedSyntax())
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
    Syntax defaultBuildFileSyntax = parserConfig.getDefaultBuildFileSyntax();
    if (parserConfig.isPolyglotParsingEnabled()) {
      return HybridProjectBuildFileParser.using(
          ImmutableMap.of(
              Syntax.PYTHON_DSL,
              newPythonParser(cell, typeCoercerFactory, console, eventBus, buildFileParserOptions),
              Syntax.SKYLARK,
              newSkylarkParser(cell, typeCoercerFactory, eventBus, buildFileParserOptions)),
          defaultBuildFileSyntax);
    } else {
      switch (defaultBuildFileSyntax) {
        case SKYLARK:
          return newSkylarkParser(cell, typeCoercerFactory, eventBus, buildFileParserOptions);
        case PYTHON_DSL:
          return newPythonParser(
              cell, typeCoercerFactory, console, eventBus, buildFileParserOptions);
        default:
          throw new HumanReadableException(
              defaultBuildFileSyntax
                  + " is not supported by this version of Buck. Please update your Buck version or "
                  + "change parser.default_build_file_syntax configuration to one of "
                  + Arrays.toString(Syntax.values()));
      }
    }
  }

  private static PythonDslProjectBuildFileParser newPythonParser(
      Cell cell,
      TypeCoercerFactory typeCoercerFactory,
      Console console,
      BuckEventBus eventBus,
      ProjectBuildFileParserOptions buildFileParserOptions) {
    return new PythonDslProjectBuildFileParser(
        buildFileParserOptions,
        typeCoercerFactory,
        cell.getBuckConfig().getEnvironment(),
        eventBus,
        new DefaultProcessExecutor(console));
  }

  private static SkylarkProjectBuildFileParser newSkylarkParser(
      Cell cell,
      TypeCoercerFactory typeCoercerFactory,
      BuckEventBus eventBus,
      ProjectBuildFileParserOptions buildFileParserOptions) {
    return SkylarkProjectBuildFileParser.using(
        buildFileParserOptions,
        eventBus,
        SkylarkFilesystem.using(cell.getFilesystem()),
        BuckGlobals.builder()
            .setDisableImplicitNativeRules(buildFileParserOptions.getDisableImplicitNativeRules())
            .setDescriptions(buildFileParserOptions.getDescriptions())
            .setRuleFunctionFactory(new RuleFunctionFactory(typeCoercerFactory))
            .build(),
        new ConsoleEventHandler(eventBus, EventKind.ALL_EVENTS));
  }
}
