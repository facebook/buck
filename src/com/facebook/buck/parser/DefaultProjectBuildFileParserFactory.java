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

package com.facebook.buck.parser;

import com.facebook.buck.command.config.ConfigIgnoredByDaemon;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.exceptions.HumanReadableExceptionAugmentor;
import com.facebook.buck.core.exceptions.config.ErrorHandlingBuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.starlark.eventhandler.ConsoleEventHandler;
import com.facebook.buck.core.starlark.eventhandler.EventKind;
import com.facebook.buck.core.starlark.knowntypes.KnownUserDefinedRuleTypes;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.watchman.Capability;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.json.TargetCountVerificationParserDecorator;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.config.ParserConfig.SkylarkGlobHandler;
import com.facebook.buck.parser.decorators.EventReportingProjectBuildFileParser;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.skylark.function.SkylarkBuildModule;
import com.facebook.buck.skylark.io.GlobberFactory;
import com.facebook.buck.skylark.io.impl.HybridGlobberFactory;
import com.facebook.buck.skylark.io.impl.NativeGlobber;
import com.facebook.buck.skylark.parser.BuckGlobals;
import com.facebook.buck.skylark.parser.RuleFunctionFactory;
import com.facebook.buck.skylark.parser.SkylarkProjectBuildFileParser;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultProjectBuildFileParserFactory implements ProjectBuildFileParserFactory {
  private final TypeCoercerFactory typeCoercerFactory;
  private final ParserPythonInterpreterProvider pythonInterpreterProvider;
  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final boolean enableProfiling;

  public DefaultProjectBuildFileParserFactory(
      TypeCoercerFactory typeCoercerFactory,
      ParserPythonInterpreterProvider pythonInterpreterProvider,
      KnownRuleTypesProvider knownRuleTypesProvider,
      boolean enableProfiling) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.pythonInterpreterProvider = pythonInterpreterProvider;
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.enableProfiling = enableProfiling;
  }

  public DefaultProjectBuildFileParserFactory(
      TypeCoercerFactory typeCoercerFactory,
      ParserPythonInterpreterProvider pythonInterpreterProvider,
      boolean enableProfiling,
      KnownRuleTypesProvider knownRuleTypesProvider) {
    this(typeCoercerFactory, pythonInterpreterProvider, knownRuleTypesProvider, enableProfiling);
  }

  public DefaultProjectBuildFileParserFactory(
      TypeCoercerFactory typeCoercerFactory,
      ParserPythonInterpreterProvider pythonInterpreterProvider,
      KnownRuleTypesProvider knownRuleTypesProvider) {
    this(typeCoercerFactory, pythonInterpreterProvider, knownRuleTypesProvider, false);
  }

  /**
   * Callers are responsible for managing the life-cycle of the created {@link
   * ProjectBuildFileParser}.
   */
  @Override
  public ProjectBuildFileParser createFileParser(
      BuckEventBus eventBus, Cell cell, Watchman watchman, boolean threadSafe) {

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
            .setCellRoots(cell.getCellPathResolver().getCellPathsByRootCellExternalName())
            .setCellName(cell.getCanonicalName())
            .setPythonInterpreter(pythonInterpreterProvider.getOrFail())
            .setPythonModuleSearchPath(pythonModuleSearchPath)
            .setAllowEmptyGlobs(parserConfig.getAllowEmptyGlobs())
            .setIgnorePaths(cell.getFilesystem().getIgnoredDirectories())
            .setBuildFileName(cell.getBuckConfigView(ParserConfig.class).getBuildFileName())
            .setDefaultIncludes(parserConfig.getDefaultIncludes())
            .setDescriptions(knownRuleTypesProvider.getNativeRuleTypes(cell).getDescriptions())
            .setPerFeatureProviders(
                knownRuleTypesProvider.getNativeRuleTypes(cell).getPerFeatureProviders())
            .setUseWatchmanGlob(useWatchmanGlob)
            .setWatchmanGlobStatResults(watchmanGlobStatResults)
            .setWatchmanUseGlobGenerator(watchmanUseGlobGenerator)
            .setWatchman(watchman)
            .setWatchmanQueryTimeoutMs(parserConfig.getWatchmanQueryTimeoutMs())
            .setRawConfig(
                cell.getBuckConfig().getView(ConfigIgnoredByDaemon.class).getRawConfigForParser())
            .setImplicitNativeRulesState(parserConfig.getImplicitNativeRulesState())
            .setUserDefinedRulesState(parserConfig.getUserDefinedRulesState())
            .setWarnAboutDeprecatedSyntax(parserConfig.isWarnAboutDeprecatedSyntax())
            .setPackageImplicitIncludes(parserConfig.getPackageImplicitIncludes())
            .build();
    return EventReportingProjectBuildFileParser.of(
        createProjectBuildFileParser(
            cell, typeCoercerFactory, eventBus, parserConfig, buildFileParserOptions),
        eventBus);
  }

  /** Creates a delegate wrapper that counts the number of targets declared in a parsed file */
  private static ProjectBuildFileParser createTargetCountingWrapper(
      ProjectBuildFileParser aggregate,
      int targetCountThreshold,
      BuckEventBus eventBus,
      AbsPath cellRoot) {
    return new TargetCountVerificationParserDecorator(
        aggregate, targetCountThreshold, eventBus, cellRoot);
  }

  /** Creates a project build file parser based on Buck configuration settings. */
  private ProjectBuildFileParser createProjectBuildFileParser(
      Cell cell,
      TypeCoercerFactory typeCoercerFactory,
      BuckEventBus eventBus,
      ParserConfig parserConfig,
      ProjectBuildFileParserOptions buildFileParserOptions) {
    Preconditions.checkArgument(
        cell.getRoot().equals(buildFileParserOptions.getProjectRoot()),
        "cell '%s' root %s should be equal to parser options root %s",
        cell.getCanonicalName(),
        cell.getRoot(),
        buildFileParserOptions.getProjectRoot());

    // Skylark parser is thread-safe, but Python parser is not, so whenever we instantiate
    // Python parser we wrap it with ConcurrentParser to get thread safety

    ProjectBuildFileParser parser =
        newSkylarkParser(
            cell,
            typeCoercerFactory,
            knownRuleTypesProvider.getUserDefinedRuleTypes(cell),
            eventBus,
            buildFileParserOptions,
            parserConfig.getSkylarkGlobHandler());

    parser =
        createTargetCountingWrapper(
            parser,
            parserConfig.getParserTargetThreshold(),
            eventBus,
            buildFileParserOptions.getProjectRoot());

    return parser;
  }

  private static class StarlarkParserMapValue {
    private final Cell cell;
    private final KnownUserDefinedRuleTypes knownUserDefinedRuleTypes;
    private final BuckEventBus eventBus;
    private final ProjectBuildFileParserOptions buildFileParserOptions;
    private final SkylarkGlobHandler skylarkGlobHandler;
    private final SkylarkProjectBuildFileParser parser;

    public StarlarkParserMapValue(
        Cell cell,
        KnownUserDefinedRuleTypes knownUserDefinedRuleTypes,
        BuckEventBus eventBus,
        ProjectBuildFileParserOptions buildFileParserOptions,
        SkylarkGlobHandler skylarkGlobHandler,
        SkylarkProjectBuildFileParser parser) {
      this.cell = cell;
      this.knownUserDefinedRuleTypes = knownUserDefinedRuleTypes;
      this.eventBus = eventBus;
      this.buildFileParserOptions = buildFileParserOptions;
      this.skylarkGlobHandler = skylarkGlobHandler;
      this.parser = parser;
    }
  }

  /**
   * Only create one Starlark parser per cell. Parser factory is not meant to do that, but
   * untangling hybrid+python+starlark parser is not trivial.
   */
  private final ConcurrentHashMap<CanonicalCellName, StarlarkParserMapValue> starlarkParserByCell =
      new ConcurrentHashMap<>();

  private SkylarkProjectBuildFileParser newSkylarkParser(
      Cell cell,
      TypeCoercerFactory typeCoercerFactory,
      KnownUserDefinedRuleTypes knownUserDefinedRuleTypes,
      BuckEventBus eventBus,
      ProjectBuildFileParserOptions buildFileParserOptions,
      SkylarkGlobHandler skylarkGlobHandler) {
    StarlarkParserMapValue starlarkParserMapValue =
        starlarkParserByCell.computeIfAbsent(
            cell.getCanonicalName(),
            k -> {
              return new StarlarkParserMapValue(
                  cell,
                  knownUserDefinedRuleTypes,
                  eventBus,
                  buildFileParserOptions,
                  skylarkGlobHandler,
                  newSkylarkParserUncached(
                      cell,
                      typeCoercerFactory,
                      knownUserDefinedRuleTypes,
                      eventBus,
                      buildFileParserOptions,
                      skylarkGlobHandler));
            });

    // Assert cached value was created using the same parameter,
    // i.e. we were caching correctly.
    Preconditions.checkState(starlarkParserMapValue.cell == cell);
    Preconditions.checkState(
        starlarkParserMapValue.knownUserDefinedRuleTypes == knownUserDefinedRuleTypes);
    Preconditions.checkState(starlarkParserMapValue.eventBus == eventBus);
    Preconditions.checkState(
        Objects.equals(starlarkParserMapValue.buildFileParserOptions, buildFileParserOptions));
    Preconditions.checkState(starlarkParserMapValue.skylarkGlobHandler == skylarkGlobHandler);
    return starlarkParserMapValue.parser;
  }

  private SkylarkProjectBuildFileParser newSkylarkParserUncached(
      Cell cell,
      TypeCoercerFactory typeCoercerFactory,
      KnownUserDefinedRuleTypes knownUserDefinedRuleTypes,
      BuckEventBus eventBus,
      ProjectBuildFileParserOptions buildFileParserOptions,
      SkylarkGlobHandler skylarkGlobHandler) {
    GlobberFactory globberFactory =
        getSkylarkGlobberFactory(buildFileParserOptions, skylarkGlobHandler);

    BuckGlobals buckGlobals =
        BuckGlobals.of(
            SkylarkBuildModule.BUILD_MODULE,
            buildFileParserOptions.getDescriptions(),
            buildFileParserOptions.getUserDefinedRulesState(),
            buildFileParserOptions.getImplicitNativeRulesState(),
            new RuleFunctionFactory(typeCoercerFactory),
            knownUserDefinedRuleTypes,
            buildFileParserOptions.getPerFeatureProviders());

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

    // TODO(ttsugrii): consider using a less verbose event handler. Also fancy handler can be
    // configured for terminals that support it.
    ConsoleEventHandler eventHandler =
        new ConsoleEventHandler(
            eventBus,
            EventKind.ALL_EVENTS,
            ImmutableSet.copyOf(buckGlobals.getNativeModuleFieldNames()),
            augmentor);

    return SkylarkProjectBuildFileParser.using(
        buildFileParserOptions, eventBus, buckGlobals, eventHandler, globberFactory);
  }

  private static GlobberFactory getSkylarkGlobberFactory(
      ProjectBuildFileParserOptions buildFileParserOptions, SkylarkGlobHandler skylarkGlobHandler) {
    switch (skylarkGlobHandler) {
      case JAVA:
        return nativeGlobberFactory(buildFileParserOptions);
      case WATCHMAN:
        return watchmanGlobberFactory(buildFileParserOptions);
      case EDEN_THRIFT:
      case EDEN_THRIFT_NO_FALLBACK:
        return edenThriftGlobberFactory(skylarkGlobHandler);
      default:
        throw new IllegalStateException("unreachable");
    }
  }

  private static GlobberFactory edenThriftGlobberFactory(SkylarkGlobHandler skylarkGlobHandler) {
    throw new RuntimeException(skylarkGlobHandler + " globber is not implemented");
  }

  private static GlobberFactory watchmanGlobberFactory(
      ProjectBuildFileParserOptions buildFileParserOptions) {
    if (buildFileParserOptions.getWatchman() instanceof WatchmanFactory.NullWatchman) {
      return nativeGlobberFactory(buildFileParserOptions);
    } else {
      return HybridGlobberFactory.using(
          buildFileParserOptions.getWatchman(), buildFileParserOptions.getProjectRoot());
    }
  }

  private static GlobberFactory nativeGlobberFactory(
      ProjectBuildFileParserOptions buildFileParserOptions) {
    return new NativeGlobber.Factory(buildFileParserOptions.getProjectRoot());
  }
}
