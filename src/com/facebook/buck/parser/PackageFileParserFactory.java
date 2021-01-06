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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.exceptions.HumanReadableExceptionAugmentor;
import com.facebook.buck.core.exceptions.config.ErrorHandlingBuckConfig;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.starlark.eventhandler.ConsoleEventHandler;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.parser.api.PackageFileManifest;
import com.facebook.buck.parser.api.PackageFileParser;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.skylark.function.SkylarkPackageModule;
import com.facebook.buck.skylark.parser.BuckGlobals;
import com.facebook.buck.skylark.parser.FileKind;
import com.facebook.buck.skylark.parser.RuleFunctionFactory;
import com.facebook.buck.skylark.parser.SkylarkPackageFileParser;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import java.util.Optional;

/** Factory for creating instances of {@link PackageFileParser}. */
public class PackageFileParserFactory implements FileParserFactory<PackageFileManifest> {
  private final TypeCoercerFactory typeCoercerFactory;
  private final ParserPythonInterpreterProvider pythonInterpreterProvider;
  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final boolean enableProfiling;

  public PackageFileParserFactory(
      TypeCoercerFactory typeCoercerFactory,
      ParserPythonInterpreterProvider pythonInterpreterProvider,
      KnownRuleTypesProvider knownRuleTypesProvider,
      boolean enableProfiling) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.pythonInterpreterProvider = pythonInterpreterProvider;
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.enableProfiling = enableProfiling;
  }

  /**
   * Callers are responsible for managing the life-cycle of the created {@link PackageFileParser}.
   */
  @Override
  public PackageFileParser createFileParser(
      BuckEventBus eventBus, Cell cell, Watchman watchman, boolean threadSafe) {

    ParserConfig parserConfig = cell.getBuckConfig().getView(ParserConfig.class);
    Optional<String> pythonModuleSearchPath = parserConfig.getPythonModuleSearchPath();

    ProjectBuildFileParserOptions buildFileParserOptions =
        ProjectBuildFileParserOptions.builder()
            .setEnableProfiling(enableProfiling)
            .setProjectRoot(cell.getFilesystem().getRootPath())
            .setCellRoots(cell.getCellPathResolver().getCellPathsByRootCellExternalName())
            .setCellName(cell.getCanonicalName().getName())
            .setPythonInterpreter(pythonInterpreterProvider.getOrFail())
            .setPythonModuleSearchPath(pythonModuleSearchPath)
            .setAllowEmptyGlobs(false)
            .setIgnorePaths(cell.getFilesystem().getIgnorePaths())
            .setBuildFileName(FileKind.PACKAGE.toString())
            .setDefaultIncludes(parserConfig.getDefaultIncludes())
            .setDescriptions(ImmutableList.of())
            .setUseWatchmanGlob(false)
            .setWatchmanGlobStatResults(false)
            .setWatchmanUseGlobGenerator(false)
            .setWatchman(watchman)
            .setWatchmanQueryTimeoutMs(parserConfig.getWatchmanQueryTimeoutMs())
            .setRawConfig(
                cell.getBuckConfig().getView(ConfigIgnoredByDaemon.class).getRawConfigForParser())
            .setBuildFileImportWhitelist(parserConfig.getBuildFileImportWhitelist())
            .setImplicitNativeRulesState(parserConfig.getImplicitNativeRulesState())
            .setUserDefinedRulesState(parserConfig.getUserDefinedRulesState())
            .setWarnAboutDeprecatedSyntax(parserConfig.isWarnAboutDeprecatedSyntax())
            .setPackageImplicitIncludes(parserConfig.getPackageImplicitIncludes())
            .build();

    BuckGlobals buckGlobals =
        BuckGlobals.of(
            SkylarkPackageModule.PACKAGE_MODULE,
            ImmutableSet.of(),
            buildFileParserOptions.getUserDefinedRulesState(),
            buildFileParserOptions.getImplicitNativeRulesState(),
            new RuleFunctionFactory(typeCoercerFactory),
            LabelCache.newLabelCache(),
            knownRuleTypesProvider.getUserDefinedRuleTypes(cell),
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

    try {
      ConsoleEventHandler eventHandler =
          new ConsoleEventHandler(
              eventBus,
              EventKind.ALL_EVENTS,
              ImmutableSet.copyOf(buckGlobals.getNativeModule().getFieldNames()),
              augmentor);

      PackageFileParser parser =
          SkylarkPackageFileParser.using(
              buildFileParserOptions,
              eventBus,
              SkylarkFilesystem.using(cell.getFilesystem()),
              buckGlobals,
              eventHandler);

      // All built-ins should have already been discovered. Freezing improves performance by
      // avoiding synchronization during query operations. This operation is idempotent, so it's
      // fine to call this multiple times.
      // Note, that this method should be called after parser is created, to give a chance for all
      // static initializers that register SkylarkSignature to run.
      // See {@link AbstractBuckGlobals} where we force some initializers to run, as they are buried
      // behind a few layers of abstraction, and it's hard to ensure that we actually have loaded
      // the class first.
      Runtime.getBuiltinRegistry().freeze();

      return parser;
    } catch (EvalException e) {
      throw new RuntimeException(e);
    }
  }
}
