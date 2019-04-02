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

import com.facebook.buck.command.config.ConfigIgnoredByDaemon;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.exceptions.config.ErrorHandlingBuckConfig;
import com.facebook.buck.core.exceptions.handler.HumanReadableExceptionAugmentor;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.io.watchman.Capability;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.json.TargetCountVerificationParserDecorator;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.parser.AbstractParserConfig.SkylarkGlobHandler;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.api.Syntax;
import com.facebook.buck.parser.cache.impl.AbstractParserCacheConfig;
import com.facebook.buck.parser.cache.impl.CachingProjectBuildFileParserDecorator;
import com.facebook.buck.parser.cache.impl.ParserCache;
import com.facebook.buck.parser.cache.impl.ParserCacheConfig;
import com.facebook.buck.parser.decorators.EventReportingProjectBuildFileParser;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.skylark.io.GlobberFactory;
import com.facebook.buck.skylark.io.impl.HybridGlobberFactory;
import com.facebook.buck.skylark.io.impl.NativeGlobber;
import com.facebook.buck.skylark.io.impl.SyncCookieState;
import com.facebook.buck.skylark.parser.BuckGlobals;
import com.facebook.buck.skylark.parser.ConsoleEventHandler;
import com.facebook.buck.skylark.parser.RuleFunctionFactory;
import com.facebook.buck.skylark.parser.SkylarkProjectBuildFileParser;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class DefaultProjectBuildFileParserFactory implements ProjectBuildFileParserFactory {
  private final TypeCoercerFactory typeCoercerFactory;
  private final Console console;
  private final ParserPythonInterpreterProvider pythonInterpreterProvider;
  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final boolean enableProfiling;
  private final Optional<AtomicLong> processedBytes;
  private final ThrowingCloseableMemoizedSupplier<ManifestService, IOException>
      manifestServiceSupplier;
  private final FileHashCache fileHashCache;

  public DefaultProjectBuildFileParserFactory(
      TypeCoercerFactory typeCoercerFactory,
      Console console,
      ParserPythonInterpreterProvider pythonInterpreterProvider,
      KnownRuleTypesProvider knownRuleTypesProvider,
      boolean enableProfiling,
      Optional<AtomicLong> processedBytes,
      ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier,
      FileHashCache fileHashCache) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.console = console;
    this.pythonInterpreterProvider = pythonInterpreterProvider;
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.enableProfiling = enableProfiling;
    this.processedBytes = processedBytes;
    this.manifestServiceSupplier = manifestServiceSupplier;
    this.fileHashCache = fileHashCache;
  }

  public DefaultProjectBuildFileParserFactory(
      TypeCoercerFactory typeCoercerFactory,
      ParserPythonInterpreterProvider pythonInterpreterProvider,
      boolean enableProfiling,
      Optional<AtomicLong> processedBytes,
      KnownRuleTypesProvider knownRuleTypesProvider,
      ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier,
      FileHashCache fileHashCache) {
    this(
        typeCoercerFactory,
        Console.createNullConsole(),
        pythonInterpreterProvider,
        knownRuleTypesProvider,
        enableProfiling,
        processedBytes,
        manifestServiceSupplier,
        fileHashCache);
  }

  public DefaultProjectBuildFileParserFactory(
      TypeCoercerFactory typeCoercerFactory,
      Console console,
      ParserPythonInterpreterProvider pythonInterpreterProvider,
      KnownRuleTypesProvider knownRuleTypesProvider,
      ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier,
      FileHashCache fileHashCache) {
    this(
        typeCoercerFactory,
        console,
        pythonInterpreterProvider,
        knownRuleTypesProvider,
        false,
        Optional.empty(),
        manifestServiceSupplier,
        fileHashCache);
  }

  /**
   * Callers are responsible for managing the life-cycle of the created {@link
   * ProjectBuildFileParser}.
   */
  @Override
  public ProjectBuildFileParser createBuildFileParser(
      BuckEventBus eventBus, Cell cell, Watchman watchman) {

    ParserConfig parserConfig = cell.getBuckConfig().getView(ParserConfig.class);

    boolean useWatchmanGlob =
        parserConfig.getGlobHandler() == ParserConfig.GlobHandler.WATCHMAN
            && watchman.hasWildmatchGlob();
    boolean watchmanGlobStatResults =
        parserConfig.getWatchmanGlobSanityCheck() == ParserConfig.WatchmanGlobSanityCheck.STAT;
    boolean watchmanUseGlobGenerator =
        watchman.getCapabilities().contains(Capability.GLOB_GENERATOR);
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
            .setBuildFileName(cell.getBuckConfigView(ParserConfig.class).getBuildFileName())
            .setDefaultIncludes(parserConfig.getDefaultIncludes())
            .setDescriptions(knownRuleTypesProvider.get(cell).getDescriptions())
            .setUseWatchmanGlob(useWatchmanGlob)
            .setWatchmanGlobStatResults(watchmanGlobStatResults)
            .setWatchmanUseGlobGenerator(watchmanUseGlobGenerator)
            .setWatchman(watchman)
            .setWatchmanQueryTimeoutMs(parserConfig.getWatchmanQueryTimeoutMs())
            .setRawConfig(
                cell.getBuckConfig().getView(ConfigIgnoredByDaemon.class).getRawConfigForParser())
            .setBuildFileImportWhitelist(parserConfig.getBuildFileImportWhitelist())
            .setDisableImplicitNativeRules(parserConfig.getDisableImplicitNativeRules())
            .setWarnAboutDeprecatedSyntax(parserConfig.isWarnAboutDeprecatedSyntax())
            .setPackageImplicitIncludes(parserConfig.getPackageImplicitIncludes())
            .build();
    return EventReportingProjectBuildFileParser.of(
        createProjectBuildFileParser(
            cell, typeCoercerFactory, console, eventBus, parserConfig, buildFileParserOptions),
        eventBus);
  }

  /** Creates a delegate wrapper that counts the number of targets declared in a parsed file */
  private static ProjectBuildFileParser createTargetCountingWrapper(
      ProjectBuildFileParser aggregate, int targetCountThreshold, BuckEventBus eventBus) {
    return new TargetCountVerificationParserDecorator(aggregate, targetCountThreshold, eventBus);
  }

  private ProjectBuildFileParser addCachingDecoratorIfEnabled(
      BuckConfig buckConfig,
      SkylarkProjectBuildFileParser skylarkParser,
      ProjectFilesystem filesystem,
      BuckEventBus eventBus) {
    AbstractParserCacheConfig parserCacheConfig = buckConfig.getView(ParserCacheConfig.class);
    if (parserCacheConfig.isParserCacheEnabled()) {
      ParserCache parserCache =
          ParserCache.of(buckConfig, filesystem, manifestServiceSupplier, eventBus);
      return CachingProjectBuildFileParserDecorator.of(
          parserCache, skylarkParser, buckConfig.getConfig(), filesystem, fileHashCache);
    }

    return skylarkParser;
  }

  /** Creates a project build file parser based on Buck configuration settings. */
  private ProjectBuildFileParser createProjectBuildFileParser(
      Cell cell,
      TypeCoercerFactory typeCoercerFactory,
      Console console,
      BuckEventBus eventBus,
      ParserConfig parserConfig,
      ProjectBuildFileParserOptions buildFileParserOptions) {
    ProjectBuildFileParser parser;
    Syntax defaultBuildFileSyntax = parserConfig.getDefaultBuildFileSyntax();
    if (parserConfig.isPolyglotParsingEnabled()) {
      parser =
          HybridProjectBuildFileParser.using(
              ImmutableMap.of(
                  Syntax.PYTHON_DSL,
                  newPythonParser(
                      cell, typeCoercerFactory, console, eventBus, buildFileParserOptions),
                  Syntax.SKYLARK,
                  addCachingDecoratorIfEnabled(
                      cell.getBuckConfig(),
                      newSkylarkParser(
                          cell,
                          typeCoercerFactory,
                          eventBus,
                          buildFileParserOptions,
                          parserConfig.getSkylarkGlobHandler()),
                      cell.getFilesystem(),
                      eventBus)),
              defaultBuildFileSyntax);
    } else {
      switch (defaultBuildFileSyntax) {
        case SKYLARK:
          parser =
              addCachingDecoratorIfEnabled(
                  cell.getBuckConfig(),
                  newSkylarkParser(
                      cell,
                      typeCoercerFactory,
                      eventBus,
                      buildFileParserOptions,
                      parserConfig.getSkylarkGlobHandler()),
                  cell.getFilesystem(),
                  eventBus);
          break;
        case PYTHON_DSL:
          parser =
              newPythonParser(cell, typeCoercerFactory, console, eventBus, buildFileParserOptions);
          break;
        default:
          throw new HumanReadableException(
              defaultBuildFileSyntax
                  + " is not supported by this version of Buck. Please update your Buck version or "
                  + "change parser.default_build_file_syntax configuration to one of "
                  + Arrays.toString(Syntax.values()));
      }
    }

    parser = createTargetCountingWrapper(parser, parserConfig.getParserTargetThreshold(), eventBus);

    return parser;
  }

  private PythonDslProjectBuildFileParser newPythonParser(
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
        new DefaultProcessExecutor(console),
        processedBytes);
  }

  private static SkylarkProjectBuildFileParser newSkylarkParser(
      Cell cell,
      TypeCoercerFactory typeCoercerFactory,
      BuckEventBus eventBus,
      ProjectBuildFileParserOptions buildFileParserOptions,
      SkylarkGlobHandler skylarkGlobHandler) {
    GlobberFactory globberFactory;
    try {
      globberFactory = getSkylarkGlobberFactory(buildFileParserOptions, skylarkGlobHandler);
    } catch (IOException e) {
      throw new RuntimeException(
          "Watchman glob handler was requested, but Watchman client cannot be created", e);
    }
    BuckGlobals buckGlobals =
        BuckGlobals.builder()
            .setDisableImplicitNativeRules(buildFileParserOptions.getDisableImplicitNativeRules())
            .setDescriptions(buildFileParserOptions.getDescriptions())
            .setRuleFunctionFactory(new RuleFunctionFactory(typeCoercerFactory))
            .build();

    HumanReadableExceptionAugmentor augmentor;
    try {
      augmentor =
          new HumanReadableExceptionAugmentor(
              cell.getBuckConfig()
                  .getView(ErrorHandlingBuckConfig.class)
                  .getErrorMessageAugmentations());
    } catch (HumanReadableException e) {
      eventBus.post(ConsoleEvent.warning(e.getHumanReadableErrorMessage()));
      augmentor = new HumanReadableExceptionAugmentor(ImmutableMap.of());
    }

    try {
      // TODO(ttsugrii): consider using a less verbose event handler. Also fancy handler can be
      // configured for terminals that support it.
      ConsoleEventHandler eventHandler =
          new ConsoleEventHandler(
              eventBus,
              EventKind.ALL_EVENTS,
              ImmutableSet.copyOf(buckGlobals.getNativeModule().getFieldNames()),
              augmentor);
      SkylarkProjectBuildFileParser skylarkParser =
          SkylarkProjectBuildFileParser.using(
              buildFileParserOptions,
              eventBus,
              SkylarkFilesystem.using(cell.getFilesystem()),
              buckGlobals,
              eventHandler,
              globberFactory);

      // All built-ins should have already been discovered. Freezing improves performance by
      // avoiding synchronization during query operations. This operation is idempotent, so it's
      // fine to call this multiple times.
      // Note, that this method should be called after parser is created, to give a chance for all
      // static initializers that register SkylarkSignature to run.
      // See {@link AbstractBuckGlobals} where we force some initializers to run, as they are buried
      // behind a few layers of abstraction, and it's hard to ensure that we actually have loaded
      // the class first.
      Runtime.getBuiltinRegistry().freeze();

      return skylarkParser;
    } catch (EvalException e) {
      throw new RuntimeException(e);
    }
  }

  private static GlobberFactory getSkylarkGlobberFactory(
      ProjectBuildFileParserOptions buildFileParserOptions, SkylarkGlobHandler skylarkGlobHandler)
      throws IOException {
    SyncCookieState syncCookieState = new SyncCookieState();
    return skylarkGlobHandler == SkylarkGlobHandler.JAVA
            || buildFileParserOptions.getWatchman() == WatchmanFactory.NULL_WATCHMAN
        ? NativeGlobber::create
        : HybridGlobberFactory.using(
            buildFileParserOptions.getWatchman().createClient(),
            syncCookieState,
            buildFileParserOptions.getProjectRoot(),
            buildFileParserOptions.getWatchman().getProjectWatches());
  }
}
