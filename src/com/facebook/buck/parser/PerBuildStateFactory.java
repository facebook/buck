/*
 * Copyright 2018-present Facebook, Inc.
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
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public abstract class PerBuildStateFactory {

  protected final ThrowingCloseableMemoizedSupplier<ManifestService, IOException>
      manifestServiceSupplier;
  protected final FileHashCache fileHashCache;

  protected PerBuildStateFactory(
      ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier,
      FileHashCache fileHashCache) {
    this.manifestServiceSupplier = manifestServiceSupplier;
    this.fileHashCache = fileHashCache;
  }

  /**
   * Creates {@link PerBuildStateFactory} which can be used to create {@link PerBuildState}.
   * Depending on the configuration this method can create a factory that supports configurable
   * attributes.
   */
  public static PerBuildStateFactory createFactory(
      TypeCoercerFactory typeCoercerFactory,
      ConstructorArgMarshaller marshaller,
      KnownRuleTypesProvider knownRuleTypesProvider,
      ParserPythonInterpreterProvider parserPythonInterpreterProvider,
      BuckConfig buckConfig,
      Watchman watchman,
      BuckEventBus eventBus,
      ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier,
      FileHashCache fileHashCache) {
    return buckConfig.getView(ParserConfig.class).getEnableConfigurableAttributes()
        ? new PerBuildStateFactoryWithConfigurableAttributes(
            typeCoercerFactory,
            marshaller,
            knownRuleTypesProvider,
            parserPythonInterpreterProvider,
            watchman,
            eventBus,
            manifestServiceSupplier,
            fileHashCache)
        : new LegacyPerBuildStateFactory(
            typeCoercerFactory,
            marshaller,
            knownRuleTypesProvider,
            parserPythonInterpreterProvider,
            watchman,
            eventBus,
            manifestServiceSupplier,
            fileHashCache);
  }

  public PerBuildState create(
      DaemonicParserState daemonicParserState,
      ListeningExecutorService executorService,
      Cell rootCell,
      ImmutableList<String> targetPlatforms,
      boolean enableProfiling,
      SpeculativeParsing speculativeParsing) {
    return create(
        daemonicParserState,
        executorService,
        rootCell,
        targetPlatforms,
        enableProfiling,
        Optional.empty(),
        speculativeParsing);
  }

  public PerBuildState create(
      DaemonicParserState daemonicParserState,
      ListeningExecutorService executorService,
      Cell rootCell,
      ImmutableList<String> targetPlatforms,
      boolean enableProfiling,
      AtomicLong processedBytes,
      SpeculativeParsing speculativeParsing) {
    return create(
        daemonicParserState,
        executorService,
        rootCell,
        targetPlatforms,
        enableProfiling,
        Optional.of(processedBytes),
        speculativeParsing);
  }

  protected abstract PerBuildState create(
      DaemonicParserState daemonicParserState,
      ListeningExecutorService executorService,
      Cell rootCell,
      ImmutableList<String> targetPlatforms,
      boolean enableProfiling,
      Optional<AtomicLong> parseProcessedBytes,
      SpeculativeParsing speculativeParsing);
}
